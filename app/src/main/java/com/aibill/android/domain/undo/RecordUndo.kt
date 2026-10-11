package com.aibill.android.domain.undo

/**
 * 撤销刚记的那一笔（通知回执上的「撤销」Action 背后的判据）。
 *
 * ## 收口了什么
 *
 * 撤销真值表原本整份写在 `AutoRecordActionReceiver` 这个 `BroadcastReceiver` 里，
 * 而它**一行都测不到**：`Hilt_AutoRecordActionReceiver` 在 `onReceive` 与 `goAsync()`
 * 两处都会 `inject()`，要求 Application 是 `@HiltAndroidApp`；本工程的 Robolectric
 * 给不出这种 Application（`Application::class` 抛 Hilt 前置条件，
 * `AiBillApp::class` 抛 `KeyStoreException: AndroidKeyStore not found`，
 * 引入 `hilt-android-testing` 不在本轮范围）。
 * 于是接收器把 `handleUndo` 提成 `internal suspend fun` 让测试直接调——
 * **逻辑仍住在平台壳里，可见性只为测试放宽**，这就是候选 5 要替掉的「后门」。
 *
 * 现在判据在本 module：receiver 只做平台管道（取 extras、撤通知、`goAsync()` 收尾、
 * 把 [UndoOutcome.userMessage] 播成 Toast），判据与用户文案都在这里，
 * 纯 JVM 单测直接构造实现即可断言，不需要 Robolectric、不碰 `Dispatchers.Main`。
 *
 * ## 撤销真值表 —— **按已知同步状态分支；不确定状态默认不删**
 *
 * | 本地行 | 服务端是否可能已有 | 处理 | [UndoOutcome] |
 * |---|---|---|---|
 * | 不存在 | 未知 | 谁都不删（无凭据，不猜） | [UndoOutcome.LocalMissing] |
 * | `pending` | 通常没有；在途 SyncWorker 是已知竞态 | 删本地（保持既有语义） | [UndoOutcome.Undone] |
 * | `failed` + 401 (`ERROR_TOKEN_EXPIRED`) | 否（服务端明确拒绝） | 删本地 | [UndoOutcome.Undone] |
 * | `failed` + 其它 reason | **可能已经建了** | 谁都不删 | [UndoOutcome.ServerStateUnknown] |
 * | `synced` + 有 serverId | 是 | 删服务端；成功才删本地 | [UndoOutcome.Undone] / [UndoOutcome.ServerStillHasIt] |
 * | `synced` + 无 serverId | 是 | 谁都不删 | [UndoOutcome.ServerStillHasIt] |
 * | 未知 status | 未知 | 谁都不删 | [UndoOutcome.ServerStateUnknown] |
 *
 * ### 为什么 `failed` 不能一律当「服务端没有」
 *
 * `SyncWorker` 的 markFailed 路径里，只有 401 能证明服务端拒绝：
 * - `SyncWorker.ERROR_TOKEN_EXPIRED`：响应是 401，服务端根本没创建 → 可安全删本地。
 * - `SyncWorker.ERROR_SERVER_ANOMALY`：**服务端可能已创建**，只是响应里
 *   `created[]`/`duplicates[]` 都没匹配上 clientId（漏字段/转换异常）。
 * - `SyncWorker.ERROR_MAX_RETRY_EXCEEDED`：本地重试数超限，**上一次请求可能
 *   已经成功、只是响应在网络上丢了**（提交后进程被杀/超时）。
 *   `ERROR_BUSINESS`（5xx）同理。
 *
 * 后三种删本地 = 服务端若已有这笔，就变成「本地删了、服务端还在」的幽灵账。
 * 不删本地的代价是本地那行会一直留着（Home 横幅会计它，且没有逐行删除入口），
 * 但**不能**因此改口说「去流水页删」——这三类里服务端是否真有这笔并不确定。
 * 后者的代价远小于前者，故一律走「不删 + 如实告知未删除任何数据」。
 *
 * ### 为什么不能用 `!= synced` 判据
 *
 * 任何**新增或拼写错误**的 status 都会被 `!= "synced"` 当成「未上传」而删本地，
 * 也就是未来某天改列名/加状态时静默退化成 fail-open。改用白名单 `when`：
 * 未知值落 `else` 分支，默认不删。
 *
 * ## 历史 bug（收口前修的，搬移时逐行保留）
 *
 * 1. 分支条件只判「本地行是否存在」而不看 `sync_status`，且 `cleanSyncedBefore`
 *    从未被调用（DAO 里定义了但零调用点）、synced 行无限留存，
 *    于是「已上传的账」几乎总是命中本地删除分支 → 服务端账目存活，用户却被告知
 *    「已撤销」（实际连回收站都没进）。
 * 2. 已同步时用 `TransactionQuery(keyword = clientId)` + `items.first()` 猜 serverId：
 *    无排序保证、只取第 1 页、命中多条时删错账。现在只认 `server_transaction_id`
 *    （`SyncWorker.markSynced` → `PendingTransactionDao.markSynced`），宁可失败也不猜。
 */
interface RecordUndo {

    /**
     * 撤销 [clientId] 那一笔，返回**实际发生了什么**（不是意图的复述）。
     *
     * 调用方（`AutoRecordActionReceiver`）只负责把 [UndoOutcome.userMessage] 播出去，
     * 不重算判据、不自己决定删不删——两处判定打架正是收口前的老毛病。
     */
    suspend fun undo(clientId: String): UndoOutcome
}

/**
 * 撤销的**实际结果**。用户看到什么由 [userMessage] 表达，处置由实现兑现。
 *
 * ## 为什么文案跟着判据走
 *
 * 文案本身是一条不变量：**不得指引用户无法执行的动作，也不得主张不确定的上传状态**
 * （与 Home 横幅同一约束）。文案若留在 receiver 里，就等于「判据被测了、文案靠 review」，
 * 正是收口前的状态。因此文案随判据一起进本文件，由 `RecordUndoImplTest` 的
 * **字面量双写 + 禁词断言**钉死（分支直接断言到 [userMessage]，不改文案就变红）。
 */
sealed interface UndoOutcome {

    /** 真的要播给用户的那句话。原样透传给 Toast，不做二次加工。 */
    val userMessage: String

    /** 真的删掉了（本地，或服务端成功后再删本地）。 */
    data object Undone : UndoOutcome {
        override val userMessage: String = "已撤销"
    }

    /**
     * 本地没有这一行：谁都不删，也**不指路**（没有证据说它在哪儿）。
     *
     * 文案里的「本地」是限定词不是废话：早期版本是「撤销失败，记录未找到」，
     * 会被读成「这笔从没记过」而诱发用户重新记一遍。
     */
    data object LocalMissing : UndoOutcome {
        override val userMessage: String = "撤销失败，未找到本地记录"
    }

    /**
     * 服务端**确有**这笔（`synced` 是 `code==0` + clientId 匹配的产物），
     * 但本次没能删掉：要么服务端删除失败，要么 `synced` 却没记住 serverId。
     *
     * 必须指向流水页——流水页数据源是服务端，那里看得到、也删得掉。
     * 反过来「一刀切改保守」（什么都不删就说不指路）同样是信息丢失。
     */
    data object ServerStillHasIt : UndoOutcome {
        override val userMessage: String = "撤销失败，请稍后在流水页手动删除"
    }

    /**
     * 服务端**状态未知**（failed 非 401 / 未知 syncStatus）：什么都没删，
     * 既不能主张它在服务器上，本地也没有删除入口。
     *
     * 因此不得指路、不得主张上传状态——见类 KDoc 的约束说明。
     */
    data object ServerStateUnknown : UndoOutcome {
        override val userMessage: String = "撤销失败，未删除任何数据"
    }
}
