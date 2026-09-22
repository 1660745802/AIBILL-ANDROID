package com.aibill.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import androidx.core.content.ContextCompat
import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.data.local.entity.PendingTransactionEntity
import com.aibill.android.di.ApplicationScope
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
            // ★ 安全检查：静默入账仅同签名 App 可用
            val granted = ContextCompat.checkSelfPermission(
                context, PERMISSION_QUICK_RECORD
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Timber.w(
                    "ExternalActionReceiver: 拒绝静默入账，" +
                        "调用方未持有 signature 权限 PERMISSION_QUICK_RECORD"
                )
                return
            }
            val pendingResult = goAsync()
            scope.launch {
                try {
                    val entity = PendingTransactionEntity(
                        clientId = UUID.randomUUID().toString(),
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
                    Timber.d("ExternalActionReceiver: 静默记录成功 amount=$amount")
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
