package com.aibill.android.domain.notification

/**
 * 预筛：调 AI 之前「这条通知值不值得当账」的唯一判定入口。
 *
 * ## 收口了什么
 *
 * 判据此前漏在三个平台 adapter 里，而且**三套口径不一致**：
 * - NLS 跑 7 步预筛（含「汇总日报」与「促销句式」两道代码层判据）；
 * - SMS 只跑 2 步（支付特征 + 营销词），促销/日报文案会直接送去调 AI；
 * - A11Y 自带一套防抖/冷却，且完全不查上述任何判据。
 * 于是「同一条促销短信从短信 App 进」与「同一条促销推送从 NLS 进」结论相反。
 *
 * 现在三条 adapter 只做**平台翻译**（`StatusBarNotification` / `SmsMessage[]` /
 * `AccessibilityNodeInfo` → [Capture]），判定链与每源状态全部收在本 module 后面。
 *
 * ## 与入账 module 的分工
 *
 * - 本 module 回答「**要不要写**」，实现 `data/notification/NotificationPrefilterImpl`；
 * - 入账 module（[com.aibill.android.domain.record.RecordIntake]）回答「**怎么写**」。
 *
 * 下游（AI 解析、评分窗口、跨渠道去重、`AiResultValidator`、`auth_pending` 重放）
 * 属于 [com.aibill.android.service.NotificationProcessor]，本 module 一律不碰。
 *
 * ## 时间口径
 *
 * 判定链里的所有窗口都以 [Capture.receivedAt] 为「现在」——候选进入本 module 的那一刻。
 * 三个字段同源的意义与入账 module 的 `RecordMoment` 一致：窗口判定与去重键必须落在同一时刻。
 */
interface NotificationPrefilter {

    /**
     * 判定一条候选。
     *
     * 拦截（[Verdict.Block]）一律是**丢弃**：本 module 只回答「值不值得调 AI」，
     * 它不持有任何待审/补偿队列——需要留存的通知在下游由 `auth_pending` 机制负责。
     *
     * suspend 的唯一原因是最后一步 DB 1s 去重（跨渠道共享 `notification_records`）。
     */
    suspend fun evaluate(capture: Capture): Verdict
}

/** 三个平台入口。只影响日志 tag 与熔断的作用域，不影响判据本身。 */
enum class SourceChannel(val logTag: String) {
    NLS("NLS"),
    SMS("SMS"),
    A11Y("A11Y"),
}

/**
 * 一条候选通知：adapter 翻译完平台对象之后的全部判据输入。
 *
 * @param packageName NLS/A11Y 是应用包名，SMS 是 `sms:<发件号码>`（与审计记录同口径）
 * @param title 通知标题（A11Y 固定为「支付成功」）
 * @param fullText 合并后的正文，**所有文本判据都只看它**（NLS 已把标题拼进去）
 * @param receivedAt 到达时刻，同时是判定链的「现在」
 */
data class Capture(
    val channel: SourceChannel,
    val packageName: String,
    val title: String,
    val fullText: String,
    val receivedAt: Long,
)

/** 判定结果。放行时原样回传 [Capture]，调用方不必自己留着。 */
sealed interface Verdict {

    /** 值得当账，交给下游 `NotificationProcessor.process`。 */
    data class Pass(val capture: Capture) : Verdict

    /** 不值得当账。[reason] 供测试与日志定位，[detail] 是人读的现场信息。 */
    data class Block(val reason: BlockReason, val detail: String) : Verdict
}

/**
 * 拦截原因。
 *
 * 每一个都对应真实发生过的一次漏记/刷屏，补一条就要在 [docs/NOTIFICATION.md] 里能找到出处。
 * 新增分支请连同「为什么不能用词表解决」一起写进 KDoc。
 */
enum class BlockReason {
    /** 本 App 自己的通知被自己的监听读回来（自动入库回执 / 待审确认弹窗） */
    SELF_NOTIFICATION,

    /** 内存内容去重：同包名 + 同内容在 5s 内重复投递 */
    DUPLICATE_CONTENT,

    /** 正文为空 */
    BLANK_TEXT,

    /** 系统「正在运行」分组摘要 */
    SYSTEM_GROUP_SUMMARY,

    /** 汇总/日报类通知：金额是区间总计而非单笔交易 */
    SUMMARY_REPORT,

    /** 促销句式：花N得M / 0元兑 / 加赠N */
    PROMO_SENTENCE,

    /** 命中营销词表且不含强交易特征 */
    MARKETING,

    /** 账务判据不通过（per_package / default_rule / 旧硬编码兜底） */
    NOT_FINANCIAL,

    /** 长窗内容记忆：同内容 5 分钟内不重复调 AI */
    AI_MEMORY,

    /** 单包 AI 熔断中（连续失败 N 次），见 [NotificationPrefilterImpl] 的作用域说明 */
    AI_MUTED,

    /** DB 1s 去重命中（同包名 + 同内容刚写过） */
    DB_DUPLICATE,
}
