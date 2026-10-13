package com.aibill.android.di

import com.aibill.android.data.notification.AndroidAutomationHealthSource
import com.aibill.android.data.notification.AutomationHealthSource
import com.aibill.android.data.notification.CloudNotificationRuleSource
import com.aibill.android.data.notification.DataStoreFunnelStatsStore
import com.aibill.android.data.notification.FunnelStatsStore
import com.aibill.android.data.notification.NotificationPrefilterImpl
import com.aibill.android.domain.notification.CaptureFunnelStats
import com.aibill.android.domain.notification.NotificationPrefilter
import com.aibill.android.domain.notification.NotificationRuleSource
import com.aibill.android.domain.notification.PrefilterReport
import com.aibill.android.domain.notification.PrefilterStats
import com.aibill.android.data.notification.rules.NotificationRulesManager
import com.aibill.android.util.AppLogger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 预筛 module 的装配。
 *
 * ⚠️ [NotificationRuleSource] 的生产绑定是 [CloudNotificationRuleSource]（云控 + 代际缓存）；
 * [com.aibill.android.data.notification.InMemoryNotificationRuleSource] 只出现在单测里。
 */
@Module
@InstallIn(SingletonComponent::class)
object NotificationPrefilterModule {

    /**
     * 判据的数据源。抽成接口只为让整条判定链能在不碰 SharedPreferences / 网络的情况下被断言
     * （否则「判据链是纯函数」这条护栏根本立不起来）。
     */
    @Provides
    @Singleton
    fun provideNotificationRuleSource(
        rulesManager: NotificationRulesManager,
        appLogger: AppLogger,
    ): NotificationRuleSource = CloudNotificationRuleSource(rulesManager, appLogger)

    /** 预筛本身是 @Singleton：内容记忆与去重窗口是跨通知的进程级状态。 */
    @Provides
    @Singleton
    fun provideNotificationPrefilter(impl: NotificationPrefilterImpl): NotificationPrefilter = impl

    /**
     * 预筛拦截计数（PRODUCT §三「预筛拦下了什么没有计数」的客户端答案）。
     * 纯内存，进程级；将来接遥测端点时直接复用这份计数。
     */
    @Provides
    @Singleton
    fun providePrefilterStats(impl: com.aibill.android.data.notification.PrefilterStatsImpl): PrefilterStats = impl

    /**
     * 捕获层漏斗计数（PRODUCT §三「漏账率」缺的那块：adapter 早退的每一层）。
     * 与 [PrefilterStats] 同形态、同作用域，但**只记 adapter 自己拦下的**，不重复预筛的账。
     */
    @Provides
    @Singleton
    fun provideCaptureFunnelStats(
        impl: com.aibill.android.data.notification.CaptureFunnelStatsImpl,
    ): CaptureFunnelStats = impl

    /**
     * 漏斗计数的持久化存储（P0-2）：未上报的计数落单行 JSON，进程重启后恢复。
     * 只解决「重启丢计数」，不改变键名 / 窗口 / 上报语义。
     */
    @Provides
    @Singleton
    fun provideFunnelStatsStore(impl: DataStoreFunnelStatsStore): FunnelStatsStore = impl

    /**
     * 首页「自动记账是否在工作」的状态读取口（P0-3）。
     * 只读三项系统设置，不碰判定链。
     */
    @Provides
    @Singleton
    fun provideAutomationHealthSource(impl: AndroidAutomationHealthSource): AutomationHealthSource = impl

    /**
     * ⑤ 拦截计数上报（跨端契约 §4）。
     *
     * ⚠️ 触发只挂在后台任务（[com.aibill.android.service.RulesSyncWorker]），不用前台定时器；
     * 实现本身 best-effort（失败只记日志、下窗口重发）。
     */
    @Provides
    @Singleton
    fun providePrefilterReport(
        impl: com.aibill.android.data.notification.PrefilterReportImpl,
    ): PrefilterReport = impl
}
