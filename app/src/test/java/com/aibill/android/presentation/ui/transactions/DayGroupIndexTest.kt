package com.aibill.android.presentation.ui.transactions

import com.aibill.android.domain.model.Transaction
import com.aibill.android.presentation.ui.transactions.components.buildDayGroups
import com.aibill.android.presentation.ui.transactions.components.itemKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 日期分组的下标不变量测试。
 *
 * 这组用例锁的是流水列表「加载会前进」这条链路的下半截：
 * 列表渲染时要按 `indexMap[group.startIndex + offset]` 把视口位置报回 Paging，
 * 否则 Paging 不知道用户看到第几项，`LoadParams.Append` 永远不触发，
 * 列表会被钉死在第一批（表现成「默认勾选全部，但翻到底只到当月」）。
 *
 * 因此 `DayGroup.startIndex` 必须严格等于该组首元素在扁平序列中的位置，
 * 且各组首尾相接、覆盖完整。任何改动导致下标错位，access hint 就会报到
 * 错误位置，静默退化成不加载更多。
 */
class DayGroupIndexTest {

    private fun tx(id: Int, date: String) = Transaction(
        clientId = "c$id",
        id = id,
        type = com.aibill.android.domain.model.TransactionType.EXPENSE,
        amount = 100,
        categoryId = null,
        categoryName = null,
        categoryIcon = null,
        accountId = null,
        targetAccountId = null,
        description = null,
        date = date,
        time = null,
    )

    @Test
    @DisplayName("每组 startIndex 等于该组首元素在扁平序列中的位置")
    fun startIndexMatchesFlatPosition() {
        val list = listOf(
            tx(1, "2026-10-05"), tx(2, "2026-10-05"), tx(3, "2026-10-05"),
            tx(4, "2026-10-04"),
            tx(5, "2026-09-28"), tx(6, "2026-09-28"),
            tx(7, "2026-07-03"),
        )
        val groups = buildDayGroups(list)

        assertEquals(listOf("2026-10-05", "2026-10-04", "2026-09-28", "2026-07-03"), groups.map { it.date })
        assertEquals(listOf(0, 3, 4, 6), groups.map { it.startIndex })
    }

    @Test
    @DisplayName("各组首尾相接，无重叠无空洞 —— startIndex + size 必须等于下一组 startIndex")
    fun groupsAreContiguousAndCoverEverything() {
        val list = (1..17).map { tx(it, "2026-10-${(it % 3) + 10}") }
        val groups = buildDayGroups(list)

        groups.forEachIndexed { i, g ->
            // 拼回扁平序列必须与输入完全一致
            assertEquals(list.subList(g.startIndex, g.startIndex + g.items.size), g.items)
            if (i + 1 < groups.size) {
                assertEquals(
                    g.startIndex + g.items.size,
                    groups[i + 1].startIndex,
                    "第 $i 组与第 ${i + 1} 组之间存在空洞或重叠",
                )
            }
        }
        assertEquals(list.size, groups.sumOf { it.items.size }, "分组丢失了条目")
    }

    @Test
    @DisplayName("跨页同日合并后，后续组的 startIndex 自动平移 —— access hint 不会报错位置")
    fun startIndexStaysCorrectWhenDateSpansPageBoundary() {
        // 真实场景：page2 末尾和 page3 开头是同一天（生产数据里 2026-09-28 就是），
        // 翻页后两天会合并成一组，后续所有组的位置都要跟着平移。
        val page1 = listOf(tx(1, "2026-10-09"), tx(2, "2026-10-05"))
        val page2 = listOf(tx(3, "2026-09-28"), tx(4, "2026-09-28"))
        val page3 = listOf(tx(5, "2026-09-28"), tx(6, "2026-07-03"))

        val beforeMore = buildDayGroups(page1 + page2)
        val afterMore = buildDayGroups(page1 + page2 + page3)

        // 加载更多前：2026-09-28 只有 2 条
        assertEquals(2, beforeMore.first { it.date == "2026-09-28" }.items.size)
        // 加载更多后：同日合并成 3 条，且 2026-07-03 的 startIndex 必须是 5
        val merged = afterMore.first { it.date == "2026-09-28" }
        assertEquals(3, merged.items.size)
        assertEquals(2, merged.startIndex)
        assertEquals(5, afterMore.first { it.date == "2026-07-03" }.startIndex)
    }

    @Test
    @DisplayName("itemKey 对 null id（离线未同步）也唯一，不产生 LazyColumn 重复 key")
    fun itemKeysAreUniqueEvenWhenIdIsNull() {
        val offline = tx(1, "2026-10-05").copy(id = null)
        val anotherOffline = tx(2, "2026-10-05").copy(id = null)
        val keys = listOf(offline, anotherOffline).map { itemKey(it) }
        assertTrue(keys.toSet().size == keys.size, "离线记录的 key 重复会导致 LazyColumn 崩溃")
    }
}
