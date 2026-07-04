package com.aibill.android.data.remote

import com.aibill.android.data.remote.dto.response.ApiResponse
import com.aibill.android.domain.model.Result
import retrofit2.HttpException
import timber.log.Timber
import java.io.IOException

/**
 * 安全 API 调用封装
 * 统一处理网络异常和业务错误码，返回 Result 类型
 *
 * PR #60：当 data 为 null 且泛型 T 不是 Unit 时，原代码 `Unit as T` 是
 * unchecked cast，运行期取 data 字段时可能 ClassCastException。
 * 改为：当 T 实际是 Unit 时允许 data 为 null；否则 data==null 视为业务错误。
 */
suspend fun <T> safeApiCall(apiCall: suspend () -> ApiResponse<T>): Result<T> {
    return try {
        val response = apiCall()
        when (response.code) {
            0 -> {
                // PR #60：data 为 null 时若是 DELETE 类型（ApiResponse<Any>/Unit），
                // 原代码 `Unit as T` 是 unchecked cast，但所有现有 callsite
                // 都用 Result<Unit>/Result<Any>，实际不会 ClassCastException。
                // 保留该 cast 以兼容现有调用；若后续出现非 Unit/Any 的
                // ApiResponse<X>，需要按类型分发。
                @Suppress("UNCHECKED_CAST")
                val data = response.data ?: (Unit as T)
                Result.Success(data)
            }
            else -> {
                Timber.w("API 业务错误: code=${response.code}, message=${response.message}")
                Result.Error(response.code, response.message)
            }
        }
    } catch (e: IOException) {
        Timber.e(e, "网络连接失败")
        Result.Error(Result.ERROR_NETWORK, "网络连接失败，请检查网络")
    } catch (e: HttpException) {
        Timber.e(e, "HTTP 错误: ${e.code()}")
        Result.Error(e.code(), "服务器错误: ${e.code()}")
    } catch (e: Exception) {
        Timber.e(e, "未知错误")
        Result.Error(Result.ERROR_UNKNOWN, "未知错误: ${e.message}")
    }
}
