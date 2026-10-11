package com.aibill.android.domain.record

import com.aibill.android.domain.model.Transaction

/**
 * 入账（记一笔）唯一入口。
 *
 * ## 收口了什么
 *
 * 此前「记一笔」有 6 条手写拷贝（AI 自动入账 / 弹窗确认 / 弹窗带备注确认 / 通知中心单条 /
 * 通知中心批量 / 外部 Intent 静默 / 手工记账落离线），各自拼装 `PendingTransactionEntity`
 * 并各自排同步、刷 widget、发回执。任何一条不变量的改动都要改 6 处——于是**已经漏过一次**：
 * 其中 4 条用 `SimpleDateFormat(Locale.getDefault())` 落 `date`/`time`，泰语 locale 下
 * 写进去的是佛历年 `2569-09-11`、阿拉伯语 locale 下是阿拉伯数字，而服务端契约是 `YYYY-MM-DD`。
 *
 * 现在 clientId 生成、时钟、entity 装配、跨表写入、分类预测/学习、排同步调度、
 * widget 累加、回执/轻通知、隐私档读取全部归本 module，外部只表达**意图**。
 *
 * ## 为什么不叫 Repository
 *
 * 它不是某张表的仓储，也不只做一件事：它是「让一笔账成为本地事实」这条**业务动作**本身
 * （落库只是其中一步）。分层与归属见 `docs/ARCHITECTURE.md` 的「入账 RecordIntake」一节。
 *
 * ## 意图与调用点一一对应
 *
 * | 意图 | 调用点 | 回执 | 预测分类 | 学习 | 排调度 |
 * |---|---|---|---|---|---|
 * | [RecordIntent.NotificationAuto] | `NotificationProcessor.directInsert` | [ReceiptPolicy.AUTO] | 否（上游已算） | 是 | 每笔 |
 * | [RecordIntent.NotificationConfirm] | `NotificationActionReceiver.handleConfirm` | [ReceiptPolicy.CONFIRM_RECEIPT] | 是 | 否 | 每笔 |
 * | [RecordIntent.NotificationConfirm] | `NotificationActionReceiver.handleConfirmWithNote` | [ReceiptPolicy.CONFIRM_RECEIPT] | 是 | 是 | 每笔 |
 * | [RecordIntent.NotificationCenterConfirm] | `NotificationCenterViewModel.insertTransaction` | [ReceiptPolicy.NONE] | 否 | 否 | 每笔 |
 * | [RecordIntent.NotificationCenterConfirm] | `NotificationCenterViewModel.confirmAll` | [ReceiptPolicy.NONE] | 否 | 否 | [SyncScheduling.DEFERRED] |
 * | [RecordIntent.ExternalQuickRecord] | `ExternalActionReceiver.handleAutoConfirmRecord` | [ReceiptPolicy.NONE] | 否 | 否 | 每笔 |
 * | [RecordIntent.ManualOffline] | `TransactionRepositoryImpl.createTransactionOffline` | [ReceiptPolicy.NONE] | 否 | 否 | 每笔 |
 *
 * 上表里的「否」多数是**历史事实而非疏漏**：批量确认本来就不预测不学习，
 * 弹窗确认与带备注确认的差异是长期存在的。收口只搬运，不统一——见各意图的 KDoc。
 */
interface RecordIntake {

    /**
     * 记一笔。
     *
     * 内部顺序**不可调换**：先写库、后排调度（反过来可能让 SyncWorker 早于本行提交
     * 就跑到空队列，白跑一轮还要等下一次网络跳变才补传），再刷 widget / streak / 回执。
     */
    suspend fun intake(intent: RecordIntent): RecordOutcome

    /**
     * 批量入账收尾：循环结束后**统一**排一次同步。
     *
     * 存在的唯一理由是 [RecordIntent.NotificationCenterConfirm] 的
     * [SyncScheduling.DEFERRED]：`confirmAll` 历史上就是「每笔只写库 + 刷 widget，
     * 循环结束才排一次」。收口成「每笔一次排调度」会把 1 次入队变成 N 次
     * （`SyncScheduler` 用 `APPEND_OR_REPLACE`，连排 N 笔就入队 N 个 worker），
     * 那是行为变更，不在本次收口范围内。
     */
    fun scheduleBatchSync()
}

/** 一次入账的结果。目前只有 clientId 对调用方有意义（撤销与幂等的唯一凭据）。 */
data class RecordOutcome(val clientId: String)

/** 给用户什么回执。 */
enum class ReceiptPolicy {
    /** 自动入账回执：带「撤销 / 查看」Action，需要 clientId 才能定位那一行 */
    AUTO,

    /** 弹窗确认后的轻反馈：无 Action，3s 自动消失 */
    CONFIRM_RECEIPT,

    /** 不给用户任何回执（用户已经在界面上看到了结果） */
    NONE,
}

/**
 * 排同步调度的时机。
 *
 * 绝大多数意图是 [IMMEDIATE]；只有通知中心批量确认是 [DEFERRED]。
 */
enum class SyncScheduling {
    /** 本笔写完立即排 */
    IMMEDIATE,

    /** 本笔不排，由调用方在批量结束后调 [RecordIntake.scheduleBatchSync] 统一排一次 */
    DEFERRED,
}

/**
 * 一条已捕获的通知里，入账真正需要的那几个字段。
 *
 * AI 自动入账要**新建**一条 `notification_records` 审计记录，而弹窗确认路径读的是
 * 早已存在的记录——所以这两种形态必须用不同的意图表达，不能合并。
 */
data class CapturedNotification(
    val packageName: String,
    val title: String,
    val content: String,
    val receivedAt: Long,
)

/**
 * 入账意图。**一个 subtype = 一条历史写入路径的全部差异**。
 *
 * 拆成 subtype 而不是一堆布尔开关，是因为这 6 条拷贝之间的差异（预测/学习/回执/排调度/
 * 来源名映射口径）是**历史事实**：把它们表达成默认值，迟早有人「顺手统一」成一种行为，
 * 而其中没有任何一种差异是 bug。
 */
sealed interface RecordIntent {

    /** 本意图给用户什么回执 */
    val receipt: ReceiptPolicy

    /**
     * AI 自动入账：AI 结果信息完整，无需用户确认，直接落库。
     *
     * 对应今天的 `NotificationProcessor.directInsert`，与其它路径的差异（都保留）：
     * - **新建** `notification_records` 行（其余路径都是改已存在的行）；
     * - `source_detail` 走**云控**来源名映射（`friendlyName(pkg, rulesManager)`），
     *   其余路径走硬编码映射；
     * - 回执带「撤销 / 查看」Action（要 clientId），且**读隐私档**（其余路径写死 false）；
     * - 刷 streak（连续记账天数），其余路径不刷；
     * - 学习分类（`commitBest` 原来在 `directInsert` 之后学）；
     * - **不做**分类预测：学习命中在 `process()` 里就算好了，且它还参与「信息是否完整」
     *   的判定（`isComplete`），挪进本 module 会改变待审/入库的判定结果。
     *
     * @param categoryId 已在 `process()` 里用学习结果兜底过的分类
     */
    data class NotificationAuto(
        val captured: CapturedNotification,
        val amount: Int,
        val type: String,
        val categoryId: Int?,
        val categoryName: String? = null,
        val categoryIcon: String? = null,
        val description: String?,
    ) : RecordIntent {
        override val receipt: ReceiptPolicy get() = ReceiptPolicy.AUTO
    }

    /**
     * 通知弹窗确认：用户在弹窗上点「确认」或「备注后确认」。
     *
     * 对应今天的 `NotificationActionReceiver.handleConfirm` / `handleConfirmWithNote`，
     * 两条路径的差异只有一个：[learn]（见下），其余逐行相同：
     * - 改**已存在**的 `notification_records` 行（填 `status` + 回填 `client_id`）；
     * - `source_detail` 走**硬编码**来源名映射（不带云控）——与 AI 自动路径的历史差异，保留；
     * - 分类**只写 id，不写 name/icon**（与「手工落离线」写冗余 name/icon 的差异一样，保留）；
     * - 回执读隐私档**写死 false**，且 notificationId 是 `recordId + 100000`
     *   （避开原确认弹窗的通知 id）；
     * - 排调度在写库之后、刷 widget 之前。
     *
     * @param description 最终描述：直接确认时是 `parsedDescription`；
     *   带备注确认时是 `note?.takeIf { it.isNotBlank() } ?: parsedDescription`（由调用方算好）。
     * @param learn 传 `false` = 弹窗直接确认（`handleConfirm` 的历史行为，不学习）；
     *   传 `true` = 带备注确认（`handleConfirmWithNote` 的历史行为，学分类）。
     *   **两条路径的差异保留，不要统一。**
     */
    data class NotificationConfirm(
        val recordId: Long,
        val packageName: String,
        val amount: Int,
        val type: String,
        val description: String?,
        val learn: Boolean,
    ) : RecordIntent {
        override val receipt: ReceiptPolicy get() = ReceiptPolicy.CONFIRM_RECEIPT
    }

    /**
     * 通知中心确认：用户在通知中心页单条确认或批量确认。
     *
     * 对应今天的 `NotificationCenterViewModel.insertTransaction`（含 `confirmItem` /
     * `confirmWithEdit`）与 `confirmAll` 的循环体，差异只有 [scheduling]：
     * - 分类由用户在界面上手选，因此 name/icon 由调用方从分类列表里查好带进来；
     * - 批量确认不传分类与标签（保持「批量只认原样入账」）；
     * - **不预测分类、不学习**：学习仍在 `confirmWithEdit` 里显式做（用户主动改了分类才学）；
     * - 无回执。
     *
     * @param scheduling 批量确认传 [SyncScheduling.DEFERRED]，单条确认用 [SyncScheduling.IMMEDIATE]
     */
    data class NotificationCenterConfirm(
        val recordId: Long,
        val packageName: String,
        val amount: Int,
        val type: String,
        val description: String?,
        val categoryId: Int? = null,
        val categoryName: String? = null,
        val categoryIcon: String? = null,
        val tags: List<String> = emptyList(),
        val scheduling: SyncScheduling = SyncScheduling.IMMEDIATE,
    ) : RecordIntent {
        override val receipt: ReceiptPolicy get() = ReceiptPolicy.NONE
    }

    /**
     * 外部 Intent 静默入账（Tasker / 快捷指令，同签名权限校验在调用方）。
     *
     * 对应今天的 `ExternalActionReceiver.handleAutoConfirmRecord`，其独有的历史写法全部保留：
     * - `source="external_intent"`、`source_detail="tasker_quick_record"`
     *   （Tasker 与通知自动记账的区分依据，丢了服务端就分不出来）；
     - `client_created_at` 用**无时区的本地日期时间串**（`LocalDateTime.now().toString()`），
     *   与其余路径的 `Instant`（UTC）口径不同——保留，不在本次收口里统一；
     - **不刷 widget**（其余通知路径都刷）。
     *
     * 安全边界不变：权限检查与 `auto_confirm` 判定仍在 [onReceive]，不在本意图内。
     */
    data class ExternalQuickRecord(
        val amount: Int,
        val type: String,
        val description: String?,
    ) : RecordIntent {
        override val receipt: ReceiptPolicy get() = ReceiptPolicy.NONE
    }

    /**
     * 手工记账网络失败后落离线。
     *
     * 对应今天的 `TransactionRepositoryImpl.createTransactionOffline`，其独有的历史写法保留：
     * - `clientId` 用调用方给的（UI 侧生成，用于「保存成功但没上传」的本地去重），**不重新生成**；
     * - 冗余写 `categoryName` / `categoryIcon` / `accountId` / `accountName` / `targetAccountId`
     *   （PR #43：未同步期间列表展示不需要 join 分类表）——通知路径不这么写，保留差异；
     * - `source` 取调用方事务的来源（`manual` / `ai`），**不写 `source_detail`**；
     - `date` / `time` 用**用户当时选的时间**，不是入账时刻；
     - **不刷 widget**。
     *
     * 排调度失败只记日志、绝不回滚、绝不抛出的理由见 [RecordIntakeImpl] 的 KDoc。
     */
    data class ManualOffline(val transaction: Transaction) : RecordIntent {
        override val receipt: ReceiptPolicy get() = ReceiptPolicy.NONE
    }
}
