package com.aibill.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.data.local.entity.PendingTransactionEntity
import com.aibill.android.presentation.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 * - ACTION_QUICK_RECORD: 快速记账（可直接静默入库或打开预填页）
 * - ACTION_AI_PARSE: AI 文本解析（打开 App 首页并填入输入框）
 */
@AndroidEntryPoint
class ExternalActionReceiver : BroadcastReceiver() {

    @Inject lateinit var pendingTransactionDao: PendingTransactionDao

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val ACTION_QUICK_RECORD = "com.aibill.ACTION_QUICK_RECORD"
        const val ACTION_AI_PARSE = "com.aibill.ACTION_AI_PARSE"

        const val EXTRA_AMOUNT = "amount"
        const val EXTRA_TYPE = "type"
        const val EXTRA_CATEGORY = "category"
        const val EXTRA_DESCRIPTION = "description"
        const val EXTRA_AUTO_CONFIRM = "auto_confirm"
        const val EXTRA_INPUT = "input"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Timber.d("ExternalActionReceiver: action=$action")

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
            // 直接静默入库
            scope.launch {
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
            }
        } else {
            // 打开 App 手动记账页预填
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
