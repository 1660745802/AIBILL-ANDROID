package com.aibill.android.data.notification

import android.content.Context
import com.aibill.android.domain.notification.AutomationHealth
import com.aibill.android.util.BatteryOptimizationHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自动记账系统前置条件（P0-3）的读取口。
 *
 * 抽接口是为了让首页 ViewModel 不持 Context（本工程架构约定），单测可注入假实现。
 */
interface AutomationHealthSource {
    fun snapshot(): AutomationHealth
}

/** Android 实现：三个静态检查，纯读、无副作用。 */
@Singleton
class AndroidAutomationHealthSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : AutomationHealthSource {

    override fun snapshot(): AutomationHealth = AutomationHealth(
        notificationListenerEnabled = BatteryOptimizationHelper.isNotificationListenerEnabled(context),
        accessibilityEnabled = BatteryOptimizationHelper.isA11yServiceEnabled(context),
        batteryOptimizationIgnored = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context),
    )
}
