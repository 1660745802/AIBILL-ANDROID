package com.aibill.android.data.ownership

import com.aibill.android.data.local.dao.AccountDao
import com.aibill.android.data.local.dao.CategoryDao
import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.data.local.datastore.SyncLock
import com.aibill.android.data.local.work.WorkManagerProvider
import com.aibill.android.data.remote.interceptor.TokenManager
import com.aibill.android.domain.ownership.DisposalIntent
import com.aibill.android.domain.ownership.DisposalOutcome
import com.aibill.android.domain.ownership.LocalDataOwnership
import com.aibill.android.domain.ownership.LocalOwnership
import com.aibill.android.domain.queue.SyncQueue
import kotlinx.coroutines.delay
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地数据归属 module 的实现（架构候选 4）。
 *
 * 它是这 4 张表的**唯一**业务处置方，也是 `SyncLock` 握手与 `WorkManagerProvider`
 * 的唯一调用方。收口前 `AuthRepositoryImpl` 与 `ServerConfigViewModel` 各手列一遍
 * 同一份清单，靠 KDoc 互保（两边必须同步改、两边各钉一套测试）。
 *
 * ## 属于「本地用户数据」的 4 张表 —— 全仓唯一清单
 *
 * | 表 | 归属判据 | 同号重登 |
 * |---|---|---|
 * | `pending_transactions` | 归属当前账号（`last_known_user_id`）：离线队列，是「用户是谁」最直接的证据 | **必须保留**，否则用户断网时记的账被物理删除 |
 * | `categories` | 服务端派生缓存 | 保留（`HomeViewModel.refresh()` 会重新拉；清掉会造成登录后分类短暂空白） |
 * | `accounts` | 服务端派生缓存 | 保留（同上，含余额显示） |
 * | `notification_records` | **设备侧**捕获的审计数据（不带账号列） | 保留（本地通知审计） |
 *
 * 新增第 5 张「要跟着账号走」的表时，**只改这里**（`clearOwnedTables`），
 * 不要在调用方重新列一遍清单——收口前的 bug 形态正是「两个 module 各列一遍，
 * 其中一个漏了新表」。判据是「用户换号后，这张表里的数据对他还有意义吗」：
 * 有 → 进清单；没有（纯设备侧统计 / 纯本地缓存）→ 不进，由各 module 自己管。
 *
 * 另外 5 张表（`category_rules` / `templates` / `auto_rules` / `recurring_rules` / `app_logs`）
 * **刻意不在清单里**：它们要么是可重建的本地配置，要么是跨账号的设备级数据，
 * 历史上换号时也从未被清——收口只搬运、不改行为。
 *
 * ## 不变量
 *
 * 1. **同号重登一张表都不能少**（数据丢失回归，见 `AuthRepositoryImplTest` 的
 *    `DATA LOSS regression` 与本类的同名用例）；
 * 2. 401 恢复只经 `SyncQueue.recoverTokenExpiredFailures()`，判据配对（写入侧
 *    `SyncWorker.ERROR_TOKEN_EXPIRED` ↔ 读取侧 `ERROR_TOKEN_EXPIRED_PATTERN`）由
 *    `SyncQueueImplTest` 用真实 Room 端到端钉住；
 * 3. 换号 / 换服务器清的是**同一份**清单，且都在 `TokenManager.saveSession()` 之前完成
 *    （调用方保证顺序：先 `dispose` 再写 session）；
 * 4. `last_known_user_id` 本 module **只读不写**（写入在 `TokenManager.saveSession()`）。
 */
@Singleton
class LocalDataOwnershipImpl @Inject constructor(
    private val tokenManager: TokenManager,
    private val syncQueue: SyncQueue,
    private val categoryDao: CategoryDao,
    private val accountDao: AccountDao,
    private val notificationRecordDao: NotificationRecordDao,
    private val syncLock: SyncLock,
    private val workManagerProvider: WorkManagerProvider,
) : LocalDataOwnership {

    override fun currentOwnership(): LocalOwnership =
        LocalOwnership(accountId = tokenManager.getLastKnownUserId())

    override suspend fun dispose(intent: DisposalIntent): DisposalOutcome = when (intent) {
        DisposalIntent.SameAccountRelogin -> retainForSameAccount()
        DisposalIntent.SwitchingAccount,
        DisposalIntent.SwitchingServer,
        -> clearOwnedTables(intent)
    }

    /**
     * 同号重登：本地数据**一张都不动**，只把 401 卡住的行复活回 pending 并主动排同步。
     *
     * 复活 + 排调度两件事都必须做：只复活不排，账要等下一次网络跳变才上传；
     * 只排不复活，`getAllPending()` 只捞 `pending`，那批行永远卡在 failed。
     */
    private suspend fun retainForSameAccount(): DisposalOutcome.Retained {
        val accountId = currentOwnership().accountId
        val revived = syncQueue.recoverTokenExpiredFailures()
        if (revived > 0) {
            Timber.i("Ownership: 同账号重登(userId=$accountId)，复活 $revived 条 401 卡住的离线记录为待同步")
        }
        // 排同步失败不外抛：SyncQueueImpl 已把它翻译成 SyncRequest.Rejected 并记过日志，
        // 这里不必打断登录（账还在本地，下次网络跳变还会再排）。
        syncQueue.requestSync()
        return DisposalOutcome.Retained(revived)
    }

    /**
     * 清空归属切走后不再属于当前用户的本地数据 —— **4 张表的清单只在这里**。
     *
     * 顺序沿用收口前 `AuthRepositoryImpl.clearLocalCache()` 的写法：先队列后派生缓存。
     * 顺序本身不承载不变量（4 张表互不引用），但「新表加在末尾」比「插在中间」好读。
     */
    private suspend fun clearOwnedTables(intent: DisposalIntent): DisposalOutcome.Cleared {
        syncQueue.clear()
        categoryDao.deleteAll()
        accountDao.deleteAll()
        notificationRecordDao.deleteAll()
        Timber.i("Ownership: 已清空本地数据（$intent，4 张表）")
        return DisposalOutcome.Cleared
    }

    /**
     * PR C1：清缓存 / 换 token 之前先取消在飞的 SyncWorker 并等 `SyncLock` 释放，
     * 避免循环进行到一半时 user A 的交易用 user B 的 token 写到 user B 的服务端。
     *
     * 最坏情况只丢一次循环（`cancelUniqueWork` 是异步生效的），不会跨用户写。
     * 等锁有上限（[MAX_WAIT_MS]）：锁迟迟不放时宁可直接处置，也不能把登录卡死。
     */
    override suspend fun awaitSyncQuiescence() {
        workManagerProvider.cancelSyncWorker()
        val deadline = System.currentTimeMillis() + MAX_WAIT_MS
        while (syncLock.isActive() && System.currentTimeMillis() < deadline) {
            delay(WAIT_INTERVAL_MS)
        }
    }

    private companion object {
        /** PR C1：等 `SyncLock` 释放的最长时限 */
        private const val MAX_WAIT_MS = 3_000L
        private const val WAIT_INTERVAL_MS = 50L
    }
}
