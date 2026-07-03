package com.aibill.android.domain.repository

import com.aibill.android.domain.model.Result
import com.aibill.android.domain.model.Transaction
import kotlinx.coroutines.flow.Flow

interface TransactionRepository {

    suspend fun createTransactions(items: List<Transaction>): Result<List<Transaction>>

    suspend fun createTransactionOffline(transaction: Transaction)

    suspend fun getTransactions(
        page: Int = 1,
        pageSize: Int = 20,
        startDate: String? = null,
        endDate: String? = null,
        type: String? = null,
        categoryId: Int? = null,
        keyword: String? = null,
    ): Result<List<Transaction>>

    suspend fun deleteTransaction(id: Int): Result<Unit>

    fun observePendingCount(): Flow<Int>

    suspend fun syncPending(): Result<Unit>
}
