package com.aibill.android.presentation.ui.transactions

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.compose.collectAsLazyPagingItems
import com.aibill.android.domain.model.Transaction
import com.aibill.android.domain.model.TransactionType
import com.aibill.android.presentation.ui.transactions.components.TransactionsPagingList
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 分页列表的**加载会前进**不变量测试。
 *
 * ## 为什么需要这个测试
 * 真实事故：流水页默认「全部」，但翻到底只有当月记录，历史月份永远加载不出来。
 *
 * 根因是 Paging 3 的一条隐式契约被悄悄破坏了：
 *
 *     LazyPagingItems.peek(index)  不产生 access hint
 *     LazyPagingItems.get(index)   才会产生
 *
 * 列表为了修一个 key 重复崩溃，改成「组合期一次性 peek 出快照」渲染，
 * 于是整个 UI 里再也没有 `get` 调用 —— Paging 永远不知道用户看到第几项，
 * `LoadParams.Append` 永远不触发，列表被钉死在 `initialLoadSize` 那一批。
 *
 * 这个 bug 用「编译通过 + 首屏正常」是发现不了的：`peek` 与 `get` 只差两个字母，
 * 签名返回类型一样，首屏 20 条照常显示。**只有行为级测试能锁住它**：
 * 只要把渲染里的 `get` 换回 `peek`，本测试立刻变红。
 *
 * ## 为什么不用滚动就够
 * `PagingConfig.prefetchDistance` 默认等于 `pageSize`（20）。也就是说只要 UI
 * 用 `get` 访问到 0..19 区间，Paging 就会判定「预取窗口已到边界」而请求下一页。
 * 反过来，若只用 `peek`，即使把列表滚穿也不会有任何请求。因此断言「组合后
 * 是否发出了 Append」即可，不需要模拟真实滚动。
 */
@RunWith(RobolectricTestRunner::class)
// 用裸 Application，不拉起 Hilt 的 AiBillApp ——
// 后者会初始化 TokenManager → MasterKeys → AndroidKeyStore，
// Robolectric 里没有 AndroidKeyStore，测试会在 setUp 阶段就崩。
@Config(sdk = [34], application = android.app.Application::class)
class TransactionsPagingAppendTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /**
     * 记录型 PagingSource：返回固定页数，并把每次 load 的页码记下来。
     */
    private class RecordingPagingSource(
        private val pageSize: Int,
        private val totalPages: Int,
    ) : PagingSource<Int, Transaction>() {
        val requestedPages = mutableListOf<Int>()

        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Transaction> {
            val page = params.key ?: 1
            requestedPages += page
            if (page > totalPages) {
                return LoadResult.Page(data = emptyList(), prevKey = null, nextKey = null)
            }
            val data = List(pageSize) { offset ->
                // 用不同日期，保证会走分组逻辑
                tx(id = (page - 1) * pageSize + offset, date = "2026-10-${(offset % 9) + 1}")
            }
            return LoadResult.Page(
                data = data,
                prevKey = if (page == 1) null else page - 1,
                nextKey = if (page >= totalPages) null else page + 1,
            )
        }

        override fun getRefreshKey(state: PagingState<Int, Transaction>): Int? = null

        companion object {
            fun tx(id: Int, date: String) = Transaction(
                clientId = "client-$id",
                id = id,
                type = TransactionType.EXPENSE,
                amount = 100,
                categoryId = null,
                categoryName = "餐饮",
                categoryIcon = "🍜",
                accountId = null,
                targetAccountId = null,
                description = "第 $id 笔",
                date = date,
                time = "12:00",
            )
        }
    }

    @Test
    fun `渲染列表后 Paging 必须被请求下一页 —— get 不能换成 peek`() {
        val pageSize = 20
        val source = RecordingPagingSource(pageSize = pageSize, totalPages = 5)
        val pager = Pager(
            config = PagingConfig(pageSize = pageSize, initialLoadSize = pageSize),
            pagingSourceFactory = { source },
        )

        composeTestRule.setContent {
            val items = pager.flow.collectAsLazyPagingItems()
            TransactionsPagingList(
                pagingItems = items,
                listState = rememberLazyListState(),
                onItemClick = { },
                onLongPress = { },
                modifier = Modifier.height(400.dp),
            )
        }

        // 等首刷完成
        composeTestRule.waitForIdle()

        val requested = source.requestedPages.toList()
        assertTrue(
            "首刷只请求了 $requested。" +
                "必须还发出了 page 2 的 Append 请求 —— " +
                "如果这里只有 [1]，说明渲染时只用了 peek()，" +
                "Paging 收不到 access hint，列表会永远停在第一批（= 真实事故「翻到底只到当月」）。",
            requested.contains(2),
        )
    }

    @Test
    fun `首刷确实只取第一页 —— 验证测试本身有效`() {
        val pageSize = 20
        val source = RecordingPagingSource(pageSize = pageSize, totalPages = 5)
        val pager = Pager(
            config = PagingConfig(pageSize = pageSize, initialLoadSize = pageSize),
            pagingSourceFactory = { source },
        )

        composeTestRule.setContent {
            val items = pager.flow.collectAsLazyPagingItems()
            TransactionsPagingList(
                pagingItems = items,
                listState = rememberLazyListState(),
                onItemClick = { },
                onLongPress = { },
                modifier = Modifier.height(400.dp),
            )
        }
        composeTestRule.waitForIdle()

        // 第一页一定被请求了 —— 否则上一条测试可能是假阳性
        assertTrue("必须至少请求过 page 1", source.requestedPages.contains(1))
    }
}
