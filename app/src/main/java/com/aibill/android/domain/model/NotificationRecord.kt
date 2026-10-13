package com.aibill.android.domain.model

/**
 * 通知记录的领域读模型（通知中心列表 / 待审池）。
 *
 * 「Entity 不出 data 层」：`NotificationRecordEntity` 留在 data 层，
 * 读路径（`domain/notification/ReviewQueue`）交给上层的就是这个纯模型，
 * 转换点唯一（`data/notification/NotificationRecordMapper`）。
 *
 * 字段与 Entity 同名同义，只保留读路径用到的列；
 * [status] 取值 `raw` / `parsed` / `confirmed` / `ignored`。
 */
data class NotificationRecord(
    val id: Long = 0,
    val packageName: String,
    val title: String? = null,
    val content: String,
    val parsedAmount: Int? = null, // 单位：分
    val parsedType: String? = null,
    val parsedDescription: String? = null,
    val status: String = "raw",
    val receivedAt: Long = 0,
) {
    companion object {
        /** 待审、可审（有 AI 结果，缺信息/未确认）。角标只数它。 */
        const val STATUS_PARSED = "parsed"

        /** 历史遗留的解析失败原文（≤1.6.1 写入，现已不再写）；仍展示、可忽略，但不可审。 */
        const val STATUS_RAW = "raw"

        /** 已确认入账（通知中心里「最近已确认」的展示项）。 */
        const val STATUS_CONFIRMED = "confirmed"

        /** 已忽略（可恢复；纯状态回滚，无入账副作用）。 */
        const val STATUS_IGNORED = "ignored"
    }
}
