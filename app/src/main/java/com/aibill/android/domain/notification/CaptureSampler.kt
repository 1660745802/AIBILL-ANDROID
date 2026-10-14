package com.aibill.android.domain.notification

/**
 * 被拦内容采样（跨端契约 docs/API.md 附录 C §11.3 / §11.7）。
 *
 * ## 为什么需要它
 *
 * 客户端在本地丢掉的 A11Y 页面/通知内容，服务端**只收到聚合计数**（§4 `filter-report`），
 * **看不到内容**。于是「哪些通知/页面可以安全排除」没有证据，规则只能盲维护。
 * 采样把「被拦的是什么」交给服务端，闭环才可能从「规则可见」走到「规则可改」。
 *
 * ## 两条硬边界（P1）
 *
 * - **绝不写账、绝不调 AI、绝不回推客户端**——采样只上行一条样本；
 * - **采集 ≠ 找回**：被拦的那笔仍然没记上，补账要另走「候选池 → 用户确认」。
 *
 * ## 触发点只有一个
 *
 * 只有归表（[PrefilterPolicy]）给出 [BlockAction.SAMPLE] 的拦截点才采样；
 * [BlockAction.BLOCK] 只计数、[BlockAction.FORWARD] 走转发（服务端预筛自己会落原文）。
 *
 * 实现必须 best-effort、非阻塞、线程安全：它被 NLS/SMS/A11Y 与预筛四条路径并发调用。
 */
interface CaptureSampler {

    /**
     * 记一条样本（原始内容；由实现按 §11.3 转成待发的 [CaptureSample]）。
     *
     * 实现内部完成：去重（同 `(content_hash, blocked_by)` 本窗口一次）、
     * 每天限流（每 `(package, rule)` [MAX_PER_RULE_PER_DAY] 条、每用户
     * [MAX_PER_USER_PER_DAY] 条）、内容截断/片段化。超限/重复一律静默丢弃（计数 + 日志）。
     */
    fun record(input: CaptureSampleInput)

    /**
     * 取走当前窗口的待发样本并**开始新窗口**（清空窗口内去重集合）。
     *
     * 与 [PrefilterStats.drain] 同语义：发送失败由上报侧保留重发，采样侧不负责重试。
     */
    fun drain(): List<CaptureSample>

    companion object {
        /** 每天每个 `(package, rule)` 的样本上限（契约 §11.8）。 */
        const val MAX_PER_RULE_PER_DAY = 20

        /** 每天每用户的样本总量上限（契约 §11.8）。 */
        const val MAX_PER_USER_PER_DAY = 200

        /** 单条 `text` 上限（服务端 zod 硬约束：>2000 → 400）。 */
        const val MAX_TEXT_LEN = 2000

        /** a11y 片段前后各取的字数（契约 §11.8「命中片段（前后各 ~30 字）」）。 */
        const val FRAGMENT_PADDING = 30
    }
}

/** 样本形态（契约 §11.3）：通知正文 / 无障碍页面。 */
enum class CaptureKind(val wire: String) {
    NOTIFICATION("notification"),
    A11Y_PAGE("a11y_page"),
}

/** 在哪一层丢的（契约 §11.3）：捕获层早退 / 预筛判据拦下。 */
enum class CaptureStage(val wire: String) {
    CAPTURE("capture"),
    PREFILTER("prefilter"),
}

/**
 * 采样**输入**：原始内容 + 归因。实现负责把它变成可上报的 [CaptureSample]。
 *
 * @param blockedBy 触发的规则 id（= 查归表用的那个键，键口径与服务端同源）
 * @param rawText 原文。`notification` = 通知全文；`a11y_page` = **整页文本**（实现只取命中片段）。
 *   null = 该拦截点拿不到内容（如 A11Y 白名单外/无 root，早于任何取文本动作）
 * @param anchors a11y_page 的命中锚点（成功词/排除词/金额），用于取片段；notification 忽略
 */
data class CaptureSampleInput(
    val channel: SourceChannel,
    val packageName: String,
    val kind: CaptureKind,
    val stage: CaptureStage,
    val blockedBy: String,
    val receivedAt: Long,
    val rawText: String? = null,
    val anchors: List<String> = emptyList(),
    val tier: Int? = null,
)

/**
 * 一条待发样本（已按 §11.3 处理完内容）。
 *
 * @param text 按形态处理后的文本：notification = 原文（≤[CaptureSampler.MAX_TEXT_LEN] 截断）；
 *   a11y_page = 命中片段（不采整页）。null = 该点没有内容可采
 * @param textLen **原文**长度（a11y 是整页长度，不是片段长度）——服务端用它衡量丢弃规模
 * @param contentHash `sha256:<hex>`，对 [text] 取（服务端拿它做 `(content_hash, blocked_by)` 幂等去重，
 *   所以指纹必须覆盖**实际发出的文本**）
 * @param redacted P1 恒 false（契约 §11.5：信息收集期默认采原文；P2 硬化）
 */
data class CaptureSample(
    val channel: SourceChannel,
    val packageName: String,
    val kind: CaptureKind,
    val stage: CaptureStage,
    val blockedBy: String,
    val receivedAt: Long,
    val text: String?,
    val textLen: Int,
    val contentHash: String,
    val tier: Int? = null,
    val redacted: Boolean = false,
)
