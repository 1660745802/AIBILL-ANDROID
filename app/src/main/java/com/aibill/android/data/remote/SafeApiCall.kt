package com.aibill.android.data.remote

import com.aibill.android.data.remote.dto.response.ApiResponse
import com.aibill.android.domain.model.Result
import retrofit2.HttpException
import timber.log.Timber
import java.io.IOException

/**
 * 安全 API 调用封装
 * 统一处理网络异常和业务错误码，返回 Result 类型
 */
suspend fun <T> safeApiCall(apiCall: suspend () -> ApiResponse<T>): Result<T> {
    return try {
        val response = apiCall()
        when (response.code) {
            0 -> {
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
        Result.Error(ERROR_NETWORK, "网络连接失败，请检查网络")
    } catch (e: HttpException) {
        Timber.e(e, "HTTP 错误: ${e.code()}")
        Result.Error(e.code(), "服务器错误: ${e.code()}")
    } catch (e: Exception) {
        Timber.e(e, "未知错误")
        Result.Error(ERROR_UNKNOWN, "未知错误: ${e.message}")
    }
}

private const val ERROR_NETWORK = -1
private const val ERROR_UNKNOWN = -2
