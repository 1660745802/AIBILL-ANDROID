package com.aibill.android.data.repository

import com.aibill.android.data.remote.api.TransactionApi
import com.aibill.android.data.remote.confirmingApiResponse
import com.aibill.android.data.remote.dto.request.CreateTransactionRequest
import com.aibill.android.data.remote.dto.request.TransactionItemRequest
import com.aibill.android.data.remote.dto.response.ApiResponse
import com.aibill.android.data.remote.dto.response.CreateTransactionResponse
import com.aibill.android.data.remote.dto.response.TransactionDto
import com.aibill.android.data.remote.duplicatesApiResponse
import com.aibill.android.data.remote.unconfirmedApiResponse
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.model.Transaction
import com.aibill.android.domain.model.TransactionSource
import com.aibill.android.domain.model.TransactionType
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
 * `duplicates[]` **也算确认**：服务端说「这个 client_id 已经存在」，也就是这笔账确实
 * 在服务端。`RetryInterceptor` 对 IOException 会重发同一个 body，首次请求其实已落库、
 * 只是响应丢了，重发拿到的正是 `duplicates[]`——判成失败就会「服务端已有这笔，
 * 却告诉用户没记上」，用户重记就是重复账。只有两个列表都没匹配到自己才算失败。
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
    fun `duplicates count as confirmation`() = runTest {
        // 重试请求（RetryInterceptor 重发同一个 body）时服务端会把它放进 duplicates[]：
        // 这笔账确实在服务端，判失败会诱使用户重记 = 重复账。
        coEvery { transactionApi.createTransactions(any()) } answers {
            duplicatesApiResponse(firstArg())
        }

        val result = repository.createTransactions(listOf(tx("C-C")))

        assertTrue(result is Result.Success, "服务端已有这笔 = 记上了")
        assertEquals(listOf("C-C"), (result as Result.Success).data.map { it.clientId })
    }

    @Test
    fun `one created and one duplicate in the same batch are both confirmations`() = runTest {
        coEvery { transactionApi.createTransactions(any()) } answers {
            val request = firstArg<CreateTransactionRequest>()
            ApiResponse(
                code = 0,
                data = CreateTransactionResponse(
                    created = listOf(dtoFor(request.items[0])),
                    duplicates = listOf(dtoFor(request.items[1])),
                ),
                message = "ok",
            )
        }

        val result = repository.createTransactions(listOf(tx("A"), tx("B")))

        assertTrue(result is Result.Success, "逐条判定，不能被整批的「有 created」遮住")
        assertEquals(listOf("A", "B"), (result as Result.Success).data.map { it.clientId })
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

    private fun dtoFor(item: TransactionItemRequest) = TransactionDto(
        id = 7,
        clientId = item.clientId,
        type = item.type,
        amount = item.amount,
        categoryId = item.categoryId,
        categoryName = null,
        categoryIcon = null,
        accountId = item.accountId,
        accountName = null,
        targetAccountId = item.targetAccountId,
        targetAccountName = null,
        description = item.description,
        date = item.date,
        time = item.time,
        createdAt = null,
        updatedAt = null,
    )

    @Test
    fun `io failure becomes the network error code so the ui can say there is no network`() = runTest {
        coEvery { transactionApi.createTransactions(any()) } throws IOException("no route to host")

        val result = repository.createTransactions(listOf(tx()))

        assertTrue(result is Result.Error)
        assertEquals(Result.ERROR_NETWORK, (result as Result.Error).code)
    }

    /**
     * 取消不是记账失败：`safeApiCall` 用 `catch (Exception)` 兜底，会把取消也吞成
     * `Result.Error`，而本层契约是「禁止抛出异常，CancellationException 除外」
     * （`domain/model/Result.kt`）。没有 `ensureActive()` 时，已取消的调用会返回一个
     * 假的失败结果，调用方照样报「保存失败」并清掉 isSaving。
     */
    @Test
    fun `cancellation propagates instead of being swallowed as a create failure`() = runTest {
        val started = CompletableDeferred<Unit>()
        coEvery { transactionApi.createTransactions(any()) } coAnswers {
            started.complete(Unit)
            delay(10_000)
            unconfirmedApiResponse()
        }

        var result: Result<List<Transaction>>? = null
        val job = launch { result = repository.createTransactions(listOf(tx())) }

        started.await()
        job.cancel()
        job.join()

        assertNull(result, "取消后不得产出 Result.Error")
    }
}
