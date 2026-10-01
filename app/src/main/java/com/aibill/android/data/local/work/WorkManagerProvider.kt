package com.aibill.android.data.local.work

import android.content.Context
import androidx.work.WorkManager
import com.aibill.android.service.SyncScheduler
import com.aibill.android.service.SyncWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WorkManager 抽象层，封装 cancelUniqueWork 调用。
 *
 * 存在原因：AuthRepositoryImpl.awaitSyncIdle() 需要在 login/register
 * 前取消在飞的 SyncWorker。直接调 WorkManager.getInstance() 是静态
 * 调用，无法在单元测试中 mockk（mockkStatic + Android framework class
 * 会触发 AbstractMethodError）。
 *
 * 通过这个接口，测试可以传入 mock 实现，不需要触发真实 WorkManager。
 */
interface WorkManagerProvider {
    fun cancelSyncWorker()

    /**
     * 入队一次同步（OneTime + NetworkType.CONNECTED）。
     *
     * AuthRepositoryImpl 在同账号重新登录后调用：把因 401 卡在 failed 的离线记录
     * 重置为 pending 后，必须主动排一次 SyncWorker，否则要等下一次网络回调/手动同步。
     */
    fun scheduleSync()
}

@Singleton
class DefaultWorkManagerProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : WorkManagerProvider {
    override fun cancelSyncWorker() {
        WorkManager.getInstance(context).cancelUniqueWork(SyncWorker.WORK_NAME)
    }

    override fun scheduleSync() {
        SyncScheduler.scheduleSyncIfNeeded(context)
    }
}
