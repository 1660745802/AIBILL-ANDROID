package com.aibill.android.data.ownership

import com.aibill.android.data.local.dao.AccountDao
import com.aibill.android.data.local.dao.CategoryDao
import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.data.local.dao.PendingTransactionDao
import com.aibill.android.data.remote.interceptor.TokenManager
import com.aibill.android.domain.ownership.DisposalIntent
import com.aibill.android.domain.ownership.DisposalOutcome
import com.aibill.android.domain.ownership.LocalDataOwnership
import com.aibill.android.domain.ownership.LocalOwnership
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地数据归属 module 的实现（架构候选 4）。
 *
 * 它是这 4 张表的**唯一**业务处置方。收口前 `AuthRepositoryImpl` 与
 * `ServerConfigViewModel` 各手列一遍同一份清单，靠 KDoc 互保（两边必须同步改、
 * 两边各钉一套测试）。
 *
 * ## 属于「本地用户数据」的 4 张表 —— 全仓唯一清单
 *
 * | 表 | 归属判据 | 同号重登 |
 * |---|---|---|
 * | `pending_transactions` | 归属当前账号（`last_known_user_id`）：已确认记录的本地缓存，是「用户是谁」最直接的证据 | **必须保留**，否则用户重登后撤销链路失去凭据 |
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
 * 2. 换号 / 换服务器清的是**同一份**清单，且都在 `TokenManager.saveSession()` 之前完成
 *    （调用方保证顺序：先 `dispose` 再写 session）；
 * 3. `last_known_user_id` 本 module **只读不写**（写入在 `TokenManager.saveSession()`）。
 */
@Singleton
class LocalDataOwnershipImpl @Inject constructor(
    private val tokenManager: TokenManager,
    private val pendingTransactionDao: PendingTransactionDao,
    private val categoryDao: CategoryDao,
    private val accountDao: AccountDao,
    private val notificationRecordDao: NotificationRecordDao,
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
     * 同号重登：本地数据**一张都不动**，也没有任何要补做的收尾。
     *
     * 这里曾经还要「把 401 卡住的行复活回 pending + 主动排一次同步」——那是
     * 待上传队列的恢复动作。记账不再有队列，也就没有需要恢复的行：
     * 缓存里的每一行都已经是服务端确认过的。
     */
    private suspend fun retainForSameAccount(): DisposalOutcome.Retained {
        val accountId = currentOwnership().accountId
        Timber.i("Ownership: 同账号重登(userId=$accountId)，本地数据一张不动")
        return DisposalOutcome.Retained
    }

    /**
     * 清空归属切走后不再属于当前用户的本地数据 —— **4 张表的清单只在这里**。
     *
     * 顺序沿用收口前 `AuthRepositoryImpl.clearLocalCache()` 的写法：先本地缓存后服务端派生缓存。
     * 顺序本身不承载不变量（4 张表互不引用），但「新表加在末尾」比「插在中间」好读。
     */
    private suspend fun clearOwnedTables(intent: DisposalIntent): DisposalOutcome.Cleared {
        pendingTransactionDao.deleteAll()
        categoryDao.deleteAll()
        accountDao.deleteAll()
        notificationRecordDao.deleteAll()
        Timber.i("Ownership: 已清空本地数据（$intent，4 张表）")
        return DisposalOutcome.Cleared
    }
}
