package com.aibill.android.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Widget 同步调度器。**ViewModel 通过此对象更新 Widget，无需持有 Application/Context**。
 *
 * 内部维护独立的 [scope]，调用 [scheduleMonthlyUpdate] 时 fire-and-forget。
 * 与 [com.aibill.android.di.ApplicationScope] 不同的是：这里用普通 SupervisorJob，
 * 因为 Widget 更新失败不应影响应用其他部分。
 */
@Singleton
class WidgetSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun scheduleMonthlyUpdate(expenseCents: Int, incomeCents: Int) {
        scope.launch {
            try {
                WidgetDataUpdater.updateMonthlySummary(
                    context = context,
                    expenseCents = expenseCents,
                    incomeCents = incomeCents,
                )
            } catch (e: Exception) {
                Timber.e(e, "Widget 月度同步失败")
            }
        }
    }
}
