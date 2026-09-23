package com.aibill.android.data.repository

import android.content.Context
import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.data.remote.api.TransactionApi
import com.aibill.android.service.SyncScheduler
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * 覆盖 [TransactionRepositoryImpl.syncPending]：
 * - PR 修复：原空 stub 已改为触发 SyncScheduler.scheduleSyncIfNeeded
 * - 关键不变量：
 *   1. syncPending 成功 → 必调 SyncScheduler.scheduleSyncIfNeeded(context)
 *   2. syncPending 抛异常 → 返回 Result.Error(-2, ...)
 */
class TransactionRepositorySyncPendingTest {

    private val context: Context = mockk(relaxed = true)
    private val transactionApi: TransactionApi = mockk()
    private val pendingTransactionDao: PendingTransactionDao = mockk(relaxed = true)

    @BeforeEach
    fun setUp() {
        mockkObject(SyncScheduler)
        every { SyncScheduler.scheduleSyncIfNeeded(any()) } returns Unit
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(SyncScheduler)
    }

    @Test
    fun `syncPending triggers SyncScheduler`() = runTest {
        val repo = TransactionRepositoryImpl(
            transactionApi = transactionApi,
            pendingTransactionDao = pendingTransactionDao,
            context = context,
        )

        val result = repo.syncPending()

        assertTrue(result is com.aibill.android.domain.model.Result.Success)
        coVerify(exactly = 1) { SyncScheduler.scheduleSyncIfNeeded(context) }
    }

    @Test
    fun `syncPending returns Error when SyncScheduler throws`() = runTest {
        every { SyncScheduler.scheduleSyncIfNeeded(any()) } throws RuntimeException("WM error")

        val repo = TransactionRepositoryImpl(
            transactionApi = transactionApi,
            pendingTransactionDao = pendingTransactionDao,
            context = context,
        )

        val result = repo.syncPending()

        assertTrue(result is com.aibill.android.domain.model.Result.Error)
        assertTrue((result as com.aibill.android.domain.model.Result.Error).code == -2)
    }
}
