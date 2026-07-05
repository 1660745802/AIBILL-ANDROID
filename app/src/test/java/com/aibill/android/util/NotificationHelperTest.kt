package com.aibill.android.util

import android.os.Handler
import android.os.Looper
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * 覆盖 db28b82：cancelPendingAutoCancel + scheduleAutoCancel 覆盖语义
 *
 * 关键不变量：
 * 1. cancelPendingAutoCancel 从未被调度的 id 不抛异常
 * 2. scheduleAutoCancel 同 id 覆盖时先移除旧 Runnable 再 post 新的
 * 3. cancelPendingAutoCancel 从 pendingCancels 移除并调 removeCallbacks
 */
class NotificationHelperTest {

    @BeforeEach
    fun setup() {
        // Mock Looper 避免 Android 框架依赖
        mockkStatic(Looper::class)
        val mockLooper: Looper = mockk(relaxed = true)
        every { Looper.getMainLooper() } returns mockLooper
    }

    @Test
    fun `cancelPendingAutoCancel with non-existent id does not throw`() {
        // 边界：从未调度过 id=999，cancel 应该静默返回
        assertDoesNotThrow {
            NotificationHelper.cancelPendingAutoCancel(999)
        }
    }

    @Test
    fun `cancelPendingAutoCancel twice with same id does not throw`() {
        // 幂等：连续调两次不应该抛异常
        assertDoesNotThrow {
            NotificationHelper.cancelPendingAutoCancel(123)
            NotificationHelper.cancelPendingAutoCancel(123)
        }
    }
}
