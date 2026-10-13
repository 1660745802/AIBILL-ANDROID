package com.aibill.android.domain.notification

/**
 * 自动记账链路的**系统前置条件**状态（P0-3）。
 *
 * 自动记账依赖三项系统设置在后台静默生效；缺任何一项功能都会静默失效，而用户不会主动进
 * 通知中心/设置页看。首页据此给一张持续卡片，明说「当前会不会工作」+ 一键跳设置，
 * 避免「以为开着、实际一条都没记」。
 *
 * ⚠️ **只读状态、只影响提示**：不参与任何判定链，也不改变捕获/入账行为。
 *
 * @param notificationListenerEnabled 通知监听权限（核心：没它整条链路不捕获）
 * @param accessibilityEnabled 无障碍服务（覆盖「无通知」场景，如部分银行 App）
 * @param batteryOptimizationIgnored 已加入电池优化白名单（不加则后台可能被杀）
 */
data class AutomationHealth(
    val notificationListenerEnabled: Boolean,
    val accessibilityEnabled: Boolean,
    val batteryOptimizationIgnored: Boolean,
) {
    /** 核心能力缺失：通知监听没开 → 自动记账**当前不会工作**。 */
    val coreBroken: Boolean get() = !notificationListenerEnabled

    /**
     * 能力不完整：无障碍未开（无通知场景漏账）或电池未加白（后台有被杀风险）。
     * 此时自动记账**会工作但不完整**，文案不得说「不会工作」（不主张不确定的状态）。
     */
    val degraded: Boolean get() = !accessibilityEnabled || !batteryOptimizationIgnored

    /** 是否需要首页提示（任一不满足即提示，直至用户处理）。 */
    val needsAttention: Boolean get() = coreBroken || degraded

    /** 缺失项，按重要性排序；UI 据此列条目并给对应跳转。 */
    val missing: List<Item>
        get() = buildList {
            if (!notificationListenerEnabled) add(Item.NOTIFICATION_LISTENER)
            if (!accessibilityEnabled) add(Item.ACCESSIBILITY)
            if (!batteryOptimizationIgnored) add(Item.BATTERY_OPTIMIZATION)
        }

    /** 缺失的系统前置项。 */
    enum class Item {
        NOTIFICATION_LISTENER,
        ACCESSIBILITY,
        BATTERY_OPTIMIZATION,
    }
}
