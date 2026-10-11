package com.aibill.android.data.repository

import com.aibill.android.data.remote.api.TransactionApi
import com.aibill.android.data.remote.confirmingApiResponse
import com.aibill.android.data.remote.dto.request.CreateTransactionRequest
import com.aibill.android.data.remote.dto.response.ApiResponse
import com.aibill.android.data.remote.dto.response.CreateTransactionResponse
import com.aibill.android.data.remote.dto.response.TransactionDto
import com.aibill.android.data.remote.unconfirmedApiResponse
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.model.Transaction
import com.aibill.android.domain.model.TransactionSource
import com.aibill.android.domain.model.TransactionType
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * 手工记账**唯一**的那条路：送服务端 → 核对 `created` 回执。
 *
 * ## 成功判据
 *
 * `code==0` **且** `created[]` 里按 `client_id` 找得到这一笔，才算 [Result.Success]。
 * 少任何一条都是 [Result.Error]，而这条路**根本不写本地**（本类没有任何 DAO 可注入），
 * 所以「报错」与「本地没留下东西」是同一件事的两面。
 *
 * `duplicates[]` 不算确认：服务端只说「这个 client_id 已经存在」，
 * 于是调用方按失败处理（宁可多问一次，也不把别人的回执当自己的成功）。
 *
 * ## 这里曾经有什么
 *
 * `createTransactionOffline`（网络错误 → 落 `pending_transactions` 等后台补传）已删除：
 * 本工程没有离线队列，手工记账失败就是失败，报错 + 保留表单由
 * `ManualRecordViewModelTest` 钉。
 */
class TransactionRepositoryCreateTest {

    private val transactionApi: TransactionApi = mockk()
    private val repository = TransactionRepositoryImpl(transactionApi)

    private fun tx(clientId: String = "C-1") = Transaction(
        clientId = clientId,
        type = TransactionType.EXPENSE,
        amount = 3200,
        description = "午饭",
        date = "2026-07-05",
        time = "12:30",
        source = TransactionSource.MANUAL,
    )

    @Test
    fun `created with a matching client id is the only success`() = runTest {
        val request = slot<CreateTransactionRequest>()
        coEvery { transactionApi.createTransactions(capture(request)) } answers {
            confirmingApiResponse(firstArg())
        }

        val result = repository.createTransactions(listOf(tx("C-A")))

        assertTrue(result is Result.Success, "服务端确认了才算记上")
        assertEquals(listOf("C-A"), request.captured.items.map { it.clientId })
        // 服务端要的字段必须带全：client_id 是幂等键，client_type 是平台标记
        assertEquals("app_android", request.captured.items.single().clientType)
        assertEquals("manual", request.captured.items.single().source)
    }

    @Test
    fun `code zero without a matching created entry is a failure`() = runTest {
        coEvery { transactionApi.createTransactions(any()) } returns unconfirmedApiResponse()

        val result = repository.createTransactions(listOf(tx("C-B")))

        assertTrue(result is Result.Error, "code==0 却没回执这一笔 = 没记上")
        assertEquals("服务端未确认这笔记录", (result as Result.Error).message)
    }

    @Test
    fun `duplicates do not count as confirmation`() = runTest {
        // 重试请求（RetryInterceptor 重发同一个 body）时服务端会把它放进 duplicates[]。
        // 按成功判据的字面口径，这条不算「返回 created」，因此如实报失败。
        val dto = TransactionDto(
            id = 9,
            clientId = "C-C",
            type = "expense",
            amount = 3200,
            categoryId = null,
            categoryName = null,
            categoryIcon = null,
            accountId = null,
            accountName = null,
            targetAccountId = null,
            targetAccountName = null,
            description = null,
            date = "2026-07-05",
            time = null,
            createdAt = null,
            updatedAt = null,
        )
        coEvery { transactionApi.createTransactions(any()) } returns ApiResponse(
            code = 0,
            data = CreateTransactionResponse(created = emptyList(), duplicates = listOf(dto)),
            message = "ok",
        )

        val result = repository.createTransactions(listOf(tx("C-C")))

        assertTrue(result is Result.Error)
    }

    @Test
    fun `business error code is surfaced as is`() = runTest {
        coEvery { transactionApi.createTransactions(any()) } returns
            ApiResponse(code = 422, data = null, message = "金额必须大于 0")

        val result = repository.createTransactions(listOf(tx()))

        assertTrue(result is Result.Error)
        assertEquals(422, (result as Result.Error).code)
        assertEquals("金额必须大于 0", result.message)
    }

    @Test
    fun `io failure becomes the network error code so the ui can say there is no network`() = runTest {
        coEvery { transactionApi.createTransactions(any()) } throws IOException("no route to host")

        val result = repository.createTransactions(listOf(tx()))

        assertTrue(result is Result.Error)
        assertEquals(Result.ERROR_NETWORK, (result as Result.Error).code)
    }
}
