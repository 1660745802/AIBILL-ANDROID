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
)
