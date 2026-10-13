package com.aibill.android.di

import com.aibill.android.data.a11y.A11yAdmissionGateImpl
import com.aibill.android.domain.a11y.A11yAdmissionGate
import com.aibill.android.domain.a11y.A11yClock
import com.aibill.android.domain.a11y.SystemA11yClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * A11Y 准入状态机的装配。
 *
 * gate 与时钟都是 `@Singleton`：gate 持有跨事件的进程级状态（节流窗 / 防抖槽 / 挂起重试），
 * 时钟无状态。平台侧（`PaymentAccessibilityService`）经 `@EntryPoint` 取用。
 */
@Module
@InstallIn(SingletonComponent::class)
object A11yAdmissionModule {

    @Provides
    @Singleton
    fun provideA11yAdmissionGate(impl: A11yAdmissionGateImpl): A11yAdmissionGate = impl

    @Provides
    @Singleton
    fun provideA11yClock(): A11yClock = SystemA11yClock
}
