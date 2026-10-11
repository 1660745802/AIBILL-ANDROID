package com.aibill.android.data.local.work

import android.content.Context
import androidx.work.WorkManager
import com.aibill.android.service.SyncWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WorkManager 抽象层，封装 cancelUniqueWork 调用。
 *
 * 存在原因：本地数据归属 module（`LocalDataOwnership.awaitSyncQuiescence()`）
 * 需要在 login/register 前取消在飞的 SyncWorker。直接调 WorkManager.getInstance()
 * 是静态调用，无法在单元测试中 mockk（mockkStatic + Android framework class
 * 会触发 AbstractMethodError）。
 *
 * ⚠️ **唯一调用方是 `LocalDataOwnershipImpl`**（架构候选 4 收口前是
 * `AuthRepositoryImpl.awaitSyncIdle()`）。
 *
 * 通过这个接口，测试可以传入 mock 实现，不需要触发真实 WorkManager。
 *
 * ⚠️ **排同步不在本接口**：它归离线队列 module（`SyncQueue.requestSync`）。
 * 收口前这里也有一个 `scheduleSync()`，与它做的事完全一样——两个入口，
 * 迟早有人改了一个忘了另一个（且测试会互相遮住）。
 */
interface WorkManagerProvider {
    fun cancelSyncWorker()
}

@Singleton
class DefaultWorkManagerProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : WorkManagerProvider {
    override fun cancelSyncWorker() {
        WorkManager.getInstance(context).cancelUniqueWork(SyncWorker.WORK_NAME)
    }
}
