package com.aibill.android.domain.record


/**
 * 入账（记一笔）唯一入口。
 *
 * ## 收口了什么
 *
 * 此前「记一笔」有 6 条手写拷贝（AI 自动入账 / 弹窗确认 / 弹窗带备注确认 / 通知中心单条 /
 * 通知中心批量 / 外部 Intent 静默入账），各自拼装 `PendingTransactionEntity`
 * 并各自发回执。任何一条不变量的改动都要改 6 处——于是**已经漏过一次**：
 * 其中 4 条用 `SimpleDateFormat(Locale.getDefault())` 落 `date`/`time`，泰语 locale 下
 * 写进去的是佛历年 `2569-09-11`、阿拉伯语 locale 下是阿拉伯数字，而服务端契约是 `YYYY-MM-DD`。
 *
 * 现在 clientId 生成、时钟、entity 装配、**送服务端确认**、跨表写入、分类预测/学习、
 * widget 累加、回执/轻通知、隐私档读取全部归本 module，外部只表达**意图**。
 *
 * ## 为什么不叫 Repository
 *
 * 它不是某张表的仓储，也不只做一件事：它是「让一笔账成为事实」这条**业务动作**本身
 * （送服务端、落本地缓存只是其中两步）。分层与归属见 `docs/ARCHITECTURE.md` §2.3。
 *
 * ## 意图与调用点一一对应
 *
 * | 意图 | 调用点 | 回执 | 预测分类 | 学习 |
 * |---|---|---|---|---|
 * | [RecordIntent.NotificationAuto] | `NotificationProcessor.directInsert` | [ReceiptPolicy.AUTO] | 否（上游已算） | 是 |
 * | [RecordIntent.NotificationConfirm] | `NotificationActionReceiver.handleConfirm` | [ReceiptPolicy.CONFIRM_RECEIPT] | 是 | 否 |
 * | [RecordIntent.NotificationConfirm] | `NotificationActionReceiver.handleConfirmWithNote` | [ReceiptPolicy.CONFIRM_RECEIPT] | 是 | 是 |
 * | [RecordIntent.NotificationCenterConfirm] | `NotificationCenterViewModel.insertTransaction` | [ReceiptPolicy.NONE] | 否 | 否 |
 * | [RecordIntent.ExternalQuickRecord] | `ExternalActionReceiver`（`auto_confirm` 静默入账） | [ReceiptPolicy.NONE] | 否 | 否 |
 *
 * 上表里的「否」多数是**历史事实而非疏漏**：批量确认本来就不预测不学习，
 * 弹窗确认与带备注确认的差异是长期存在的。收口只搬运，不统一——见各意图的 KDoc。
 */
interface RecordIntake {

    /**
     * 记一笔。
     *
     * ## 成功判据：服务端回执（`created[]` 或 `duplicates[]`）里按 `client_id` 匹配到 id
     *
     * 顺序**不可调换**：先送服务端 → 拿到服务端 id → 才写本地缓存与审计行
     * → 才刷 widget / streak / 回执 / 学分类。服务端没确认的那一笔**什么都不能发生**：
     * 不写库、不发回执、不学习、不刷统计。
     *
     * 失败一律通过 [RecordOutcome.failure] 如实返回，**绝不静默**：
     * 调用方必须把它变成用户看得见的反馈（见各调用点的处置，`docs/ARCHITECTURE.md` §2.3）。
     */
    suspend fun intake(intent: RecordIntent): RecordOutcome
}

/**
 * 一次入账的结果：**服务端确认了没有**，以及确认之后产生了什么。
 *
 * @property clientId 撤销与幂等的唯一凭据（写进 `pending_transactions.client_id`）
 * @property recordId 本笔新建的通知审计记录 id。**仅** [RecordIntent.NotificationAuto]
 *   非 null（它是唯一会新建审计行的意图）：它同时是 AUTO 回执的通知 id 与「撤销」的定位键。
 *   其余意图改的是早已存在的审计行，没有 id 可回传。
 * @property failure 非 null = 服务端没确认这一笔。此时本地什么都没写，
 *   审计行仍停在待审池，[recordId] 恒为 null。
 */
data class RecordOutcome(
    val clientId: String,
    val recordId: Long? = null,
    val failure: RecordFailure? = null,
) {
    /** 服务端确认这一笔了吗。`true` 才允许对用户说「已记录」。 */
    val recorded: Boolean get() = failure == null
}

/**
 * 服务端**没有**确认这一笔的原因。只有两类，且都必须让用户知道。
 */
sealed interface RecordFailure {

    /** 给用户看的那句话。**不得**出现「已记录 / 已保存 / 已同步」类措辞。 */
    val userMessage: String

    /**
     * 请求根本没送达（无网 / 超时 / 连接被拒）。
     *
     * 手工记账的文案由 `ManualRecordViewModel` 自己给（「没网，记不上」），
     * 因为那条路径不经过本 module。
     */
    data object Offline : RecordFailure {
        override val userMessage: String = "没网，记不上"
    }

    /**
     * 服务端答了话但没确认：业务错误（422 / 5001 …）、HTTP 错误（含 401）、
     * 以及 `code==0` 却在两个回执列表里都按 `client_id` 找不到这笔（漏字段 / 转换异常）。
     *
     * @param code 服务端业务码；HTTP 错误时是 HTTP 状态码；本地未知异常用 `Result.ERROR_UNKNOWN`
     * @param reason 服务端 `message` 或本地拼的说明，直接进日志与用户文案
     */
    data class Rejected(val code: Int, val reason: String) : RecordFailure {
        override val userMessage: String get() = "记账失败：$reason"
    }
}

/**
 * 给用户什么回执。
 *
 * **只在服务端确认之后才会真的发出去**：失败路径压根走不到发回执那一步。
 */
enum class ReceiptPolicy {

    /** 自动入账回执：带「撤销 / 查看」Action，需要 clientId 才能定位那一行 */
    AUTO,

    /** 弹窗确认后的轻反馈：无 Action，3s 自动消失 */
    CONFIRM_RECEIPT,

    /** 不给用户任何回执（用户已经在界面上看到了结果） */
    NONE,
}

/**
 * 一条已捕获的通知里，入账真正需要的那几个字段。
 *
 * AI 自动入账要**新建**一条 `notification_records` 审计记录，而弹窗确认路径读的是
 *早已存在的记录——所以这两种形态必须用不同的意图表达，不能合并。
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
 * 拆成 subtype 而不是一堆布尔开关，是因为这 5 条拷贝之间的差异（预测/学习/回执/
 * 来源名映射口径）是**历史事实**：把它们表达成默认值，迟早有人「顺手统一」成一种行为，
 * 而其中没有任何一种差异是 bug。
 */
sealed interface RecordIntent {

    /** 本意图给用户什么回执 */
    val receipt: ReceiptPolicy

    /**
     * AI 自动入账：AI 结果信息完整，无需用户确认，直接送服务端。
     *
     * 对应 `NotificationProcessor.directInsert`，与其它路径的差异（都保留）：
     * - **新建** `notification_records` 行（其余路径都是改已存在的行）；
     * - `source_detail` 走**云控**来源名映射（`friendlyName(pkg, rulesManager)`），
     *   其余路径走硬编码映射；
     * - 回执带「撤销 / 查看」Action（要 clientId），且**读隐私档**（其余路径写死 false）；
     * - 刷 streak（连续记账天数），其余路径不刷；
     * - 学习分类（`commitBest` 原来在 `directInsert` 之后学）；
     * - **不做**分类预测：学习命中在 `process()` 里就算好了，且它还参与「信息是否完整」
     *   的判定（`isComplete`），挪进本 module 会改变待审/入库的判定结果。
     *
     * **失败时审计行也不建**：调用方（`NotificationProcessor`）据此把这条通知
     * 降级进待审池，用户在通知中心仍看得见。
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
     * 对应 `NotificationActionReceiver.handleConfirm` / `handleConfirmWithNote`，
     * 两条路径的差异只有一个：[learn]（见下），其余逐行相同：
     * - 改**已存在**的 `notification_records` 行（填 `status` + 回填 `client_id`）；
     * - `source_detail` 走**硬编码**来源名映射（不带云控）——与 AI 自动路径的历史差异，保留；
     * - 分类**只写 id，不写 name/icon**（与手工记账写冗余 name/icon 的差异一样，保留）；
     * - 回执读隐私档**写死 false**，且 notificationId 是 `recordId + 100000`
     *   （避开原确认弹窗的通知 id）。
     *
     * **失败时审计行原样留在待审池**（`parsed`），调用方 Toast 报错即可。
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
     * 对应 `NotificationCenterViewModel.insertTransaction`（含 `confirmItem` /
     * `confirmWithEdit`）与 `confirmAll` 的循环体：
     * - 分类由用户在界面上手选，因此 name/icon 由调用方从分类列表里查好带进来；
     * - 批量确认不传分类与标签（保持「批量只认原样入账」）；
     * - **不预测分类、不学习**：学习仍在 `confirmWithEdit` 里显式做（用户主动改了分类才学）；
     * - 无回执。
     *
     * 失败的那几条**仍留在待审池**，由调用方按条数汇报，不要把失败也算进「已确认」。
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
    ) : RecordIntent {
        override val receipt: ReceiptPolicy get() = ReceiptPolicy.NONE
    }

    /**
     * 外部 Intent 静默入账（Tasker / 快捷指令，同签名权限校验在调用方）。
     *
     * 对应 `ExternalActionReceiver`（`auto_confirm` 静默入账分支），其独有的历史写法全部保留：
     * - `source="external_intent"`、`source_detail="tasker_quick_record"`
     *   （Tasker 与通知自动记账的区分依据，丢了服务端就分不出来）；
     * - `client_created_at` 用**无时区的本地日期时间串**（`LocalDateTime.now().toString()`），
     *   与其余路径的 `Instant`（UTC）口径不同——保留，不在本次收口里统一；
     * - **不刷 widget**（其余通知路径都刷）。
     *
     * 安全边界不变：权限检查与 `auto_confirm` 判定仍在接收器的 `onReceive` 里，不在本意图内。
     * 失败只进日志（静默入账没有可承载失败的 UI），**不落库、不发回执**。
     */
    data class ExternalQuickRecord(
        val amount: Int,
        val type: String,
        val description: String?,
    ) : RecordIntent {
        override val receipt: ReceiptPolicy get() = ReceiptPolicy.NONE
    }
}
