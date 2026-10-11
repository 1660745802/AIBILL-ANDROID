package com.aibill.android.data.local.work

import android.content.Context
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WorkManager 抽象层，封装 cancelUniqueWork 调用。
 *
 * 存在原因：本地数据归属 module（`LocalDataOwnership.awaitSyncQuiescence()`）
 * 需要在 login/register 前取消在飞的同步任务。直接调 WorkManager.getInstance()
 * 是静态调用，无法在单元测试中 mockk（mockkStatic + Android framework class
 * 会触发 AbstractMethodError）。
 *
 * ⚠️ **唯一调用方是 `LocalDataOwnershipImpl`**（架构候选 4 收口前是
 * `AuthRepositoryImpl.awaitSyncIdle()`）。
 *
 * 通过这个接口，测试可以传入 mock 实现，不需要触发真实 WorkManager。
 *
 * ⚠️ **排任务不在本接口**：记账不再有任何后台补传任务，`WORK_NAME` 只作为
 * 历史队列 work 的名字留在这里，`cancelUniqueWork` 因此恒为无害的空操作——
 * 保留它是因为归属 module 的握手时序（取消在飞任务 → 等锁 → 处置数据）本身
 * 仍是一条要在处置前成立的顺序不变量。
 */
interface WorkManagerProvider {
    fun cancelSyncWorker()
}

@Singleton
class DefaultWorkManagerProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : WorkManagerProvider {

    override fun cancelSyncWorker() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    companion object {
        /** 历史同步任务的 unique work 名（该任务已删除，取消它永远是空操作） */
        const val WORK_NAME = "transaction_sync"
    }
}
