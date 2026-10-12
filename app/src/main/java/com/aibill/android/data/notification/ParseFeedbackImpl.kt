package com.aibill.android.data.notification

import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.data.remote.api.AiApi
import com.aibill.android.data.remote.dto.request.ParseFeedbackRequest
import com.aibill.android.data.remote.dto.response.AiParsedItemDto
import com.aibill.android.domain.notification.FeedbackItem
import com.aibill.android.domain.notification.ParseFeedback
import com.aibill.android.util.AppLogger
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ParseFeedback] 的实现：从服务端事务 id 反查本地锚点 → 回传用户最终态。
 *
 * ## 三个刻意的设计
 *
 * 1. **没有锚点就是 no-op**：手工记账、外部静默入账、服务端预筛挡下的形态都没有
 *    `parse_log_id`，这里静默跳过——绝不凭空构造 id 去反馈。
 * 2. **发送成功后清锚点**：同一笔反复编辑不会重复上报（幂等由客户端先手保障；
 *    服务端也有 upsert，但少发一次就少一次噪声）。
 * 3. **失败只记日志**：反馈是旁路，任何异常都不能影响用户正在做的事；
 *    取消要原样传播（结构化取消）。
 */
@Singleton
class ParseFeedbackImpl @Inject constructor(
    private val aiApi: AiApi,
    private val pendingTransactionDao: PendingTransactionDao,
    private val appLogger: AppLogger,
) : ParseFeedback {

    override suspend fun reportCorrected(serverTransactionId: Int, finalItem: FeedbackItem) {
        send(
            serverTransactionId = serverTransactionId,
            finalItems = listOf(finalItem.toDto()),
            what = "用户修正",
        )
    }

    override suspend fun reportDeleted(serverTransactionId: Int) {
        // 删除 = 该笔不出现在 final_items 里（契约 §2）
        send(serverTransactionId = serverTransactionId, finalItems = emptyList(), what = "用户删除")
    }

    private suspend fun send(
        serverTransactionId: Int,
        finalItems: List<AiParsedItemDto>,
        what: String,
    ) {
        val parseLogId = try {
            pendingTransactionDao.findByServerTransactionId(serverTransactionId)?.parseLogId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            appLogger.warn("FEEDBACK", "反查 parse_log_id 失败 id=$serverTransactionId err=${e.message}")
            null
        }
        if (parseLogId == null) {
            // 非 AI 解析路径，或该笔的反馈已发过 → 静默跳过（不是错误）
            appLogger.debug("FEEDBACK", "无可反馈锚点，跳过（$what id=$serverTransactionId）")
            return
        }

        try {
            val response = aiApi.parseFeedback(
                ParseFeedbackRequest(
                    parseLogId = parseLogId,
                    finalItems = finalItems,
                    modified = true,
                    channel = CHANNEL_NOTIFICATION,
                )
            )
            if (response.code == 0) {
                pendingTransactionDao.clearParseLogId(serverTransactionId)
                appLogger.info(
                    "FEEDBACK",
                    "$what 已回传 parseLogId=$parseLogId id=$serverTransactionId items=${finalItems.size}",
                )
            } else {
                // 3002 = 日志不存在/不属于当前用户：不可恢复，清锚点免得每次改动都白试
                if (response.code == CODE_LOG_NOT_FOUND) {
                    pendingTransactionDao.clearParseLogId(serverTransactionId)
                }
                appLogger.warn("FEEDBACK", "$what 回传失败 code=${response.code} msg=${response.message}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 网络失败留锚点：下次用户再改还会带上，无需重试队列
            appLogger.warn("FEEDBACK", "$what 回传异常（保留锚点待下次）: ${e.message}")
        }
    }

    private fun FeedbackItem.toDto() = AiParsedItemDto(
        type = type,
        amount = amount,
        categoryId = categoryId,
        categoryName = categoryName,
        categoryIcon = categoryIcon,
        description = description,
        date = date.orEmpty(),
        accountId = accountId,
        accountName = null,
        targetAccountId = null,
        targetAccountName = null,
    )

    private companion object {
        /** 通知链路入口归因（手工链路不参与本反馈：它没有 parse_log_id） */
        const val CHANNEL_NOTIFICATION = "nls"

        /** 服务端「日志不存在/不属于当前用户」 */
        const val CODE_LOG_NOT_FOUND = 3002
    }
}
