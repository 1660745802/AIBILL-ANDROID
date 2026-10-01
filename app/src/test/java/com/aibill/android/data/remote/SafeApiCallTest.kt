package com.aibill.android.data.remote

import com.aibill.android.data.remote.dto.response.ApiResponse
import com.aibill.android.domain.model.Result
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/**
 * 覆盖 PR M7：safeApiCall reified 内联函数的 8 条核心行为
 *
 * 关键不变量：
 * 1. code==0 + data!=null → Result.Success(data)
 * 2. code==0 + data=null + T=Unit → Result.Success(Unit)（DELETE 类接口）
 * 3. code==0 + data=null + T=Any → Result.Success(Unit)
 * 4. code==0 + data=null + T!=Unit/Any → Result.Error(-3, "API 返回数据为空")
 * 5. code!=0 → Result.Error(code, message)
 * 6. IOException → Result.Error(ERROR_NETWORK, "网络连接失败...")
 * 7. HttpException → Result.Error(httpCode, "服务器错误: httpCode")
 * 8. 其他 Exception → Result.Error(ERROR_UNKNOWN, "未知错误: msg")
 */
class SafeApiCallTest {

    @Test
    fun `code 0, data non-null, T String - returns Success with data`() = runTest {
        val result = safeApiCall<String> {
            ApiResponse(code = 0, data = "hello", message = "ok")
        }
        assertTrue(result is Result.Success)
        assertEquals("hello", (result as Result.Success).data)
    }

    @Test
    fun `code 0, data null, T is Unit - returns Success(Unit) (DELETE class API)`() = runTest {
        val result = safeApiCall<Unit> {
            ApiResponse<Unit>(code = 0, data = null, message = "ok")
        }
        assertTrue(result is Result.Success)
    }

    @Test
    fun `code 0, data null, T is Any - returns Success(Unit) (generic DELETE API)`() = runTest {
        val result = safeApiCall<Any> {
            ApiResponse<Any>(code = 0, data = null, message = "ok")
        }
        assertTrue(result is Result.Success)
    }

    @Test
    fun `code 0, data null, T is non-Unit (String) - returns Error code=-3 empty data`() = runTest {
        // PR M7 修复：之前 `Unit as T` 对 T=String 会 ClassCastException
        // 现在返回 Result.Error(-3, "API 返回数据为空")
        val result = safeApiCall<String> {
            ApiResponse<String>(code = 0, data = null, message = "ok")
        }
        assertTrue(result is Result.Error)
        assertEquals(-3, (result as Result.Error).code)
        assertEquals("API 返回数据为空", (result as Result.Error).message)
    }

    @Test
    fun `code 5001 AI 失败 - returns Error with business code and message preserved`() = runTest {
        val result = safeApiCall<String> {
            ApiResponse(code = 5001, data = null, message = "AI 解析失败")
        }
        assertTrue(result is Result.Error)
        val err = result as Result.Error
        assertEquals(5001, err.code)
        assertEquals("AI 解析失败", err.message)
    }

    @Test
    fun `code 401 - returns Error with 401 (interceptor handles emit separately)`() = runTest {
        val result = safeApiCall<String> {
            ApiResponse(code = 401, data = null, message = "Unauthorized")
        }
        assertTrue(result is Result.Error)
        val err = result as Result.Error
        assertEquals(401, err.code)
        assertEquals("Unauthorized", err.message)
    }

    @Test
    fun `IOException - returns Result_ERROR_NETWORK`() = runTest {
        val result = safeApiCall<String> { throw IOException("connection refused") }
        assertTrue(result is Result.Error)
        val err = result as Result.Error
        assertEquals(Result.ERROR_NETWORK, err.code)
        assertTrue(err.message.contains("网络连接失败"))
    }

    @Test
    fun `HttpException - returns Error with http code (500 502 503 all covered)`() = runTest {
        listOf(500, 502, 503).forEach { code ->
            val httpEx = HttpException(
                Response.error<Any>(code, "server error".toResponseBody("text/plain".toMediaType()))
            )
            val result = safeApiCall<String> { throw httpEx }
            assertTrue(result is Result.Error, "HttpException $code should be Error")
            val err = result as Result.Error
            assertEquals(code, err.code)
            assertTrue(err.message.contains("服务器错误"))
            assertTrue(err.message.contains(code.toString()))
        }
    }

    @Test
    fun `非 IOException or HttpException - returns Result_ERROR_UNKNOWN with message`() = runTest {
        // 任意 RuntimeException 子类（覆盖 NPE/ClassCast/IllegalState 等）都归类到 UNKNOWN
        listOf(
            RuntimeException("unexpected oops"),
            NullPointerException("NPE"),
            IllegalStateException("bad state"),
        ).forEach { ex ->
            val result = safeApiCall<String> { throw ex }
            assertTrue(result is Result.Error, "${ex::class.simpleName} should be Error")
            val err = result as Result.Error
            assertEquals(Result.ERROR_UNKNOWN, err.code)
            assertTrue(err.message.contains("未知错误"), "${ex.message} message format")
            assertTrue(err.message.contains(ex.message!!), "${ex.message} preserves original")
        }
    }
}
