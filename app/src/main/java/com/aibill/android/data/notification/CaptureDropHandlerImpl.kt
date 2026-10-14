package com.aibill.android.data.notification

import com.aibill.android.domain.notification.BlockAction
import com.aibill.android.domain.notification.CaptureDrop
import com.aibill.android.domain.notification.CaptureDropHandler
import com.aibill.android.domain.notification.CaptureFunnelStats
import com.aibill.android.domain.notification.CaptureKind
import com.aibill.android.domain.notification.CaptureSampleInput
import com.aibill.android.domain.notification.CaptureSampler
import com.aibill.android.domain.notification.CaptureStage
import com.aibill.android.domain.notification.NotificationRuleSource
import com.aibill.android.domain.notification.SourceChannel
import com.aibill.android.util.AppLogger
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [CaptureDropHandler] 的实现：**先计数（口径不变）再查归表决定是否采样**。
 *
 * 三件事刻意分开：
 *
 * 1. **计数照旧**：`block` 与 `sample` 都记一次 [CaptureFunnelStats.recordDropped]——
 *    归表只决定「要不要把内容样本送出去」，不改变漏斗计数口径（否则历史曲线立刻断掉）；
 * 2. **查表键复用上报键**（[CaptureFunnelStats.reportKey]）：与服务端 `prefilter_policy`
 *    的闭集**逐字同源**，不另起名字；
 * 3. **`forward` 视作 `block`**（契约 §11.7 P1）：捕获层转发成本高（整页 / 无内容），
 *    首次出现时 log 一次，免得运维以为配了 `forward` 就生效了。
 */
@Singleton
class CaptureDropHandlerImpl @Inject constructor(
    private val stats: CaptureFunnelStats,
    private val ruleSource: NotificationRuleSource,
    private val sampler: CaptureSampler,
    private val appLogger: AppLogger,
) : CaptureDropHandler {

    /** 已经提示过「捕获层不支持 forward」的 rule_id（每进程每规则一次）。 */
    private val forwardWarned = ConcurrentHashMap.newKeySet<String>()

    override fun onDrop(
        drop: CaptureDrop,
        packageName: String,
        rawText: String?,
        anchors: List<String>,
        receivedAt: Long,
    ) {
        stats.recordDropped(drop)

        val ruleId = CaptureFunnelStats.reportKey(CaptureFunnelStats.droppedStage(drop))
        when (ruleSource.snapshot().actionFor(ruleId)) {
            BlockAction.BLOCK -> Unit
            BlockAction.FORWARD -> {
                if (forwardWarned.add(ruleId)) {
                    appLogger.warn(
                        "SAMPLER",
                        "捕获层不支持 forward（P1 视作 block，页面级/无内容转发成本高）: $ruleId",
                    )
                }
            }
            BlockAction.SAMPLE -> sampler.record(
                CaptureSampleInput(
                    channel = drop.channel,
                    packageName = packageName,
                    // A11Y 一律按「页面」形态采样（不采整页，只取命中片段）；其余是通知
                    kind = if (drop.channel == SourceChannel.A11Y) CaptureKind.A11Y_PAGE else CaptureKind.NOTIFICATION,
                    stage = CaptureStage.CAPTURE,
                    blockedBy = ruleId,
                    receivedAt = receivedAt,
                    rawText = rawText,
                    anchors = anchors,
                ),
            )
        }
    }
}
