package com.aibill.android.di

import com.aibill.android.data.notification.CloudNotificationRuleSource
import com.aibill.android.data.notification.NotificationPrefilterImpl
import com.aibill.android.domain.notification.NotificationPrefilter
import com.aibill.android.domain.notification.NotificationRuleSource
import com.aibill.android.service.NotificationRulesManager
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
}
