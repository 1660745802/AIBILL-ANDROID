package com.aibill.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.data.local.entity.PendingTransactionEntity
import com.aibill.android.di.ApplicationScope
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.repository.TransactionRepository
import com.aibill.android.presentation.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject

/**
 * 外部 Intent 接收器（Tasker / 快捷指令 / 第三方 App 对接）
 *
 * 支持两个 Action:
 * - ACTION_QUICK_RECORD: 快速记账
 *   - auto_confirm=false（默认）：打开 App 预填页（任意调用方可用）
 *   - auto_confirm=true：直接静默入账，**仅同签名 App 可用**
 *     （运行时检查 [PERMISSION_QUICK_RECORD] signature 权限）
 * - ACTION_AI_PARSE: AI 文本解析（打开 App 首页并填入输入框）
 *
 * 安全模型：auto_confirm 静默入账能力通过 signature permission 保护，
 * 防止第三方 App 伪造 Intent 任意金额入账。
 */
@AndroidEntryPoint
class ExternalActionReceiver : BroadcastReceiver() {

    @Inject lateinit var pendingTransactionDao: PendingTransactionDao

    /**
     * 只用来排同步（[syncPending]）。**不**用它写库——写库仍走
     * [pendingTransactionDao]，与 `NotificationProcessor` / `NotificationActionReceiver`
     * 的既有写法一致；把三个服务一齐换成 `PendingTransactionRepository` 是另一个
     * 独立的 repo 级切片，不在本次范围内。
     */
    @Inject lateinit var transactionRepository: TransactionRepository

    // PR 修复：注入进程级 ApplicationScope，替代每次实例化创建的泄漏 Scope。
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    companion object {
        const val ACTION_QUICK_RECORD = "com.aibill.ACTION_QUICK_RECORD"
        const val ACTION_AI_PARSE = "com.aibill.ACTION_AI_PARSE"

        const val EXTRA_AMOUNT = "amount"
        const val EXTRA_TYPE = "type"
        const val EXTRA_CATEGORY = "category"
        const val EXTRA_DESCRIPTION = "description"
        const val EXTRA_AUTO_CONFIRM = "auto_confirm"
        const val EXTRA_INPUT = "input"

        /** 同签名权限名：仅签名一致的 App 才能调用静默入账 */
        const val PERMISSION_QUICK_RECORD = "com.aibill.android.permission.QUICK_RECORD"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Timber.d("ExternalActionReceiver: action=$action, callingUid=${Binder.getCallingUid()}")

        when (action) {
            ACTION_QUICK_RECORD -> handleQuickRecord(context, intent)
            ACTION_AI_PARSE -> handleAiParse(context, intent)
        }
    }

    /**
     * 静默入账本体（`autoConfirm` 且签名权限已通过时调用）：
     * 构造 pending 行 → 写库 → 排一次同步。
     *
     * internal 而非 private，与 [AutoRecordActionReceiver.handleUndo] 同款：为让
     * 单元测试能直接调本函数，绕开 `onReceive` 的两道门——
     * `Hilt_ExternalActionReceiver` 在 `onReceive` 与 `goAsync()` 里都会 `inject()`，
     * 而本工程的 Robolectric 基建给不出 `@HiltAndroidApp` 的 `Application`：
     * 用 `Application::class` 抛 Hilt 前置条件，用 `AiBillApp::class` 抛
     * `KeyStoreException: AndroidKeyStore not found`（`hilt-android-testing` 本轮不引入）。
     *
     * 本函数是**纯搬移**：判断、日志级别与文案、写入内容均与搬移前逐行相同；
     * 唯一的行为顺序差异是「静默记录成功」日志移到了排同步之后（因为排同步在前）。
     * 权限检查与 `autoConfirm` 判断仍留在 [onReceive]，位置与逻辑均未改动。
     */
    internal suspend fun handleAutoConfirmRecord(
        amount: Int,
        type: String,
        description: String?,
    ) {
        // 提为局部变量：日志需要 clientId 才能事后对账（它是查这行的唯一凭据）
        val clientId = UUID.randomUUID().toString()
        val entity = PendingTransactionEntity(
            clientId = clientId,
            type = type,
            amount = amount,
            description = description,
            date = LocalDate.now().toString(),
            time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm")),
            source = "external_intent",
            sourceDetail = "tasker_quick_record",
            clientCreatedAt = LocalDateTime.now().toString()
        )
        pendingTransactionDao.insert(entity)
        // 排一次同步：否则这条 pending 只能等「网络跳变 / 下次启动 /
        // 用户手动点同步」——WiFi 常连的静默入账会一直躺着。
        // ⚠️ Success 只代表**已入队**，不代表已上传。
        // 失败只记日志：记账已落库，不回滚；静默入账也没有 UI 可提示。
        val sync = transactionRepository.syncPending()
        if (sync is Result.Error) {
            Timber.w(
                "ExternalActionReceiver: 已入账但排同步失败 " +
                    "clientId=$clientId message=${sync.message}",
            )
        }
        Timber.d("ExternalActionReceiver: 静默记录成功 amount=$amount")
    }

    private fun handleQuickRecord(context: Context, intent: Intent) {
        val amount = intent.getIntExtra(EXTRA_AMOUNT, 0)
        val type = intent.getStringExtra(EXTRA_TYPE) ?: "expense"
        val category = intent.getStringExtra(EXTRA_CATEGORY)
        val description = intent.getStringExtra(EXTRA_DESCRIPTION)
        val autoConfirm = intent.getBooleanExtra(EXTRA_AUTO_CONFIRM, false)

        if (amount <= 0) {
            Timber.w("ExternalActionReceiver: 无效金额 amount=$amount")
            return
        }

        if (autoConfirm) {
            // ★ 安全检查：静默入账仅同签名 App 可用。
            // 必须校验**调用方** UID 是否持有 signature 权限，不能用
            // ContextCompat.checkSelfPermission（那只检查自己进程，而本 App 自己
            // 声明了 uses-permission，永远 GRANTED，等于没有防护）。
            val callingUid = Binder.getCallingUid()
            val granted = context.checkPermission(
                PERMISSION_QUICK_RECORD, callingUid, -1
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Timber.w(
                    "ExternalActionReceiver: 拒绝静默入账，" +
                        "调用方(uid=$callingUid)未持有 signature 权限 PERMISSION_QUICK_RECORD"
                )
                return
            }
            val pendingResult = goAsync()
            scope.launch {
                try {
                    handleAutoConfirmRecord(amount, type, description)
                } finally {
                    pendingResult.finish()
                }
            }
        } else {
            // 打开 App 手动记账页预填（任意调用方可用）
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("navigate_to", "manual_record")
                putExtra(EXTRA_AMOUNT, amount)
                putExtra(EXTRA_TYPE, type)
                putExtra(EXTRA_CATEGORY, category)
                putExtra(EXTRA_DESCRIPTION, description)
            }
            context.startActivity(launchIntent)
        }
    }

    private fun handleAiParse(context: Context, intent: Intent) {
        val input = intent.getStringExtra(EXTRA_INPUT)
        if (input.isNullOrBlank()) {
            Timber.w("ExternalActionReceiver: AI_PARSE 缺少 input")
            return
        }

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "home")
            putExtra("ai_input", input)
        }
        context.startActivity(launchIntent)
    }
}
