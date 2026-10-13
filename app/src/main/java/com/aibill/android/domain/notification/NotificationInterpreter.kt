package com.aibill.android.domain.notification

/**
 * 解析出来的一笔候选交易。
 *
 * 它只描述「AI 说了什么 + 本地校验的结论」，**不含任何去重/入库状态**：
 * 是否会被评分窗口挤出、是否已在 60s 内被别的渠道记过，都由
 * [ScoredAdmission] 判断；写不写得进服务端由入账 module 判断。
 *
 * @param score 同一金额的跨渠道视图之间择优用的信息完整度分
 * @param isComplete 信息是否完整到可以直接入账（缺信息 → 待审池）
 */
data class ParsedTransaction(
    val capture: Capture,
    val amount: Int,
    val type: String,
    val categoryId: Int?,
    val categoryName: String?,
    val categoryIcon: String?,
    val description: String?,
    val score: Int,
    val isComplete: Boolean,
    /**
     * 本次解析在服务端的日志 id（反馈闭环的钥匙，见跨端契约 §1/§2）。
     *
     * null = 没有可反馈对象：被服务端预筛挡下、上游超时等形态不产生 `ai_parse_logs` 行。
     */
    val parseLogId: Long? = null,
)

/**
 * 一次解析尝试的结果。把「没能解析」与「AI 判定不是账」分开：
 * 前者是**不确定**（AI 不可用/异常），后者是 AI 明确的否定。两者都**不进用户视野**，
 * 但原因不同：前者留痕在服务端 `ai_parse_logs`（客户端只计数 + warn），后者连留痕都不需要。
 */
sealed interface ParseOutcome {

    /** AI 给出了可用候选（可能信息不完整，由调用方决定路由）。 */
    data class Parsed(val transaction: ParsedTransaction) : ParseOutcome

    /** AI 明确判定不是账（空 items / amount ≤ 0）→ 丢弃。 */
    data object NotATransaction : ParseOutcome

    /**
     * AI **不可用或输出不可用**（服务端 `outcome=unavailable`：超时 / 上游 502·504 / 格式异常 5002 /
     * 网络异常 / 未知异常）→ **不确定**，丢弃、不打扰用户。
     * ⚠️ 「模型空结果 code=5001」若带 `outcome=not_a_bill` 是**判非账**，不是本态（走 [NotATransaction]）。
     */
    data class Unparsed(val reason: String) : ParseOutcome

    /**
     * 入参被服务端拒（HTTP 400：空 / 超长）→ 丢弃、不打扰用户。
     *
     * 与 [Unparsed] 分开：那是「AI 不可用」（`outcome=unavailable`），这是**客户端请求问题**。
     * 混在一桶会让「客户端失败率 vs 服务端失败率」的交叉核对把客户端多算误读成服务端丢内存
     * （服务端 `docs/API.md` 附录 C §10）。
     */
    data class InvalidInput(val reason: String) : ParseOutcome

    /** 会话过期（401）→ 进待补偿，等同号重登后重放。 */
    data object AuthExpired : ParseOutcome

    /** 设置里关闭了通知 AI 解析 → 不处理（用户的明确选择，不留痕）。 */
    data object Disabled : ParseOutcome
}

/**
 * AI 解析与校验 module：独占「调 AI、[AiResultValidator] 校验、营销后缀截断、
 * 电商描述脱敏、候选评分」这一条策略。
 *
 * 它**不碰**去重、评分窗口、入库、原文留痕——只负责把一条捕获翻译成一个候选，
 * 或明确告诉调用方为什么没有候选。
 *
 * 分类学习命中（[com.aibill.android.domain.usecase.CategoryLearningEngine]）也在这里：
 * 它决定 `categoryId`，属于「解析出什么」而不是「要不要记」。
 */
interface NotificationInterpreter {

    /**
     * 读一条捕获。
     *
     * suspend 的原因只有设置与分类学习的读取（DataStore / 内存表），没有网络以外的副作用。
     * 网络失败不抛出，一律以 [ParseOutcome.Unparsed] / [ParseOutcome.AuthExpired] 返回。
     * [ParseOutcome.Unparsed] 是**不确定**（既非「是账」也非「不是账」）：调用方不做用户可见的留痕，
     * 只计数 + warn，留痕交给服务端 `ai_parse_logs`（PRODUCT §五.2）。
     */
    suspend fun read(capture: Capture): ParseOutcome
}
