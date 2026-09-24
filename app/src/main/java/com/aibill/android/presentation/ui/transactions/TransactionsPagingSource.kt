package com.aibill.android.presentation.ui.transactions

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.model.Transaction
import com.aibill.android.domain.repository.TransactionQuery
import com.aibill.android.domain.repository.TransactionRepository
import timber.log.Timber

/**
 * 流水分页数据源（PR C2：Paging 3 落地）
 *
 * 替代原 `TransactionsViewModel.currentPage++ + loadTransactions(refresh=false)` 的
 * 手动分页模式。LazyColumn 通过 `collectAsLazyPagingItems()` 自动触发 load()，
 * 无需在 `shouldLoadMore` 中手动判断。
 *
 * filter 维度（type/categoryId/keyword/tag/startDate/endDate）作为构造参数，
 * 任意维度变化即 `invalidate()`，Paging 重新从第 1 页开始加载。
 *
 * 边界处理：
 * - 服务端 `total` 为 0 → 立即返回空列表（不再发请求）
 * - API 失败 → throw LoadResult.Error，由 Paging 暴露 loadState
 */
/**
 * PagingSource 的 filter 快照。集中参数避免 LongParameterList。
 */
data class PagingFilterSnapshot(
    val startDate: String? = null,
    val endDate: String? = null,
    val type: String? = null,
    val categoryId: Int? = null,
    val tag: String? = null,
)

class TransactionsPagingSource(
    private val repository: TransactionRepository,
    private val filter: PagingFilterSnapshot,
    private val pageSize: Int = 20,
) : PagingSource<Int, Transaction>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Transaction> {
        val page = params.key ?: 1
        return when (val result = repository.getTransactions(
            TransactionQuery(
                page = page,
                pageSize = pageSize,
                startDate = filter.startDate,
                endDate = filter.endDate,
                type = filter.type,  // ViewModel 已转 null（非 "all"）
                categoryId = filter.categoryId,
                tag = filter.tag,
            ),
        )) {
            is Result.Success -> {
                val pageData = result.data
                val items = pageData.items
                val total = pageData.total
                val endOfPaginationReached = (page * pageSize) >= total || items.isEmpty()
                LoadResult.Page(
                    data = items,
                    prevKey = if (page == 1) null else page - 1,
                    nextKey = if (endOfPaginationReached) null else page + 1,
                )
            }
            is Result.Error -> {
                Timber.w("TransactionsPagingSource: load error page=$page msg=${result.message}")
                LoadResult.Error(IllegalStateException(result.message))
            }
            is Result.Loading -> LoadResult.Error(
                IllegalStateException("Unexpected Loading state in PagingSource.load")
            )
        }
    }

    override fun getRefreshKey(state: PagingState<Int, Transaction>): Int? {
        // 当数据 invalidate 后，从最接近用户当前滚动位置的页开始加载
        val anchorPosition = state.anchorPosition ?: return null
        val closestPage = state.closestPageToPosition(anchorPosition) ?: return null
        return closestPage.prevKey?.plus(1) ?: closestPage.nextKey?.minus(1)
    }
}
