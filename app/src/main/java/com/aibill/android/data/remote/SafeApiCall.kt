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
 * PR #60 + M7：原代码 `Unit as T` 是 unchecked cast，运行期取 data 字段时可能
 * ClassCastException（虽然所有现有 callsite 都用 Result<Unit>/Result<Any>，
 * 但 foot-gun 性质）。
 *
 * M7 修复：直接重写为 reified 内联函数，通过 T::class 在编译期决定 null-data 行为：
 * - T == Unit → 允许 null data（DELETE 类接口）
 * - T == Any  → 允许 null data（同上）
 * - 其他类型 → data==null 视为业务错误（code=-3 "API 返回数据为空"）
 *
 * 用法：`safeApiCall<TransactionDto> { api.getTransaction(id) }`
 */
suspend inline fun <reified T> safeApiCall(
    crossinline apiCall: suspend () -> ApiResponse<T>,
): Result<T> {
    return try {
        val response = apiCall()
        when (response.code) {
            0 -> {
                val data = response.data
                when {
                    data != null -> Result.Success(data)
                    // DELETE 类接口（ApiResponse<Any>/ApiResponse<Unit>）允许 data=null
                    T::class == Unit::class || T::class == Any::class ->
                        @Suppress("UNCHECKED_CAST")
                        Result.Success(Unit as T)
                    // 其他类型 data==null 视为业务错误
                    else -> Result.Error(-3, "API 返回数据为空")
                }
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