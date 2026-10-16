package com.aibill.android.service

/**
 * `AccessibilityService.setServiceInfo()` 的**下发时机闸门**。
 *
 * ## 为什么要它
 *
 * `refreshRulesIfNeeded()` 会在**规则代际变化**时下发 `serviceInfo.packageNames`
 * （让系统只投递支付 App 的事件）。改动前它直接写在事件回调链路上
 * （`onAccessibilityEvent` 的第一件事就是 `refreshRulesIfNeeded()`），于是：
 *
 * **服务已连接状态下改 `packageNames` 会让系统走 `AccessibilityManagerService` 的重装路径**，
 * 部分 ROM（MIUI / HyperOS 实测）会直接把服务解绑。而 **Android 没有 a11y 的 rebind API**
 * （写 `Settings.Secure.enabled_accessibility_services` 要 `WRITE_SECURE_SETTINGS`，
 * 普通 App 申请不到），解绑后**只能用户手动去设置里关掉再打开**。
 *
 * 这正是线上「无障碍经常失效、提示我手动关闭后重开」的成因：
 * 规则一变 → 下一个事件 → `setServiceInfo()` → 服务被解绑 → 不再自愈。
 *
 * ## 契约
 *
 * - [onGenerationChanged]：记下**待下发**的 `packageNames`（幂等，重复调用只覆盖）；
 * - [enterEvent] / [exitEvent]：标记是否正在**事件分发中**；
 * - [takeIfApplicable]：只在**不在分发中**、且与当前值确有差异时交出待下发值；
 *   其余情况返回 null（调用方什么也不做）。
 *
 * 因此「代际变了但此刻正在分发事件」不会丢更新——它会留在 pending 里，
 * 由下一次 [takeIfApplicable]（`onServiceConnected` / `onInterrupt`）补上。
 */
internal class A11yServiceInfoGate {

    /** 待下发的 `packageNames`；null = 没有待办。 */
    private var pending: Array<String>? = null

    /** 是否正在 `onAccessibilityEvent` 分发链路上。 */
    private var dispatching: Boolean = false

    fun onGenerationChanged(packages: Array<String>) {
        pending = packages
    }

    fun enterEvent() {
        dispatching = true
    }

    fun exitEvent() {
        dispatching = false
    }

    /**
     * 取走「现在就可以下发」的 `packageNames`；不能下发时返回 null。
     *
     * @param current 当前 `serviceInfo.packageNames`（null = 尚未连接/无 serviceInfo）。
     *   **按集合比较**：顺序变化不算变更，避免无谓的下发。
     */
    fun takeIfApplicable(current: Array<String>?): Array<String>? {
        if (dispatching) return null
        val next = pending ?: return null
        if (current != null && current.toSet() == next.toSet()) {
            pending = null
            return null
        }
        pending = null
        return next
    }

    /** 仅供诊断/测试：此刻是否有挂起的更新。 */
    fun hasPending(): Boolean = pending != null

    /** 仅供诊断/测试：此刻是否处于事件分发中。 */
    fun isDispatching(): Boolean = dispatching
}
