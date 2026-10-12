package com.aibill.android.domain.notification

/**
 * AI 解析反馈：把**用户对 AI 结果的纠正**回传给服务端（跨端契约 §2）。
 *
 * ## 为什么需要它
 *
 * 服务端早就实现了 `/api/ai/parse-feedback` 与学习链路，但客户端从未调用
 * （连 `parse_log_id` 都在 DTO 层被 Moshi 丢掉了）——于是「AI 越用越准」这条闭环
 * 一直是断的：服务端只能看到自己的解析结果，看不到用户改成了什么。
 *
 * ## 语义（由服务端定，客户端只如实表达）
 *
 * - 传**用户最终态** `final_items`，不是原始结果、也不是补丁；
 * - **删除 = 该项不出现在 `final_items` 里**（本应用的软删除到回收站即算用户不要这笔）；
 * - `modified` 由客户端给：只有「用户确实改了」才传 true。
 *
 * ⚠️ 本应用一次通知只入**一笔**（评分窗口择优），因此 `final_items` 长度只会是 0 或 1。
 *
 * ## 失败姿态
 *
 * 纯 best-effort：**绝不阻塞、绝不上抛**用户操作。没有 `parse_log_id`（手工记账、
 * 外部静默入账、服务端预筛挡下的形态）时静默跳过；发送失败只记日志（下次改动还会再试）。
 */
interface ParseFeedback {

    /**
     * 用户改动了某一笔 AI 自动入账的记录。
     *
     * @param serverTransactionId 服务端事务 id（用户界面上唯一可见的标识）
     * @param finalItem 该笔的**最终态**（用户确认后的字段值）
     */
    suspend fun reportCorrected(serverTransactionId: Int, finalItem: FeedbackItem)

    /** 用户删除了某一笔 AI 自动入账的记录（`final_items` 不含它）。 */
    suspend fun reportDeleted(serverTransactionId: Int)
}

/**
 * 回传的单笔最终态。字段与 `POST /api/ai/parse` 的 items 同形（服务端按同一形状解析）。
 */
data class FeedbackItem(
    val type: String,
    val amount: Int,
    val categoryId: Int? = null,
    val categoryName: String? = null,
    val categoryIcon: String? = null,
    val description: String? = null,
    val date: String? = null,
    val time: String? = null,
    val accountId: Int? = null,
)
