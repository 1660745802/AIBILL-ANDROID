package com.aibill.android.domain.a11y

import com.aibill.android.domain.notification.Capture

/**
 * A11Y 页面准入状态机：独占「这个无障碍页面事件该不该往下走」的全部判据与全部跨事件状态。
 *
 * ## 两段式主链
 * ```
 * beforeScan(token, kind, now) ──Scan──▶ platform 遍历树 → PageSnapshot ──▶ admit(snapshot, now)
 *        │                                                                        │
 *        └──Throttled（早退，platform 不得遍历）                Captured / Dropped(reason, retry)
 *
 * onRetryDue(token, currentPageTexts, now) ──Reread──▶ platform 重读 → admit()（不走 beforeScan）
 * ```
 *
 * ## 收进 gate
 * G1 8s 内容防抖、G2 页面重发抑制（pageToken + summary 指纹，30s 客户端常量）、
 * G3 3s（仅 CONTENT）、G4 1.5s（全事件）、金额区间 1..10_000_000 分、历史日期切片窗、
 * pending 重试生命周期与 anchor 判定、summary 与 pageText 构造、全部取时（以值传入）。
 *
 * ## 留在 platform
 * 白名单、`isActivityClassName` / `isPotentialPaymentActivity`、节点树遍历、
 * `currentActivity` / `currentPageToken` / `currentWindowId`、500ms Handler、Hilt、
 * AppLogger 与 CaptureFunnelStats 的写入。
 *
 * ## 契约
 * - **单线程**：所有入口只在主线程调用（AccessibilityService 回调与 500ms Handler 都在主线程），
 *   内部状态用普通 `HashMap`/`HashSet`，不加锁。
 * - `now` 由 platform 从 [A11yClock] 一次取快照后传入；gate 不持钟。`admit` 内 G1/G2 共用同一个 `now`。
 * - gate 内**没有** AppLogger / CaptureFunnelStats / 任何回调。
 */
interface A11yAdmissionGate {

    /**
     * phase 1：事件到达、遍历节点树**之前**。
     *
     * 副作用 = 刷新 G3（仅 CONTENT）/ G4（全事件）时间窗、切页作废旧 token 的挂起重试。
     * 返回 [ScanDecision.Throttled] 时 platform **不得遍历树**（保留「先节流后遍历」的早退）。
     */
    fun beforeScan(token: PageToken, kind: PageEventKind, now: A11yMoment): ScanDecision

    /**
     * phase 2：拿到 platform 产出的页面值快照之后。
     *
     * 判定顺序（顺序本身是行为）：
     * 未命中成功词 → 排除词 → 金额缺失(可挂 retry) → 金额解析/区间 → 历史日期
     * → G1 8s 防抖 → G2 页面重发抑制 → Captured。
     *
     * G1 与 G2 共用本次调用的同一个 `now`。重试复用本方法（gate 依内部 `retriedTokens` 决定
     * 是否再挂 retry，调用方不需要传 isRetry）。
     */
    fun admit(snapshot: PageSnapshot, now: A11yMoment): Admission

    /**
     * 重试到点：platform 在 500ms 回调里以**当前值**回调本入口。
     *
     * anchor 判定与 token 时效性都在 gate 内。返回 [RetryOutcome.Reread] 时 platform 重读树 →
     * `admit(snapshot, clock.now())`。本入口**不**经过 [beforeScan]，因此天然绕过 G3/G4，
     * 但随后的 `admit` 仍走 G1（8s 防抖）。
     */
    fun onRetryDue(token: PageToken, currentPageTexts: List<String>, now: A11yMoment): RetryOutcome
}

/** 页面身份：platform 组装（`pkg|windowId|activity`），gate 只当不透明键。 */
@JvmInline
value class PageToken(val value: String)

/** 事件种类。G3（3s 节流）只对 CONTENT 生效；G4（1.5s）对两者都生效。 */
enum class PageEventKind { STATE, CONTENT }

/**
 * platform 一次遍历 + 按 profile 摘取后产出的**定案数据**。
 *
 * **禁止把 lambda / 回调 / `AccessibilityNodeInfo` 传进 gate**——否则状态机无法纯测。
 * gate 只对这份值做判断，不碰节点树。
 */
data class PageSnapshot(
    val token: PageToken,
    /** `Capture.packageName` 用；不从 pageToken 里 parse。 */
    val packageName: String,
    /** `collectAllNodeTexts` 的原始顺序结果（trim、1..50 字、text/desc 同今口径）。 */
    val allTexts: List<String>,
    /** 命中成功词；null = 未命中（不是结果页）。摘取在 platform，判定在 gate。 */
    val successKeyword: String?,
    /** 命中排除词的原文；null = 未命中。 */
    val excludeKeyword: String?,
    /** 首个金额原文；null = 页面尚未渲染出金额。 */
    val amountText: String?,
    /** 商家名；null = gate 用 allTexts 邻近上下文兜底。 */
    val merchant: String?,
)

/** phase 1 输出。 */
sealed interface ScanDecision {
    /** 放行：platform 可以遍历节点树、构造 [PageSnapshot]。 */
    data object Scan : ScanDecision

    /** 早退：窗口内重复。platform 不遍历，直接记 `DROPPED_A11Y_DEBOUNCE`。 */
    data object Throttled : ScanDecision
}

/** phase 2 输出。 */
sealed interface Admission {
    /** 放行：gate 已构好预筛输入（channel=A11Y / title=「支付成功」/ fullText=summary / pageText=去噪整页）。 */
    data class Captured(val capture: Capture) : Admission

    /**
     * 丢弃。[detail] 供 platform 原样写 `A11Y_SKIP` 日志；
     * [retry] 非 null = 「这个页面值得 500ms 后再看一次」，调度由 platform 负责。
     */
    data class Dropped(
        val reason: DropReason,
        val detail: String,
        val retry: RetryPlan? = null,
    ) : Admission
}

/**
 * 页面准入的丢弃原因。platform 按它做**漏斗归因**（穷尽 `when`，不加 else）。
 *
 * 与 `NotificationPrefilter.BlockReason` 是两套：这里管「页面没通过准入」，
 * 那里管「这条通知值不值得当账」。
 */
enum class DropReason {
    /** 未命中支付成功词。 */
    NOT_SUCCESS_PAGE,

    /** 命中排除词。 */
    EXCLUDED,

    /** 命中成功词但金额未渲染（可挂 retry）。 */
    NO_AMOUNT,

    /** 金额原文在，但解析不出整数分。 */
    AMOUNT_UNPARSABLE,

    /** 金额不在 1..10_000_000 分。 */
    AMOUNT_OUT_OF_RANGE,

    /** 金额邻域/前 15 节点出现非今天的日期。 */
    HISTORY_DATE,

    /** G1：同 summary 8s 内。 */
    DEBOUNCE_8S,

    /** G2：同 pageToken + 内容指纹 30s 内（页面重发抑制）。 */
    PAGE_DEDUP_30S,
}

/** gate 决定要重试；platform 负责 `postDelayed`。`delayMs` 是 gate 常量，不是调用方决定。 */
data class RetryPlan(val token: PageToken, val delayMs: Long)

/** 重试入口输出。 */
sealed interface RetryOutcome {
    /** anchor 命中且 token 仍是当前页 → platform 重读树并调 [A11yAdmissionGate.admit]。 */
    data class Reread(val token: PageToken) : RetryOutcome

    data class Abandoned(val reason: RetryAbandonReason) : RetryOutcome
}

/** 重试作废原因（独立于 [DropReason]：中止的重试不是「页面被丢弃」）。 */
enum class RetryAbandonReason {
    /** 该 token 没有挂起的重试。 */
    NOT_PENDING,

    /** 页面已切换。 */
    TOKEN_STALE,

    /** 当前页文本对不上 anchor（页面已变）。 */
    ANCHOR_MISSED,
}
