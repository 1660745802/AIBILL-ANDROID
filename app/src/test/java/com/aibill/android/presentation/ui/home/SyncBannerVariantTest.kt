package com.aibill.android.presentation.ui.home

import com.aibill.android.domain.model.SyncQueueStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * `bannerVariantOf` 的四档映射。
 *
 * 这是**纯 JVM 普通单元测试**：档位判定是展示层的纯函数，不碰 Compose、不碰 Room、
 * 不需要 Robolectric。渲染出来的具体文案/按钮由 `PendingSyncBannerRenderTest`
 * 锁，SQL 分区由 `PendingTransactionDaoQueueStatusTest` 锁——本文件只锁
 * 「两个计数 → 档位」这一层的分支顺序。
 *
 * 为什么分支顺序值得单测：`Failed` 与 `Mixed` 的判据都是 `failed > 0`，
 * 若有人把 `isEmpty` 与 `failed == 0` 两支调换，`Failed(0,3)` 会落到 Pending 档，
 * 于是横幅显示「3 笔还没同步 · 联网后自动补上」——**谎报**（failed 不会自动补上）。
 */
class SyncBannerVariantTest {

    @Test
    fun `both partitions empty maps to Hidden`() {
        assertEquals(SyncBannerVariant.Hidden, bannerVariantOf(SyncQueueStatus(0, 0)))
    }

    @Test
    fun `pending only maps to Pending`() {
        assertEquals(SyncBannerVariant.Pending, bannerVariantOf(SyncQueueStatus(1, 0)))
        assertEquals(SyncBannerVariant.Pending, bannerVariantOf(SyncQueueStatus(7, 0)))
    }

    /** 变异防线：failed-only 绝不能落到 Pending 档（会被渲染成「会自动补上」）。 */
    @Test
    fun `failed only maps to Failed and never to Pending`() {
        assertEquals(SyncBannerVariant.Failed, bannerVariantOf(SyncQueueStatus(0, 3)))
        assertEquals(SyncBannerVariant.Failed, bannerVariantOf(SyncQueueStatus(0, 1)))
    }

    @Test
    fun `both partitions present maps to Mixed`() {
        assertEquals(SyncBannerVariant.Mixed, bannerVariantOf(SyncQueueStatus(2, 1)))
        assertEquals(SyncBannerVariant.Mixed, bannerVariantOf(SyncQueueStatus(1, 5)))
    }
}
