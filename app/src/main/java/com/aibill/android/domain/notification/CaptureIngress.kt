package com.aibill.android.domain.notification

import kotlinx.coroutines.Job

/**
 * 捕获入管道（C02）：三个平台 adapter（NLS / SMS / A11Y）翻译出 [Capture] 之后的**共同尾巴**。
 *
 * 收口的内容：`prefilter.evaluate → Verdict.Pass → sink.process`，以及**唯一一处**
 * 取消/异常处理（`CancellationException` 重抛、其余异常记日志）。
 *
 * ## 谁拥有 launch
 *
 * [admit] 是 fire-and-forget：内部在进程级 `@ApplicationScope` 上启动，立即返回 [Job]。
 * adapter 不再注入 scope、不再写 try/catch。返回 `Job` 的唯一用途是 SMS 的
 * `goAsync` 生命周期——`onReceive` 需在任务完成后 `pendingResult.finish()`：
 * `ingress.admit(capture).invokeOnCompletion { pendingResult.finish() }`。
 *
 * ## 不拥有什么
 *
 * - **receipt（`RECEIVED_*`）**：留 adapter。它发生在 adapter 早退**之前**，
 *   收进来会让「进 ingress 之前就丢弃」的事件永不计 received，破坏捕获层漏斗顶端。
 * - **平台事实与 [Capture] 组装**：留 adapter（A11Y 场景下由 `A11yAdmissionGate` 产出 Capture）。
 */
interface CaptureIngress {

    /**
     * 接纳一条 [Capture]：记录放行/拦截 → 放行则交给 [NotificationSink]。
     *
     * @return 后台任务的 [Job]（仅供需要生命周期钩子的调用方，如 SMS 的 `goAsync`）。
     */
    fun admit(capture: Capture): Job
}
