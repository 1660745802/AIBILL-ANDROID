package com.aibill.android.domain.ownership

/**
 * 本地数据的**归属与处置**：本地缓存里那几张表属于哪个 server × account，
 * 以及在三种意图下该怎么处置它们。
 *
 * ## 收口了什么
 *
 * 「把本地数据清掉」这件事原本写了两遍，靠两段 KDoc 互相声明「表集合必须一致，改一处
 * 必须改另一处」：
 *
 * - `AuthRepositoryImpl.clearLocalCache()`（登录路径：cancel 在飞 SyncWorker → 等 SyncLock
 *   → 清 4 张表）；
 * - `ServerConfigViewModel.onSave(clearLocalCache = true)`（同一个 4 张表清单，
 *   **没有** SyncLock 握手）。
 *
 * 表是同一批、归属是同一个问题，却分属两个 module 各手列一遍。现在归属判据、
 * 表清单、SyncLock 握手、`last_known_user_id` 的存活期都住在这里，
 * 两个调用点各只剩**一句意图**。
 *
 * ## 归属是什么（这是本 module 的不变量）
 *
 * 本地缓存里会随用户产生的数据一共 4 张表，见 `data/ownership/LocalDataOwnershipImpl`
 * 的 `OWNED_TABLES` 注释。它们当前**只**由 account 维度判归属
 * （[LocalOwnership.accountId] ← `TokenManager.getLastKnownUserId()`），
 * **server 维度是空的**：换服务器当前被当作「整表作废」而不是「换一个命名空间」。
 * 那就是 ARCHITECTURE §11.1 #3 未修的根因——登出态换服务器且两端 userId 相同时会撞名。
 * 本切片只做收口、不修根因；归属键写成 [LocalOwnership] 就是为了后续加 server scope
 * 时**只有这一处**需要改，不必回到两个调用点。
 *
 * ## 处置意图只有三种，且都由本 module 兑现
 *
 * | 意图 | 场景 | 4 张表 | 附带动作 |
 * |---|---|---|---|
 * | [DisposalIntent.SameAccountRelogin] | Token 过期后重登、登出后登回同一账号 | **一张都不动** | 只按 `ERROR_TOKEN_EXPIRED_PATTERN` 复活 failed 行 + 主动排同步 |
 * | [DisposalIntent.SwitchingAccount] | 换号 / 首次登录 / 旧版本拿不到 userId | 清空 | — |
 * | [DisposalIntent.SwitchingServer] | `ServerConfig` 页保存服务器地址 | 清空（**同样 4 张**） | — |
 *
 * 「换服务器为什么也清 4 张」的成本写在 `LocalDataOwnershipImpl`：A→B→A 换回原服务器时，
 * 中间捕获的通知记录一并丢失（通知记录是设备侧捕获的审计数据，不带服务器归属，
 * 按「换服务器 = 换命名空间」处理，接受该丢失）。
 *
 * ## 判定归属与处置是两件事，别在调用方合成
 *
 * 登录路径要**先**问归属（[currentOwnership]，必须在 `TokenManager.saveSession()` 之前问——
 * 它会覆盖 `last_known_user_id`），**再**处置（[dispose]），
 * 而且处置必须排在 `saveSession()` 之前，否则 user B 的 session 会盖在还没清掉的 user A 数据上。
 * 归属怎么算归本 module（[LocalOwnership.isSameAccount]），处置出什么副作用也归本 module
 * （[DisposalOutcome]）——调用方不重算一遍，自然就不会出现两处判定打架。
 *
 * ## `last_known_user_id` 活得比 session 长，且不是本 module 写的
 *
 * 判据来自 `TokenManager.getLastKnownUserId()`，**任何**会话结束路径都不清除它
 * （401 不清、显式登出也不清）：删掉它，用户登出再登回同一账号时会退化成「首次登录」
 * 而清空离线队列，违反登出弹窗对用户的承诺。它的写入发生在
 * `TokenManager.saveSession()`，本 module 只读。
 */
interface LocalDataOwnership {

    /**
     * 当前本地缓存归属谁。
     *
     * ⚠️ **必须在 `TokenManager.saveSession()` 之前调用**：那会原子写入新的
     * `last_known_user_id`，之后问到的就永远是「刚登进来的这个账号」，
     * 于是同号重登会被误判成换号 → 离线记账被物理删除。
     */
    fun currentOwnership(): LocalOwnership

    /**
     * 按意图处置本地数据，返回**实际发生了什么**（不是意图的复述）。
     *
     * 返回值存在的理由：调用方要据此决定后续动作（登录路径要据此决定是否重放
     * 因 401 未解析的通知）。让「数据是否留下来了」由真正做处置的一方回答，
     * 调用方就不必把归属判定重算一遍——两处判定打架正是本 module 收口前的老毛病。
     */
    suspend fun dispose(intent: DisposalIntent): DisposalOutcome

    /**
     * SyncLock 握手：取消在飞的 SyncWorker，并等 `SyncLock` 释放（最长 3s）。
     *
     * 为什么必须在处置之前：清缓存 / 换 token 时若同步循环跑到一半，
     * user A 的交易会用 user B 的 token 写到 user B 的服务端。
     *
     * 换服务器路径**不**调它（与收口前一致，`ServerConfig` 仅登录页可达、此时无有效会话）。
     */
    suspend fun awaitSyncQuiescence()
}

/**
 * 本地缓存的归属键 = **server × account**。
 *
 * 现在只有 **account 维度**有判据：[accountId] 来自 `last_known_user_id`
 * （跨 session 存活的字段，见 [LocalDataOwnership] 的 KDoc）。
 *
 * ⚠️ **server 维度是缺席的**，这不是笔误：当前换服务器被当作「整表作废」，
 * 而不是「切到另一个命名空间」。自建部署里两端 userId 常常相同，于是
 * A→登出→换 B→登回 A 会看到 B 的数据（ARCHITECTURE §11.1 #3，本切片只收口不修根因）。
 * 后续切片补 server scope 时，归属键**只有这一处**需要改：
 * 判据、[DisposalIntent] 的取舍、以及两个调用点各自的一句话意图都不用动。
 */
data class LocalOwnership(
    /** 本地缓存归属的账号；`null` = 从未登录过，或老版本没写 `last_known_user_id`。 */
    val accountId: Int?,
) {

    /**
     * 这次登录算不算「同一个账号」。
     *
     * `accountId == null`（首次安装 / 老版本无此字段）一律**判否**，
     * 于是走 [DisposalIntent.SwitchingAccount] 保守清空——
     * 判据不可知时宁可多清一次本地缓存，也不能把两个账号的数据混在一起。
     */
    fun isSameAccount(userId: Int): Boolean = accountId != null && accountId == userId
}

/**
 * 处置本地数据的**三种意图**。意图之外，本 module 不接受任何形态参数
 * （不要加 `clearAll: Boolean` 之类的开关：那会让「清哪几张表」重新散回调用方）。
 */
sealed interface DisposalIntent {

    /**
     * 换号 / 首次登录 / 旧版本拿不到 userId：本地数据归**上一个**账号，清空 4 张表。
     */
    data object SwitchingAccount : DisposalIntent

    /**
     * 同号重登（Token 过期后重登、登出后登回同一账号）：数据仍归同一个账号，
     * **一张表都不清**，只把 `last_error LIKE '%Token expired%'` 的 failed 行复活回 pending，
     * 再主动排一次同步。
     *
     * 为什么保留：Token 30 天过期（无 refresh token）恰恰是离线数据最容易堆积的时刻，
     * 早期实现在登录成功时无条件清空，用户「断网时记的、还没同步上去的账」被物理删除，
     * 且不可恢复（`AuthRepositoryImplTest` 的 `DATA LOSS regression` 钉住此不变量）。
     *
     * 为什么只按 401 模式复活：`failed` 是本地终态，只捞 `pending`；
     * 但业务错误 / 服务端异常 / 超最大重试是**不可恢复**的，一并复活就是无限重试死循环。
     */
    data object SameAccountRelogin : DisposalIntent

    /**
     * 换服务器（`ServerConfig` 页保存地址）：清空**同样 4 张表**。
     *
     * 这里的通知记录是 G-3 补齐的第 4 张，**别退回去**：
     * 历史上换服务器只清 3 张，A 服务器的通知审计记录会残留到 B 服务器下。
     */
    data object SwitchingServer : DisposalIntent
}

/**
 * [LocalDataOwnership.dispose] 的结果：告诉调用方本地数据**实际**留下了什么。
 */
sealed interface DisposalOutcome {

    /** 4 张表已清空（换号 / 换服务器）。队列里没有这个用户的离线记账了。 */
    data object Cleared : DisposalOutcome

    /**
     * 4 张表一张未动；其中 [revivedRows] 条 401 卡住的行被复活回 pending，并已排同步。
     *
     * `revivedRows == 0` 也是正常的（没有卡住的行）。**无论是不是 0 都会排同步**，
     * 因为「刚登回来」这件事本身就该把积压推一次。
     */
    data class Retained(val revivedRows: Int) : DisposalOutcome
}
