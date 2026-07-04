package com.aibill.android.domain.model

/**
 * 统一结果封装
 * 所有 Repository 方法返回此类型，禁止抛出异常（除 CancellationException）
 */
sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val code: Int, val message: String) : Result<Nothing>()
    data object Loading : Result<Nothing>()

    val isSuccess: Boolean get() = this is Success
    val isError: Boolean get() = this is Error

    fun getOrNull(): T? = (this as? Success)?.data

    fun <R> map(transform: (T) -> R): Result<R> = when (this) {
        is Success -> Success(transform(data))
        is Error -> this
        is Loading -> Loading
    }

    companion object {
        /** 网络异常（IOException 等本地错误）的统一 code */
        const val ERROR_NETWORK = -1
        /** 未知错误 */
        const val ERROR_UNKNOWN = -2
    }
}
