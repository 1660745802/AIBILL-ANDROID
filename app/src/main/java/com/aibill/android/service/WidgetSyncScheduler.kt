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
 * 自建 SupervisorJob + Dispatchers.IO：Widget 同步逻辑独立、不与其他模块协程共享，
 * 保持 fire-and-forget 语义。子 job 失败已在调用点 try-catch + Timber.e 兜底，
 * 进程退出时随 JVM 释放。未走 @ApplicationScope 注入是为了减少与全局协程的耦合、
 * 保持该组件内聚——测试时通过 mock WidgetDataUpdater 间接覆盖即可。
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
