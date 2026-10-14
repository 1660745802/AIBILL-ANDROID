package com.aibill.android.di

import com.aibill.android.data.notification.AiCircuitBreakerImpl
import com.aibill.android.data.notification.BalanceConsistencyImpl
import com.aibill.android.data.notification.CaptureIngressImpl
import com.aibill.android.data.notification.CaptureArchiveImpl
import com.aibill.android.data.notification.NotificationInterpreterImpl
import com.aibill.android.data.notification.ScoredAdmissionImpl
import com.aibill.android.domain.notification.AiCircuitBreaker
import com.aibill.android.domain.notification.BalanceConsistency
import com.aibill.android.domain.notification.CaptureArchive
import com.aibill.android.domain.notification.CaptureIngress
import com.aibill.android.domain.notification.NotificationInterpreter
import com.aibill.android.domain.notification.NotificationSink
import com.aibill.android.domain.notification.ScoredAdmission
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 通知核心循环各 module 的装配。
 *
 * 四个 module 各自独占一条策略、各自有跨通知的进程级状态（熔断计数 / 评分窗口 /
 * 已认领记录 / 待补偿队列），因此都是 `@Singleton`。
 * 协调者 [com.aibill.android.service.NotificationProcessor] 只依赖这里的 interface。
 */
@Module
@InstallIn(SingletonComponent::class)
object NotificationLoopModule {

    /** AI 熔断：每来源连续失败计数与静音窗口。由预筛 module 查询。 */
    @Provides
    @Singleton
    fun provideAiCircuitBreaker(impl: AiCircuitBreakerImpl): AiCircuitBreaker = impl

    /** 解析与校验：调 AI、AiResultValidator、营销后缀截断、电商描述脱敏、候选评分。 */
    @Provides
    @Singleton
    fun provideNotificationInterpreter(impl: NotificationInterpreterImpl): NotificationInterpreter = impl

    /** 评分窗口 + 跨渠道判重：同金额 10s 择优、60s 跨渠道只留最优。 */
    @Provides
    @Singleton
    fun provideScoredAdmission(impl: ScoredAdmissionImpl): ScoredAdmission = impl

    /** 捕获与补偿：未解析原文的落库语义 + 401 重放 + 终态。 */
    @Provides
    @Singleton
    fun provideCaptureArchive(impl: CaptureArchiveImpl): CaptureArchive = impl

    /**
     * 预筛放行后的落点：`NotificationProcessor` 是唯一实现（C02 抽出的 seam）。
     */
    @Provides
    @Singleton
    fun provideNotificationSink(processor: com.aibill.android.service.NotificationProcessor): NotificationSink = processor

    /**
     * 捕获入管道：三个平台 adapter 的共同尾巴（C02）。
     */
    @Provides
    @Singleton
    fun provideCaptureIngress(impl: CaptureIngressImpl): CaptureIngress = impl

    /**
     * 余额自洽校验（纯本地）：用上一条同卡余额自证金额；不一致只降级待审，不拒收。
     */
    @Provides
    @Singleton
    fun provideBalanceConsistency(impl: BalanceConsistencyImpl): BalanceConsistency = impl
}
