package com.aibill.android.service

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * [A11yServiceInfoGate] ——「绝不在事件分发中改 `setServiceInfo`」这条不变量。
 *
 * 这条不变量对应线上事故：无障碍服务反复失效、提示用户手动关闭后重开。
 * 成因是 `refreshRulesIfNeeded()` 在 `onAccessibilityEvent` 的第一件事里就下发
 * `serviceInfo.packageNames`，而连接态下改它会被系统走重装路径解绑服务，
 * 而 Android **没有 a11y 的 rebind API** → 只能手动 toggle。
 *
 * 回归护栏拆成四条：分发中拦住、待下发不丢、集合相同不重复下发、未连接(无 current)时也要能下。
 */
class A11yServiceInfoGateTest {

    @Test
    @DisplayName("事件分发中：绝不交出待下发值（否则就是那条解绑路径）")
    fun `分发中不下发`() {
        val gate = A11yServiceInfoGate()
        gate.onGenerationChanged(arrayOf("com.tencent.mm", "com.eg.android.AlipayGphone"))

        gate.enterEvent()
        assertNull(gate.takeIfApplicable(current = arrayOf("com.old.app")), "分发中必须返回 null")

        gate.exitEvent()
        assertArrayEquals(
            arrayOf("com.tencent.mm", "com.eg.android.AlipayGphone"),
            gate.takeIfApplicable(current = arrayOf("com.old.app")),
            "分发结束后必须能补上（待下发不能丢）",
        )
    }

    @Test
    @DisplayName("分发中攒下的更新，退出后由下一次安全时机补上")
    fun `待下发不丢`() {
        val gate = A11yServiceInfoGate()
        gate.enterEvent()
        gate.onGenerationChanged(arrayOf("a", "b"))
        assertNull(gate.takeIfApplicable(current = arrayOf("x")))
        assertTrue(gate.hasPending(), "分发中拿不到，但必须留着")

        gate.exitEvent()
        assertArrayEquals(arrayOf("a", "b"), gate.takeIfApplicable(current = arrayOf("x")))
        assertFalse(gate.hasPending(), "取走后不该还挂着")
    }

    @Test
    @DisplayName("集合相同则不下发（顺序变化不算变更，避免无谓的 setServiceInfo）")
    fun `集合相同不下发`() {
        val gate = A11yServiceInfoGate()
        gate.onGenerationChanged(arrayOf("a", "b"))

        assertNull(
            gate.takeIfApplicable(current = arrayOf("b", "a")),
            "同集合不同序 → 不该触发 setServiceInfo",
        )
        assertFalse(gate.hasPending())
    }

    @Test
    @DisplayName("尚未连接（current = null）也要能下发——否则首次连接后白名单永远不生效")
    fun `current 为 null 也能下发`() {
        val gate = A11yServiceInfoGate()
        gate.onGenerationChanged(arrayOf("a"))

        assertArrayEquals(arrayOf("a"), gate.takeIfApplicable(current = null))
    }

    @Test
    @DisplayName("没有待下发时返回 null（不触发多余的 setServiceInfo）")
    fun `无待办不下发`() {
        val gate = A11yServiceInfoGate()
        assertNull(gate.takeIfApplicable(current = arrayOf("a")))
    }

    @Test
    @DisplayName("isDispatching 只反映事件分发窗口")
    fun `分发窗口标记`() {
        val gate = A11yServiceInfoGate()
        assertFalse(gate.isDispatching())
        gate.enterEvent()
        assertTrue(gate.isDispatching())
        gate.exitEvent()
        assertFalse(gate.isDispatching())
    }
}
