package com.aibill.android.util

import android.os.Handler
import android.os.Looper
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * 覆盖 db28b82：cancelPendingAutoCancel 核心行为
 *
 * 关键不变量：
 * 1. cancelPendingAutoCancel 从未被调度的 id 不抛异常（幂等安全）
 * 2. cancelPendingAutoCancel 从 pendingCancels map 中移除该 id
 * 3. 连续两次调同一 id 幂等不抛异常
 * 4. cancel 某 id 不影响其他 id
 *
 * 测试策略：
 * - 通过反射访问 pendingCancels map 进行前置/断言
 * - Handler.removeCallbacks 的调用通过代码结构保证（map.remove(id)?.let { handler.removeCallbacks(it) }）
 *   只要 map 正确移除了 Runnable，removeCallbacks 必然被调用（?.let 语义保证）
 */
class NotificationHelperTest {

    private lateinit var mapField: java.lang.reflect.Field

    @Suppress("UNCHECKED_CAST")
    private val pendingCancels: ConcurrentHashMap<Int, Runnable>
        get() = mapField.get(NotificationHelper) as ConcurrentHashMap<Int, Runnable>

    @BeforeEach
    fun setup() {
        // Mock Looper 避免 Android 框架依赖
        mockkStatic(Looper::class)
        every { Looper.getMainLooper() } returns mockk(relaxed = true)

        // 反射获取 pendingCancels map
        mapField = NotificationHelper::class.java.getDeclaredField("pendingCancels")
        mapField.isAccessible = true

        // 每个测试开始前清空 map
        pendingCancels.clear()
    }

    @AfterEach
    fun tearDown() {
        pendingCancels.clear()
        unmockkStatic(Looper::class)
    }

    @Test
    fun `cancelPendingAutoCancel with non-existent id does not throw`() {
        // 边界：从未调度过 id=999，cancel 应该静默返回
        assertDoesNotThrow {
            NotificationHelper.cancelPendingAutoCancel(999)
        }
        // map 仍为空
        assertTrue(pendingCancels.isEmpty())
    }

    @Test
    fun `cancelPendingAutoCancel removes Runnable from map`() {
        // 前置：往 map 里放一个 Runnable 模拟已调度状态
        val testRunnable = Runnable {}
        pendingCancels[42] = testRunnable
        assertEquals(1, pendingCancels.size)

        // 调 cancelPendingAutoCancel 应该移除
        NotificationHelper.cancelPendingAutoCancel(42)

        // 核心断言：map 中已无 id=42
        assertNull(pendingCancels[42])
        assertTrue(pendingCancels.isEmpty())
    }

    @Test
    fun `cancelPendingAutoCancel twice with same id is idempotent`() {
        val testRunnable = Runnable {}
        pendingCancels[123] = testRunnable

        // 第一次移除
        NotificationHelper.cancelPendingAutoCancel(123)
        assertNull(pendingCancels[123])

        // 第二次：map 中已不存在，应静默不抛
        assertDoesNotThrow {
            NotificationHelper.cancelPendingAutoCancel(123)
        }
    }

    @Test
    fun `cancelPendingAutoCancel does not affect other ids`() {
        val runnable1 = Runnable {}
        val runnable2 = Runnable {}
        pendingCancels[10] = runnable1
        pendingCancels[20] = runnable2
        assertEquals(2, pendingCancels.size)

        NotificationHelper.cancelPendingAutoCancel(10)

        // id=10 被移除
        assertNull(pendingCancels[10])
        // id=20 不受影响
        assertNotNull(pendingCancels[20])
        assertEquals(runnable2, pendingCancels[20])
        assertEquals(1, pendingCancels.size)
    }
}
