package com.aibill.android.data.notification

import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.domain.model.NotificationRecord
import com.aibill.android.domain.notification.ReviewQueue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ReviewQueue] 的 DAO 实现。DAO 的查询细节（哪些 status、时间窗、排序、LIMIT）
 * 收在这里，调用方只看到「观察待审池」这个意图。
 *
 * Entity → domain 模型的转换只走 [toDomain]（Mapper 收口）。
 */
@Singleton
class ReviewQueueImpl @Inject constructor(
    private val dao: NotificationRecordDao,
) : ReviewQueue {

    override fun observe(sinceMillis: Long): Flow<List<NotificationRecord>> =
        dao.observeAllWithConfirmedSince(sinceMillis).map { rows -> rows.map { it.toDomain() } }

    override fun observePendingCount(): Flow<Int> = dao.observePendingCount()

    override suspend fun findById(id: Long): NotificationRecord? = dao.findById(id)?.toDomain()

    override suspend fun ignore(id: Long) = dao.updateStatus(id, "ignored")
}
