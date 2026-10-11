package com.aibill.android.data.repository

import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.data.local.entity.NotificationRecordEntity
import com.aibill.android.domain.repository.NotificationRecordRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationRecordRepositoryImpl @Inject constructor(
    private val dao: NotificationRecordDao,
) : NotificationRecordRepository {
    override fun observePendingCount(): Flow<Int> = dao.observePendingCount()
    override fun observePending(): Flow<List<NotificationRecordEntity>> = dao.observePending()
    override fun observeConfirmed(): Flow<List<NotificationRecordEntity>> = dao.observeConfirmed()
    override fun observeAllWithConfirmedSince(sinceMillis: Long): Flow<List<NotificationRecordEntity>> =
        dao.observeAllWithConfirmedSince(sinceMillis)
    override suspend fun findById(id: Long): NotificationRecordEntity? = dao.findById(id)
    override suspend fun updateStatus(id: Long, status: String, clientId: String?) =
        dao.updateStatus(id, status, clientId)
}
