package com.aibill.android.data.remote.dto.response

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AiParseResponseDto(
    @Json(name = "items") val items: List<AiParsedItemDto>,
    @Json(name = "raw_input") val rawInput: String,
    /**
     * 本次解析在服务端的日志 id（反馈闭环的钥匙，见 [ApiResponse.parseLogId]）。
     * 只有「服务端真的调了模型 / 走了快捷解析」的形态才有；
     * 被服务端预筛挡下、上游超时等形态没有 → **不存在可反馈对象**，不要凭空构造。
     */
    @Json(name = "parse_log_id") val parseLogId: Long? = null,
    /**
     * 因请求的 `max_items` 被服务端裁掉的**多余条数**（契约保证恒出现，0 = 没丢）。
     *
     * 时机：服务端**先出站佐证、再裁剪**，所以 [items] 里返回的那笔是过了佐证的。
     * 语义：只算因 `max_items` 被裁掉的条数（不是预筛/幻觉丢弃数）。
     * 旧服务端无此字段 → 默认 0（此时客户端另有「响应含多条」的兜底计数，见
     * [com.aibill.android.domain.notification.CaptureFunnelStats.AI_EXTRA_ITEMS_REPORT_KEY]）。
     */
    @Json(name = "extra_items_dropped") val extraItemsDropped: Int = 0,
)

@JsonClass(generateAdapter = true)
data class AiParsedItemDto(
    @Json(name = "type") val type: String,
    @Json(name = "amount") val amount: Int,
    /** transfer 类型必为 null（PRD §6.5.4） */
    @Json(name = "category_id") val categoryId: Int?,
    @Json(name = "category_name") val categoryName: String?,
    @Json(name = "category_icon") val categoryIcon: String?,
    @Json(name = "description") val description: String?,
    @Json(name = "date") val date: String,
    @Json(name = "account_id") val accountId: Int?,
    @Json(name = "account_name") val accountName: String?,
    @Json(name = "target_account_id") val targetAccountId: Int?,
    @Json(name = "target_account_name") val targetAccountName: String?,

    // ── S3：服务端机械抽取/推导的出参（不进 prompt，不会污染账本，全部可空）──
    // 缺失时客户端按「旧行为」处理（兼容旧服务端）。

    /** 商户名（文本关键词机械抽取） */
    @Json(name = "merchant") val merchant: String? = null,
    /** 订单号（纯复制任务，服务端机械抽取而非让模型生成） */
    @Json(name = "order_no") val orderNo: String? = null,
    /** 卡号尾号，与 balance_* 在**同一条元素**上成对出现 */
    @Json(name = "card_tail") val cardTail: String? = null,
    /** 交易后余额（分）；**仅展示/软校验，绝不用于写余额** */
    @Json(name = "balance_after_cents") val balanceAfterCents: Int? = null,
    /** 余额命中的原始片段（含「可用余额」等前缀）——客户端按前缀做同模板自比 */
    @Json(name = "balance_text") val balanceText: String? = null,
    /** 交易时间 `HH:mm`（抽不到为 null），与 `POST /transactions` 同格式 */
    @Json(name = "time") val time: String? = null,
    /** 置信度枚举（服务端派生，非模型自评）。**仅用于展示/排序，不要拿来设阈值** */
    @Json(name = "confidence") val confidence: String? = null,
    /** 置信度的推导原因，便于审计 */
    @Json(name = "confidence_reasons") val confidenceReasons: List<String>? = null,
    /**
     * **是否该进待审**——服务端唯一的阈值实现（verifyParsed=review / 分类未精确命中 /
     * 金额来自裸数字）。客户端只用它作「不自动入账」判据，自己不再算第二套阈值；
     * null = 旧服务端没给，此时回退到客户端的旧启发式。
     */
    @Json(name = "needs_review") val needsReview: Boolean? = null,
)
