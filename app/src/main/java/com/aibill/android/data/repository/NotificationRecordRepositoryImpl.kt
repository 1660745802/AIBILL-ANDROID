package com.aibill.android.data.repository

import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.domain.repository.NotificationRecordRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationRecordRepositoryImpl @Inject constructor(
    private val dao: NotificationRecordDao,
) : NotificationRecordRepository {
    override fun observePendingCount(): Flow<Int> = dao.observePendingCount()
}
