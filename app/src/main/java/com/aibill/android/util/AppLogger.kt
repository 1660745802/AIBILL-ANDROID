package com.aibill.android.util

import com.aibill.android.data.local.dao.AppLogDao
import com.aibill.android.data.local.entity.AppLogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用内持久化日志工具。
 *
 * 与 Timber（logcat）不同：
 * - Timber 日志随进程死亡消失
 * - AppLogger 写入 Room DB，进程重启后仍可查看
 * - 用户可导出日志文件给开发者排查问题
 *
 * 用法：
 *   appLogger.info("NLS", "通知到达: pkg=com.tencent.mm")
 *   appLogger.warn("AI", "AI返回空: 非支付通知")
 */
@Singleton
class AppLogger @Inject constructor(
    private val appLogDao: AppLogDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun debug(tag: String, message: String) = log("DEBUG", tag, message)
    fun info(tag: String, message: String) = log("INFO", tag, message)
    fun warn(tag: String, message: String) = log("WARN", tag, message)
    fun error(tag: String, message: String) = log("ERROR", tag, message)

    private fun log(level: String, tag: String, message: String) {
        // 同时写 logcat（开发调试用）
        when (level) {
            "DEBUG" -> Timber.tag(tag).d(message)
            "INFO" -> Timber.tag(tag).i(message)
            "WARN" -> Timber.tag(tag).w(message)
            "ERROR" -> Timber.tag(tag).e(message)
        }
        // 写 DB（持久化）
        scope.launch {
            try {
                appLogDao.insert(
                    AppLogEntity(
                        level = level,
                        tag = tag,
                        message = message,
                    )
                )
            } catch (_: Exception) {
                // DB 写入失败不能崩，静默忽略
            }
        }
    }

    /** 清理所有日志（DB记录 + cache文件）— 用户手动触发 */
    fun cleanAllLogs(context: android.content.Context? = null) {
        scope.launch {
            try {
                appLogDao.cleanBefore(System.currentTimeMillis()) // 清全部
                context?.cacheDir?.listFiles()?.filter {
                    it.name.startsWith("aibill_log_")
                }?.forEach { it.delete() }
            } catch (_: Exception) {}
        }
    }

    /** App 打开时自动清理 7 天前的日志记录 + 旧日志文件 */
    /** App 打开时自动清理 2 天前的日志 */
    fun autoCleanOldLogs(context: android.content.Context? = null) {
        val twoDaysAgo = System.currentTimeMillis() - 2 * 24 * 60 * 60 * 1000L
        scope.launch {
            try {
                appLogDao.cleanBefore(twoDaysAgo)
                context?.cacheDir?.listFiles()?.filter {
                    it.name.startsWith("aibill_log_") && it.lastModified() < twoDaysAgo
                }?.forEach { it.delete() }
            } catch (_: Exception) {}
        }
    }

    /** 导出全部日志为文本（不限条数） */
    suspend fun exportAsText(): String {
        val logs = appLogDao.getRecent()
        return buildString {
            appendLine("=== AIBILL 日志导出 ===")
            appendLine("导出时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
            appendLine()
            for (log in logs) {
                val time = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date(log.timestamp))
                appendLine("[$time] [${log.level}] [${log.tag}] ${log.message}")
            }
        }
    }
}
