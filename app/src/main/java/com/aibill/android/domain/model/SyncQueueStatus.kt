package com.aibill.android.domain.model

/**
 * 离线同步队列的一次**原子快照**：把「还没确认上传成功」的行按处置方式分成两份。
 *
 * 由 [com.aibill.android.domain.repository.TransactionRepository.observeSyncStatus]
 * 作为单一数据源提供（底层是一条 `COUNT(CASE WHEN ...)` 聚合查询，一次读取、
 * 两个数字来自同一份快照，不存在两个 Flow 各自重查导致的中间不一致帧）。
 *
 * ## 为什么只有两个字段、且不做 sealed
 *
 * `pending` 与 `failed` 是**同一张表同一批行的两个可并存分区**，不是互斥的种类：
 * 两者完全可能同时大于 0。因此用 sealed 表达是错的——它会诱导调用方写
 * `when (status)` 这种「优先级」判断，而"哪个更该先显示"是展示层的排版决策。
 *
 * ## 为什么不放 BannerKind / title / color 之类的展示字段
 *
 * 格式化（档位、文案、配色、图标、按钮有无）一律留在
 * `presentation.ui.home.HomeScreen` 的 `bannerVariantOf()` 与 `PendingSyncBanner` 内部。
 * domain 层一旦沾上文案或配色令牌，UI 改一次文案就要动 domain，
 * 而这正是「为了测试抽 shallow module」的典型形态：类型里没有不变量，
 * 只有一层转发，测它等于测 mock 自己。
 *
 * ## 为什么没有 `unsyncedTotal`（`pending + failed`）
 *
 * 合并成一个数会**同时污染两个口径**：
 * - `pending` 的语义是「排队等上传的条数」（横幅对用户承诺的是"会自动补上"），
 *   混进 failed 后这个数字就与「实际可自动续传的条数」对不上；
 * - `pending + failed` 与 `PendingTransactionRepository.getAnyUnsyncedCount()`
 *   是**同一个数字的第二份载体**，多一份就多一个漂移点。
 *
 * 需要合并语义时（切换服务器前的清空警告），请直接用
 * `getAnyUnsyncedCount()`，那是它唯一且已定语义的持有者。
 *
 * ## ⚠️ 不得被 ServerConfig 复用
 *
 * 本类型是**横幅**的口径（回答「有没有东西没传上去、哪部分需要人管」）；
 * `ServerConfigViewModel` 的警告语义是「这次 deleteAll 会带走多少用户数据」，
 * 由 `PendingTransactionRepository.getAnyUnsyncedCount()` 承担。
 * 让两者互相复用会在同一张表上产生第三种计数口径——本项目历史上
 * 「名字说 unsynced、实现只数 pending」的 bug 正是这么来的。
 *
 * @property pending `sync_status = 'pending'` 的行数：已排队、会被自动续传
 * @property failed `sync_status = 'failed'` 的行数：本地终态。**除 401 类**
 *   （`last_error` 匹配 `Token expired`）会在同账号重新登录后由 `resetFailedMatching`
 *   复位并自动续传外，其余不会自动重试
 */
data class SyncQueueStatus(
    val pending: Int,
    val failed: Int,
) {
    /** 是否没有任何待上传行。只回答「有没有东西」，不表达任何合并口径。 */
    val isEmpty: Boolean get() = pending == 0 && failed == 0
}
