package com.aibill.android.data.remote

import com.aibill.android.data.remote.api.TransactionApi
import com.aibill.android.data.remote.dto.request.CreateTransactionRequest
import com.aibill.android.data.remote.dto.response.ApiResponse
import com.aibill.android.data.remote.dto.response.CreateTransactionResponse
import com.aibill.android.data.remote.dto.response.TransactionDto
import io.mockk.coEvery
import io.mockk.mockk

/**
 * 测试替身：把请求里的每一条 `client_id` 原样回执成 `created`（= **服务端确认**）。
 *
 * 「记一笔」的成功判据写在两处实现里（`RecordIntakeImpl.confirmAndCache` 与
 * `TransactionRepositoryImpl.createTransactions`），两处都只认 `created[]` 里
 * 按 `client_id` 匹配到的那条。本替身就是这条判据的「服务端那一半」：
 * 给它什么 `client_id`，它就回认什么，因此入账路径上「本地写不写」的断言
 * 可以直接建立在「服务端确认了没有」上，而不是靠 mock 掉网络层。
 *
 * 想测失败，用 [unconfirmedApiResponse] / 让它抛异常覆盖掉本桩即可。
 */
fun confirmingTransactionApi(): TransactionApi = mockk<TransactionApi>().also { api ->
    coEvery { api.createTransactions(any()) } answers { confirmingApiResponse(firstArg()) }
}

/** `code==0` + `created[]` 按 `client_id` 回认（带 id）——成功判据的正例。 */
fun confirmingApiResponse(request: CreateTransactionRequest): ApiResponse<CreateTransactionResponse> =
    ApiResponse(
        code = 0,
        data = CreateTransactionResponse(
            created = request.items.map { item ->
                TransactionDto(
                    id = SERVER_TRANSACTION_ID,
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
                    tags = null,
                    source = item.source,
                    createdAt = null,
                    updatedAt = null,
                )
            },
            duplicates = emptyList(),
        ),
        message = "ok",
    )

/** `code==0` 但 `created[]` 里一条都没有——成功判据的反例（服务端没确认）。 */
fun unconfirmedApiResponse(): ApiResponse<CreateTransactionResponse> =
    ApiResponse(
        code = 0,
        data = CreateTransactionResponse(created = emptyList(), duplicates = emptyList()),
        message = "ok",
    )

/** 测试里回认给每一笔的服务端交易 id（撤销时删的就是它）。 */
const val SERVER_TRANSACTION_ID = 99
