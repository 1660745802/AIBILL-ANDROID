package com.aibill.android.data.repository

import com.aibill.android.data.remote.api.TransactionApi
import com.aibill.android.data.remote.dto.response.ApiResponse
import com.aibill.android.domain.model.Result
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/**
 * `DELETE /transactions/:id`（含 `RecordUndoImpl` 撤销链路）的错误映射。
 *
 * 收口前这里把所有异常一律映射成 `-1`（= [Result.ERROR_NETWORK]）：既把 401 / 5xx
 * 谎报成「没网」，又把协程取消当成一次「删除失败」返回，破坏结构化取消。
 * 本类钉住修好后的语义，与 `safeApiCall` 的错误码契约（架构 §三）一致。
 */
class TransactionRepositoryDeleteTest {

    private val transactionApi: TransactionApi = mockk()
    private val repository = TransactionRepositoryImpl(transactionApi)

    @Test
    fun `code zero deletes and reports success`() = runTest {
        coEvery { transactionApi.deleteTransaction(7) } returns
            ApiResponse(code = 0, data = null, message = "ok")

        val result = repository.deleteTransaction(7)

        assertTrue(result is Result.Success, "code==0 才算删掉")
    }

    @Test
    fun `business error code is surfaced as is`() = runTest {
        coEvery { transactionApi.deleteTransaction(7) } returns
            ApiResponse(code = 403, data = null, message = "无权删除")

        val result = repository.deleteTransaction(7)

        assertTrue(result is Result.Error)
        assertEquals(403, (result as Result.Error).code)
        assertEquals("无权删除", result.message)
    }

    @Test
    fun `io failure maps to the network error code`() = runTest {
        coEvery { transactionApi.deleteTransaction(7) } throws IOException("no route to host")

        val result = repository.deleteTransaction(7)

        assertTrue(result is Result.Error)
        assertEquals(Result.ERROR_NETWORK, (result as Result.Error).code)
    }

    @Test
    fun `http failure keeps its http code instead of being reported as a network error`() = runTest {
        coEvery { transactionApi.deleteTransaction(7) } throws
            HttpException(Response.error<Any>(500, "{}".toResponseBody(null)))

        val result = repository.deleteTransaction(7)

        assertTrue(result is Result.Error)
        // 历史实现一律返回 -1（网络错误），用户会被「没网」误导
        assertEquals(500, (result as Result.Error).code)
    }

    @Test
    fun `unexpected failure maps to the unknown error code`() = runTest {
        coEvery { transactionApi.deleteTransaction(7) } throws IllegalStateException("boom")

        val result = repository.deleteTransaction(7)

        assertTrue(result is Result.Error)
        assertEquals(Result.ERROR_UNKNOWN, (result as Result.Error).code)
    }

    /**
     * 取消不是删除失败：`RecordUndoImpl` 依赖这个语义，否则协程已被取消时仍会走完
     * 「返回 UndoOutcome + 播报文案」的后续步骤。
     */
    @Test
    fun `cancellation propagates instead of being reported as a delete failure`() = runTest {
        coEvery { transactionApi.deleteTransaction(7) } throws CancellationException("cancelled")

        var propagated: CancellationException? = null
        try {
            repository.deleteTransaction(7)
        } catch (e: CancellationException) {
            propagated = e
        }

        assertNotNull(propagated, "取消必须原样抛出，不能被吞成 Result.Error")
    }
}
