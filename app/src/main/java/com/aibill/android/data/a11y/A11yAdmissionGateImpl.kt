package com.aibill.android.data.a11y

import com.aibill.android.domain.a11y.A11yAdmissionGate
import com.aibill.android.domain.a11y.A11yMoment
import com.aibill.android.domain.a11y.A11yRecognition
import com.aibill.android.domain.a11y.Admission
import com.aibill.android.domain.a11y.DropReason
import com.aibill.android.domain.a11y.PageEventKind
import com.aibill.android.domain.a11y.PageSnapshot
import com.aibill.android.domain.a11y.PageToken
import com.aibill.android.domain.a11y.RetryAbandonReason
import com.aibill.android.domain.a11y.RetryOutcome
import com.aibill.android.domain.a11y.RetryPlan
import com.aibill.android.domain.a11y.ScanDecision
import com.aibill.android.domain.notification.Capture
import com.aibill.android.domain.notification.SourceChannel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [A11yAdmissionGate] 的纯 Kotlin 实现：一个不可变状态 + 显式顺序判定链。
 *
 * ## 状态（单线程，主线程串行，不加锁）
 * - `content3s` / `scan15s`：两个节流窗，各自按 TTL 清理过期项（决策 5；G3 现状曾无清理、会泄漏）。
 * - `pageDedup30s`：G2 页面重发抑制，key = `pageToken|summaryHash`（决策 2/3：只抑制「同页同内容」，
 *   交易级同金额判重单一归口下游 ScoredAdmission）。
 * - `lastSummaryHash` / `lastSummaryAt`：G1 全局单槽 8s 防抖（保留今行为）。
 * - `pendingRetries` / `retriedTokens`：重试生命周期（决策 4：每页至多挂一次，`retriedTokens`
 *   取代 `isRetry` 布尔）。
 * - `currentToken`：当前页；切页作废旧 token 的挂起重试。
 *
 * 时间全部以 [A11yMoment] 值传入，gate 不持钟。
 */
@Singleton
class A11yAdmissionGateImpl @Inject constructor() : A11yAdmissionGate {

    private var currentToken: PageToken? = null

    private val content3s = HashMap<PageToken, Long>()
    private val scan15s = HashMap<PageToken, Long>()
    private val pageDedup30s = HashMap<String, Long>()

    private var lastSummaryHash: Int = 0
    private var lastSummaryAt: Long = 0L

    private val pendingRetries = HashMap<PageToken, Set<String>>()
    private val retriedTokens = HashSet<PageToken>()

    override fun beforeScan(token: PageToken, kind: PageEventKind, now: A11yMoment): ScanDecision {
        val t = now.epochMillis
        if (currentToken != token) {
            currentToken = token
            pendingRetries.keys.removeIf { it != token }
            retriedTokens.removeIf { it != token }
        }
        // G3：3s 内容节流，仅 CONTENT（STATE 不参与）
        if (kind == PageEventKind.CONTENT) {
            content3s.entries.removeIf { t - it.value >= CONTENT_TTL_MS }
            val last = content3s[token]
            if (last != null && t - last < CONTENT_TTL_MS) return ScanDecision.Throttled
            content3s[token] = t
        }
        // G4：1.5s 全树扫描节流，全事件
        scan15s.entries.removeIf { t - it.value >= SCAN_TTL_MS }
        val lastScan = scan15s[token]
        if (lastScan != null && t - lastScan < SCAN_TTL_MS) return ScanDecision.Throttled
        scan15s[token] = t
        return ScanDecision.Scan
    }

    override fun admit(snapshot: PageSnapshot, now: A11yMoment): Admission {
        val t = now.epochMillis
        val token = snapshot.token

        val successKeyword = snapshot.successKeyword
            ?: return Admission.Dropped(DropReason.NOT_SUCCESS_PAGE, "未命中支付成功词")

        snapshot.excludeKeyword?.let {
            return Admission.Dropped(DropReason.EXCLUDED, "排除词命中: $it")
        }

        val amountText = snapshot.amountText
        if (amountText == null) {
            // 命中成功词但页面还没渲染出金额 → 首次挂一次重试；重试后仍无 → 终态。
            val alreadyTried = token in retriedTokens || token in pendingRetries
            if (alreadyTried) {
                return Admission.Dropped(DropReason.NO_AMOUNT, "重试后仍无金额")
            }
            val anchor = A11yRecognition.retryAnchor(snapshot.allTexts, successKeyword)
            pendingRetries[token] = anchor
            return Admission.Dropped(
                DropReason.NO_AMOUNT,
                "有成功词但无金额，已挂重试",
                RetryPlan(token, RETRY_DELAY_MS),
            )
        }

        val cents = A11yRecognition.amountKey(amountText)?.toIntOrNull()
            ?: return Admission.Dropped(DropReason.AMOUNT_UNPARSABLE, "金额解析失败: raw=$amountText")
        if (cents < MIN_AMOUNT_CENTS || cents > MAX_AMOUNT_CENTS) {
            return Admission.Dropped(DropReason.AMOUNT_OUT_OF_RANGE, "金额异常($cents): raw=$amountText")
        }

        if (!A11yRecognition.isRecentPayment(snapshot.allTexts, amountText, now.today)) {
            return Admission.Dropped(DropReason.HISTORY_DATE, "历史日期拦截: amount=$amountText")
        }

        val merchant = snapshot.merchant
        val summary = if (merchant != null) {
            "$ITEM_TITLE $amountText $merchant"
        } else {
            "$ITEM_TITLE $amountText ${A11yRecognition.collectNearbyText(snapshot.allTexts, amountText)}"
        }

        // G1：同 summary 8s 内不重复（全局单槽，保留今行为）。命中与未命中都会刷新槽位。
        val hash = summary.hashCode()
        if (hash == lastSummaryHash && (t - lastSummaryAt) < DEBOUNCE_TTL_MS) {
            return Admission.Dropped(DropReason.DEBOUNCE_8S, "防抖拦截(${DEBOUNCE_TTL_MS / 1000}s): $amountText")
        }
        lastSummaryHash = hash
        lastSummaryAt = t

        // G2：同页同内容 30s 内只放一次（页面重发抑制；不承担交易判重）。
        // key 用完整 summary 串（非 32 位 hash），避免不同交易哈希碰撞被误判为重发。
        val dedupKey = "${token.value}|$summary"
        pageDedup30s.entries.removeIf { t - it.value >= PAGE_DEDUP_TTL_MS }
        val lastPay = pageDedup30s[dedupKey]
        if (lastPay != null && (t - lastPay) < PAGE_DEDUP_TTL_MS) {
            return Admission.Dropped(
                DropReason.PAGE_DEDUP_30S,
                "页面重发抑制(${PAGE_DEDUP_TTL_MS / 1000}s 同页同内容)",
            )
        }
        pageDedup30s[dedupKey] = t

        return Admission.Captured(
            Capture(
                channel = SourceChannel.A11Y,
                packageName = snapshot.packageName,
                title = ITEM_TITLE,
                fullText = summary,
                receivedAt = t,
                pageText = A11yRecognition.buildPageText(snapshot.allTexts),
            )
        )
    }

    override fun onRetryDue(token: PageToken, currentPageTexts: List<String>, now: A11yMoment): RetryOutcome {
        // 先判 token 时效（对齐今 `currentPageToken == pageToken` 守卫），再判是否真的挂起过。
        if (token != currentToken) {
            pendingRetries.remove(token)
            return RetryOutcome.Abandoned(RetryAbandonReason.TOKEN_STALE)
        }
        val anchor = pendingRetries[token] ?: return RetryOutcome.Abandoned(RetryAbandonReason.NOT_PENDING)
        if (!A11yRecognition.matchesRetryAnchor(anchor, currentPageTexts)) {
            // 放弃也要清挂起态：否则该 token 在 admit 里永远算「已试过」，再也不挂重试。
            pendingRetries.remove(token)
            return RetryOutcome.Abandoned(RetryAbandonReason.ANCHOR_MISSED)
        }
        pendingRetries.remove(token)
        retriedTokens.add(token)
        return RetryOutcome.Reread(token)
    }

    override fun reset() {
        currentToken = null
        content3s.clear()
        scan15s.clear()
        pageDedup30s.clear()
        lastSummaryHash = 0
        lastSummaryAt = 0L
        pendingRetries.clear()
        retriedTokens.clear()
    }

    private companion object {
        /** G1 内容防抖：同 summary 8s。 */
        const val DEBOUNCE_TTL_MS = 8_000L

        /** G3 内容节流：仅 CONTENT。 */
        const val CONTENT_TTL_MS = 3_000L

        /** G4 全树扫描节流：全事件。 */
        const val SCAN_TTL_MS = 1_500L

        /** G2 页面重发抑制：同页同内容 30s（客户端常量；云控 cooldownMinutes 待删）。 */
        const val PAGE_DEDUP_TTL_MS = 30_000L

        /** 金额区间：0.01 元 ~ 100,000.00 元。 */
        const val MIN_AMOUNT_CENTS = 1
        const val MAX_AMOUNT_CENTS = 10_000_000

        /** 延迟重试间隔（platform 侧 postDelayed 用）。 */
        const val RETRY_DELAY_MS = 500L

        /** 无障碍渠道产出的摘要标题：与页面上「支付成功」一致，也是判据链的 title 输入。 */
        const val ITEM_TITLE = "支付成功"
    }
}
