package com.aibill.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import com.aibill.android.di.ApplicationScope
import com.aibill.android.domain.record.RecordIntent
import com.aibill.android.domain.record.RecordIntake
import com.aibill.android.presentation.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
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

    /**
     * 入账（记一笔）唯一入口。clientId 生成、实体装配、跨表事务与排同步都在里面，
     * 本类只表达「静默入账这一笔」的意图（架构候选 1 收口）。
     *
     * 排同步不再绕 `TransactionRepository.syncPending()`（该方法已在架构候选 3 删除）：
     * 排调度归离线队列 module 的 `SyncQueue.requestSync()`，失败处置
     * （只记日志、不回滚、不抛出）由入账 module 统一承担。
     */
    @Inject lateinit var recordIntake: RecordIntake

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
     * 表达入账意图 → 由入账 module 写库并排一次同步。
     *
     * **private 是收口后的状态**（架构候选 5）：本函数曾是 `internal`，理由写着「为让单测
     * 直接调它，绕开 `onReceive` 的 `inject()`」——逻辑住在壳里、可见性只为测试放宽，
     * 那是本仓库唯一的测试后门形态。但候选 1 之后它只剩「翻译成 [RecordIntent] 再交给
     * [RecordIntake]」这一步，写入逻辑根本不在这里，于是后门没有必要存在：
     * 可测的部分已经全在 `data/record/RecordIntakeImpl` 里，由 `RecordIntakeTest`
     * 用真实的入账实现直接驱动。
     *
     * 权限检查与 `autoConfirm` 判定仍留在 [onReceive]，位置与逻辑均未改动。
     */
    private suspend fun handleAutoConfirmRecord(
        amount: Int,
        type: String,
        description: String?,
    ) {
        // clientId 是查这一行的唯一凭据（事后对账用），必须从返回值拿
        val outcome = recordIntake.intake(
            RecordIntent.ExternalQuickRecord(
                amount = amount,
                type = type,
                description = description,
            )
        )
        Timber.d("ExternalActionReceiver: 静默记录成功 amount=$amount clientId=${outcome.clientId}")
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
