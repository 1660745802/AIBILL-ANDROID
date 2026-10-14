package com.aibill.android.domain.notification

/**
 * 捕获层早退的**统一入口**（跨端契约 docs/API.md 附录 C §11.2）。
 *
 * 捕获层（三个平台 adapter 的早退分支）今天只计数：[CaptureFunnelStats.recordDropped]。
 * 归表引入之后，同一个点还要查一次 `prefilter_policy`（键 = [CaptureFunnelStats.reportKey]
 * 派生的 `client_only.capture_dropped_<ch>_<slug>`）：
 *
 * - `block` → 只计数（= 今天的行为）；
 * - `sample` → 计数 + [CaptureSampler] 采一条样本（§11.3）；
 * - `forward` → **P1 在捕获层按 `block` 处理**。理由：捕获层的拦截点要么是
 *   「拿不到内容」（白名单外 / 无 root，转发也没有可解析的输入），要么是**整页**转发
 *   （A11Y 页面级判据），后者交给 `/api/ai/parse` 的成本与噪音都不可控；页面级转发是后续议题。
 *
 * 抽成接口（而不是让 adapter 各查一次表）的理由与预筛一致：**口径只有一处**——
 * 键怎么算、计数是否照旧、采样带什么内容，都在实现里；adapter 只负责把平台事实交出来。
 */
interface CaptureDropHandler {

    /**
     * 记一次捕获层早退。
     *
     * @param packageName 归因用包名（SMS 是 `sms:<号码>`，与审计记录同口径）
     * @param rawText 原文：通知类传通知全文；A11Y 传**整页文本**（采样侧只取命中片段）；
     *   null = 该早退点拿不到内容（大多数结构/平台判据都是这样）
     * @param anchors A11Y 的命中锚点（成功词/排除词/金额），供采样侧取片段
     * @param receivedAt 事件到达时刻（分天限流按它算，同 [CaptureSampleInput.receivedAt]）
     */
    fun onDrop(
        drop: CaptureDrop,
        packageName: String,
        rawText: String? = null,
        anchors: List<String> = emptyList(),
        receivedAt: Long = System.currentTimeMillis(),
    )
}
