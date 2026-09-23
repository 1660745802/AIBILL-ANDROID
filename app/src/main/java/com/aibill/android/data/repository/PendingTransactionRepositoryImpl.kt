package com.aibill.android.data.repository

import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.data.local.entity.PendingTransactionEntity
import com.aibill.android.domain.repository.PendingTransactionRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PendingTransactionRepositoryImpl @Inject constructor(
    private val dao: PendingTransactionDao,
) : PendingTransactionRepository {
    override suspend fun insert(entity: PendingTransactionEntity) = dao.insert(entity)
    override suspend fun getPendingCount(): Int = dao.getPendingCount()
    override suspend fun deleteAll() = dao.deleteAll()
    override suspend fun findByClientId(clientId: String): PendingTransactionEntity? =
        dao.findByClientId(clientId)
}
