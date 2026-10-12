package com.aibill.android.data.notification

import com.aibill.android.data.local.entity.NotificationRecordEntity
import com.aibill.android.domain.model.NotificationRecord

/**
 * `NotificationRecordEntity` → [NotificationRecord] 的唯一转换点
 * （ARCHITECTURE §1.3「转换只走 Mapper」）。Entity 不出 data 层：
 * 只有 data 层的 [ReviewQueueImpl] 需要它，上层拿到的一律是 domain 模型。
 */
fun NotificationRecordEntity.toDomain(): NotificationRecord = NotificationRecord(
    id = id,
    packageName = packageName,
    title = title,
    content = content,
    parsedAmount = parsedAmount,
    parsedType = parsedType,
    parsedDescription = parsedDescription,
    status = status,
    receivedAt = receivedAt,
)
