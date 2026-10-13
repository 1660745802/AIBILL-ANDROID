package com.aibill.android.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.aibill.android.domain.a11y.A11yAdmissionGate
import com.aibill.android.domain.a11y.A11yClock
import com.aibill.android.domain.a11y.A11yMoment
import com.aibill.android.domain.a11y.A11yRecognition
import com.aibill.android.domain.a11y.Admission
import com.aibill.android.domain.a11y.DropReason
import com.aibill.android.domain.a11y.PageEventKind
import com.aibill.android.domain.a11y.PageSnapshot
import com.aibill.android.domain.a11y.PageToken
import com.aibill.android.domain.a11y.RetryOutcome
import com.aibill.android.domain.a11y.RetryPlan
import com.aibill.android.domain.a11y.ScanDecision
import com.aibill.android.domain.notification.A11yGlobalProfile
import com.aibill.android.domain.notification.A11yPackageProfile
import com.aibill.android.domain.notification.A11yProfileResolver
import com.aibill.android.domain.notification.CaptureFunnelStats
import com.aibill.android.domain.notification.NotificationPrefilter
import com.aibill.android.domain.notification.NotificationRuleSource
import com.aibill.android.domain.notification.Verdict
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * A11Y 平台的**纯文本判据**（列表页启发式、页面身份、成功词精确匹配、排除词包含）。
 *
 * C01 收口：准入状态机已搬到 [A11yAdmissionGate]（domain）+ `data/a11y` 实现；
 * 需要纯判据的 `amountKey` / 历史日期 / 重试锚点等已搬到 `domain/a11y/A11yRecognition`，
 * 这里保留原签名并**委托**过去，避免两份实现（调用方与单测不必改）。
 *
 * 留下的都是「平台事实」：页面身份（pageToken）、root 是否同包、成功词精确匹配、
 * 排除词包含、列表页启发式（其调用方在下游预筛 `NotificationPrefilterImpl`）。
 */
internal object PaymentAccessibilityRecognition {

    fun pageToken(packageName: String, className: String, windowId: Int): String =
        "${packageName.trim().lowercase()}|$windowId|${className.trim().lowercase()}"

    fun pageState(packageName: String, className: String, windowId: Int): Pair<String, String> =
        className to pageToken(packageName, className, windowId)

    /** 见 `A11yRecognition` 对应 KDoc：仅跨包才拒绝，刻意不比较 windowId。 */
    fun isRootForEvent(rootPackage: String?, eventPackage: String): Boolean =
        rootPackage == eventPackage

    /** 金额文本 → 整数分。委托 `domain/a11y/A11yRecognition`。 */
    fun amountKey(amountText: String): String? = A11yRecognition.amountKey(amountText)

    fun isSuccessKeywordMatch(text: String, keyword: String): Boolean {
        val value = text.trim()
        if (value == keyword) return true
        if (value.length > keyword.length * 3 || !value.contains(keyword)) return false
        val suffix = value.substring(value.indexOf(keyword) + keyword.length)
        if (suffix.isBlank()) return true
        if (suffix == "了" || suffix.startsWith("了 ") || suffix.startsWith("了，") ||
            suffix.startsWith("了。") || suffix.startsWith("了！")
        ) {
            return true
        }
        return suffix.first().isWhitespace() || suffix.first() in "!！。,.，:：¥￥(（[【"
    }

    fun retryAnchor(texts: List<String>, successKeyword: String): Set<String> =
        A11yRecognition.retryAnchor(texts, successKeyword)

    fun matchesRetryAnchor(anchor: Set<String>, currentTexts: List<String>): Boolean =
        A11yRecognition.matchesRetryAnchor(anchor, currentTexts)

    fun paymentPackages(embeddedPackages: List<String>): Set<String> =
        setOf("com.tencent.mm", "com.eg.android.AlipayGphone") +
            embeddedPackages.asSequence().map(String::trim).filter(String::isNotBlank).toSet()

    /**
     * 页面冷却 TTL。C01 起 gate 用固定 30s 客户端常量、不再读云控 cooldownMinutes；
     * 本函数暂留（旧单测引用），实现层已不使用。
     */
    fun dedupTtl(cooldownMs: Long): Long = minOf(cooldownMs, 30_000L)

    fun containsExcludeKeyword(texts: List<String>, keywords: List<String>): Boolean =
        keywords.any { keyword -> keyword.isNotBlank() && texts.any { it.contains(keyword) } }

    /** 历史日期判据。委托 `domain/a11y/A11yRecognition`。 */
    fun isRecentTextRange(texts: List<String>, today: java.time.LocalDate): Boolean =
        A11yRecognition.isRecentTextRange(texts, today)

    /** 列表页「金额节点」数下限：真实支付结果页最多 2 个（实付 + 原价/优惠）。 */
    const val LIST_PAGE_MIN_AMOUNTS = 3

    /** 列表词必须出现在 `pageText` 前 N 个字符内（页面标题区）。见原 KDoc。 */
    const val LIST_PAGE_KEYWORD_HEAD_CHARS = A11yProfileResolver.DEFAULT_LIST_PAGE_HEAD_CHARS

    /** 列表页启发式的内置兜底列表词（7 个）。 */
    val DEFAULT_LIST_KEYWORDS = listOf("账单", "交易记录", "明细", "全部交易", "收支记录", "历史", "月账单")

    /**
     * 列表页金额计数专用正则：覆盖 `¥12.00` / `12.00元` 与 `1,000元` 千分位。
     * 与云控 `a11y.amount_regex` 解耦——这是纯客户端页面结构判据，不看单笔金额语义。
     */
    val LIST_PAGE_AMOUNT_REGEX = Regex("""[¥￥][ \t\n\x0B\f\r\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000\ufeff]*\d[\d,]*(?:\.\d{1,2})?|\d[\d,]*(?:\.\d{1,2})?[ \t\n\x0B\f\r\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000\ufeff]*元""")

    /** 统计一组文本里像金额的出现次数（同一条文本里有多个则累计）。 */
    fun countAmountLikeTexts(texts: List<String>, amountRegex: Regex): Int =
        texts.sumOf { text -> amountRegex.findAll(text).count() }

    /**
     * 列表页启发式：金额数 ≥ [minAmounts] 且列表词出现在页面前 [LIST_PAGE_KEYWORD_HEAD_CHARS]
     * 个字符内（标题区）→ 判为账单/明细页。取舍：宁可漏挡，不可误杀真账。
     */
    fun looksLikeListPage(
        pageText: String,
        amountRegex: Regex,
        listKeywords: List<String>,
        minAmounts: Int = LIST_PAGE_MIN_AMOUNTS,
        headChars: Int = LIST_PAGE_KEYWORD_HEAD_CHARS,
    ): Boolean {
        val head = pageText.take(headChars)
        if (listKeywords.none { it.isNotBlank() && head.contains(it) }) return false
        return countAmountLikeTexts(listOf(pageText), amountRegex) >= minAmounts
    }
}

/**
 * 支付页面无障碍识别服务——**平台翻译 adapter**（C01 后）。
 *
 * 本类只剩平台事实：白名单、事件分类、Activity 跟踪、root 校验、树遍历 + profile 摘取、
 * 500ms Handler 调度、漏斗计数与日志、预筛/落库接线。
 * 全部「这个页面该不该放行」的判据与时间窗状态都在 [A11yAdmissionGate] 后面。
 *
 * 调用链：
 * ```
 * 事件 → 白名单 → 分类(STATE/CONTENT) → 页面 token → root 校验
 *      → gate.beforeScan（节流，Throttled 则不遍历）
 *      → 遍历树 + 摘取 → PageSnapshot → gate.admit → Captured → 预筛 / Dropped → 漏斗 + 调度重试
 * ```
 */
class PaymentAccessibilityService : AccessibilityService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface A11yEntryPoint {
        fun notificationProcessor(): NotificationProcessor
        fun prefilter(): NotificationPrefilter
        fun appLogger(): com.aibill.android.util.AppLogger
        fun rulesManager(): NotificationRulesManager
        fun ruleSource(): NotificationRuleSource
        fun captureFunnelStats(): CaptureFunnelStats
        fun a11yAdmissionGate(): A11yAdmissionGate
        fun a11yClock(): A11yClock
        @com.aibill.android.di.ApplicationScope
        fun applicationScope(): kotlinx.coroutines.CoroutineScope
    }

    private lateinit var notificationProcessor: NotificationProcessor
    private lateinit var prefilter: NotificationPrefilter
    private lateinit var appLogger: com.aibill.android.util.AppLogger
    private lateinit var rulesManager: NotificationRulesManager
    private lateinit var ruleSource: NotificationRuleSource
    private lateinit var captureFunnelStats: CaptureFunnelStats
    private lateinit var gate: A11yAdmissionGate
    private lateinit var clock: A11yClock

    private lateinit var serviceScope: kotlinx.coroutines.CoroutineScope
    private val handler = Handler(Looper.getMainLooper())

    // ── 平台事实：当前页面状态（gate 只看 pageToken） ──
    private val currentActivity = ConcurrentHashMap<String, String>()
    private val currentPageToken = ConcurrentHashMap<String, String>()
    private val currentWindowId = ConcurrentHashMap<String, Int>()

    // ── 从云控规则读取（每次事件时按代际刷新） ──
    private var embeddedPaymentApps: Set<String> = emptySet()
    private var paymentApps: Set<String> = emptySet()
    private var a11yGlobal: A11yGlobalProfile = A11yGlobalProfile()
    private var a11yProfiles: Map<String, A11yPackageProfile> = emptyMap()
    private var lastRulesGeneration: Int = -1

    companion object {
        private const val PACKAGE_WECHAT = "com.tencent.mm"
        private const val PACKAGE_ALIPAY = "com.eg.android.AlipayGphone"

        /** 无 profile 的包走全局默认：不触发回退告警。 */
        private val NO_PROFILE_WARN = A11yProfileResolver.Warn { _, _, _ -> }

        /** 内嵌 App 中可能是支付结果页的 Activity 类名关键词。 */
        private val PAYMENT_ACTIVITY_HINTS = listOf(
            "pay", "payment", "cashier", "order", "result", "success",
            "trade", "checkout", "confirm", "receipt", "bill", "transaction",
        )

        /**
         * 服务是否已成功连接到无障碍框架。国内 ROM 常在后台断开连接但设置开关仍是开的，
         * [A11yHealthCheckWorker] 用它做辅助信号。进程被杀时随进程消失。
         */
        @Volatile
        @JvmStatic
        var isServiceConnected: Boolean = false
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceConnected = true
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, A11yEntryPoint::class.java)
        notificationProcessor = entryPoint.notificationProcessor()
        prefilter = entryPoint.prefilter()
        appLogger = entryPoint.appLogger()
        rulesManager = entryPoint.rulesManager()
        ruleSource = entryPoint.ruleSource()
        captureFunnelStats = entryPoint.captureFunnelStats()
        gate = entryPoint.a11yAdmissionGate()
        clock = entryPoint.a11yClock()
        serviceScope = entryPoint.applicationScope()

        refreshRulesIfNeeded(force = true)
        appLogger.info("A11Y", "无障碍服务已连接 (v5: gate 收口)")
    }

    private fun refreshRulesIfNeeded(force: Boolean = false) {
        val snapshot = rulesManager.getSnapshot()
        if (!force && snapshot.generation == lastRulesGeneration) return
        val rules = snapshot.rules
        embeddedPaymentApps = rules.a11y.embeddedPaymentApps.map(String::trim).filter(String::isNotBlank).toSet()
        paymentApps = PaymentAccessibilityRecognition.paymentPackages(rules.a11y.embeddedPaymentApps)
        // A11Y 摘取策略：schema 守卫 + 逐键回退在快照层做完（与预筛共用）。
        val a11ySnapshot = ruleSource.snapshot()
        a11yGlobal = a11ySnapshot.a11yGlobalProfile
        a11yProfiles = a11ySnapshot.a11yPackageProfiles
        lastRulesGeneration = snapshot.generation
        serviceInfo = serviceInfo?.apply { packageNames = paymentApps.toTypedArray() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val ev = event ?: return
        val packageName = ev.packageName?.toString() ?: return
        captureFunnelStats.record(CaptureFunnelStats.RECEIVED_A11Y)

        refreshRulesIfNeeded()
        if (packageName !in paymentApps) {
            captureFunnelStats.record(CaptureFunnelStats.DROPPED_A11Y_NOT_WHITELISTED)
            return
        }

        val kind = when (ev.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> PageEventKind.STATE
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> PageEventKind.CONTENT
            else -> return
        }

        // ── 页面身份：STATE 由本事件决定；CONTENT 用已有当前页 ──
        val activity: String
        val token: PageToken
        if (kind == PageEventKind.STATE) {
            val className = ev.className?.toString() ?: ""
            activity = if (isActivityClassName(className)) {
                currentActivity[packageName] = className
                className
            } else {
                currentActivity[packageName] ?: className
            }
            currentWindowId[packageName] = ev.windowId
            val tokenStr = PaymentAccessibilityRecognition.pageToken(packageName, activity, ev.windowId)
            currentPageToken[packageName] = tokenStr
            token = PageToken(tokenStr)
        } else {
            activity = currentActivity[packageName]
                ?: ev.className?.toString()?.takeIf { isActivityClassName(it) }?.also {
                    currentActivity[packageName] = it
                }
                ?: run {
                    captureFunnelStats.record(CaptureFunnelStats.DROPPED_A11Y_NO_ACTIVITY)
                    return
                }
            if (!isPotentialPaymentActivity(activity, packageName)) {
                captureFunnelStats.record(CaptureFunnelStats.DROPPED_A11Y_NOT_PAYMENT_ACTIVITY)
                return
            }
            val tokenStr = currentPageToken[packageName]
            if (tokenStr == null) {
                captureFunnelStats.record(CaptureFunnelStats.DROPPED_A11Y_NO_PAGE_TOKEN)
                return
            }
            token = PageToken(tokenStr)
        }

        // ── root 校验（平台事实；在节流与遍历之前） ──
        val root = rootInActiveWindow
        if (root == null) {
            captureFunnelStats.record(CaptureFunnelStats.DROPPED_A11Y_NO_ROOT)
            return
        }
        if (!PaymentAccessibilityRecognition.isRootForEvent(root.packageName?.toString(), packageName)) {
            captureFunnelStats.record(CaptureFunnelStats.DROPPED_A11Y_ROOT_MISMATCH)
            return
        }

        // ── phase 1：节流（返回 Throttled 则不遍历树） ──
        val now = clock.now()
        if (gate.beforeScan(token, kind, now) == ScanDecision.Throttled) {
            captureFunnelStats.record(CaptureFunnelStats.DROPPED_A11Y_DEBOUNCE)
            return
        }

        processSnapshot(root, packageName, activity, token, now)
    }

    /** 判断类名是否是一个 Activity（而非 View/Dialog 等）。 */
    private fun isActivityClassName(className: String): Boolean {
        if (className.isBlank()) return false
        val lower = className.lowercase()
        if (lower.startsWith("android.widget.") || lower.startsWith("android.view.")) return false
        return lower.contains("activity") || lower.contains("fragment")
                || lower.contains("home") || lower.contains("appbrandui")
                || lower.contains("welcome") || lower.contains("launcher")
                || lower.contains("main") || lower.contains("page")
    }

    /** 未命中成功词那行汇总日志的诊断字段，不参与判定。 */
    private fun hasAmountLikeText(allTexts: List<String>, regex: Regex): Boolean =
        allTexts.any { regex.containsMatchIn(it) }

    /** 内嵌 App 只在可能支付/订单页响应 CONTENT_CHANGED；微信/支付宝任意容器页都算。 */
    private fun isPotentialPaymentActivity(activity: String, packageName: String): Boolean {
        if (packageName == PACKAGE_WECHAT || packageName == PACKAGE_ALIPAY) return true
        val lower = activity.lowercase()
        return PAYMENT_ACTIVITY_HINTS.any { lower.contains(it) }
    }

    /**
     * 一次遍历 + 按 profile 摘取 → [PageSnapshot] → 调 gate.admit → 路由。
     */
    private fun processSnapshot(
        rootNode: AccessibilityNodeInfo,
        packageName: String,
        activity: String,
        token: PageToken,
        now: A11yMoment,
    ) {
        val allTexts = mutableListOf<String>()
        collectAllNodeTexts(rootNode, allTexts)

        val isEmbeddedApp = packageName in embeddedPaymentApps
        val policy = a11yProfiles[packageName]
            ?: A11yProfileResolver.resolve(packageName, isEmbeddedApp, null, a11yGlobal, NO_PROFILE_WARN)

        val successKeyword = findMatchedKeyword(rootNode, policy.successKeywords)
        val excludeKeyword = policy.excludeKeywords.firstOrNull { keyword ->
            keyword.isNotBlank() && allTexts.any { it.contains(keyword) }
        }
        val amountText = findAmount(rootNode, policy.amountRegex)
        val merchant = findMerchant(rootNode, policy.merchantLabels)

        if (successKeyword == null) {
            val hasAmount = hasAmountLikeText(allTexts, policy.amountRegex)
            appLogger.debug(
                "A11Y_SKIP",
                "未命中支付成功词: pkg=$packageName class=${activity.substringAfterLast('.')} " +
                    "texts=${allTexts.size} hasAmount=$hasAmount",
            )
        } else {
            appLogger.debug(
                "A11Y_PAGE",
                "[$packageName/${activity.substringAfterLast('.')}] texts=${allTexts.size} " +
                    allTexts.take(20).joinToString("|"),
            )
        }

        val snapshot = PageSnapshot(
            token = token,
            packageName = packageName,
            allTexts = allTexts,
            successKeyword = successKeyword,
            excludeKeyword = excludeKeyword,
            amountText = amountText,
            merchant = merchant,
        )

        when (val admission = gate.admit(snapshot, now)) {
            is Admission.Captured -> routeToPrefilter(admission, packageName)
            is Admission.Dropped -> {
                captureFunnelStats.record(funnelStage(admission.reason))
                appLogger.debug("A11Y_SKIP", admission.detail)
                admission.retry?.let { scheduleRetry(packageName, it) }
            }
        }
    }

    /** gate 放行 → 预筛 → 入账协调者（与 NLS/SMS 同一套判据）。 */
    private fun routeToPrefilter(admission: Admission.Captured, packageName: String) {
        appLogger.info("A11Y", "✓识别支付页: ${admission.capture.fullText} pkg=$packageName")
        serviceScope.launch {
            try {
                when (val verdict = prefilter.evaluate(admission.capture)) {
                    is Verdict.Block -> appLogger.debug("A11Y_SKIP", "预筛拦截(${verdict.reason}): pkg=$packageName")
                    is Verdict.Pass -> notificationProcessor.process(verdict.capture)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                appLogger.error("A11Y", "支付页处理异常: pkg=$packageName err=${e.message}")
            }
        }
    }

    /**
     * 延迟重试：`plan.delayMs` 后重新获取 rootNode，把「当前页 token + 当前页文本」当值回传 gate。
     * 重试**决定**在 gate（token 是否仍是当前页 / anchor 是否匹配），这里只做调度。
     */
    private fun scheduleRetry(packageName: String, plan: RetryPlan) {
        handler.postDelayed({
            val retryRoot = rootInActiveWindow ?: return@postDelayed
            if (!PaymentAccessibilityRecognition.isRootForEvent(retryRoot.packageName?.toString(), packageName)) {
                return@postDelayed
            }
            val currentTexts = mutableListOf<String>()
            collectAllNodeTexts(retryRoot, currentTexts)
            val now = clock.now()
            when (val outcome = gate.onRetryDue(plan.token, currentTexts, now)) {
                is RetryOutcome.Reread -> {
                    val activity = currentActivity[packageName] ?: return@postDelayed
                    processSnapshot(retryRoot, packageName, activity, outcome.token, now)
                }
                is RetryOutcome.Abandoned -> Unit
            }
        }, plan.delayMs)
    }

    /** [DropReason] → 捕获层漏斗档位（穷尽 `when`，编译器保证新增原因不漏计数）。 */
    private fun funnelStage(reason: DropReason): String = when (reason) {
        DropReason.NOT_SUCCESS_PAGE -> CaptureFunnelStats.DROPPED_A11Y_NO_SUCCESS_KEYWORD
        DropReason.EXCLUDED -> CaptureFunnelStats.DROPPED_A11Y_EXCLUDED
        DropReason.NO_AMOUNT,
        DropReason.AMOUNT_UNPARSABLE,
        DropReason.AMOUNT_OUT_OF_RANGE,
        -> CaptureFunnelStats.DROPPED_A11Y_BAD_AMOUNT
        DropReason.HISTORY_DATE -> CaptureFunnelStats.DROPPED_A11Y_HISTORY_DATE
        DropReason.DEBOUNCE_8S -> CaptureFunnelStats.DROPPED_A11Y_DEBOUNCE
        DropReason.PAGE_DEDUP_30S -> CaptureFunnelStats.DROPPED_A11Y_PAGE_DEDUP
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        isServiceConnected = false
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        isServiceConnected = false
        appLogger.warn("A11Y", "无障碍服务被中断")
    }

    override fun onDestroy() {
        isServiceConnected = false
        super.onDestroy()
        appLogger.warn("A11Y", "无障碍服务销毁")
        handler.removeCallbacksAndMessages(null)
    }

    /** 查找匹配的关键词（系统 findByText 是模糊匹配，需二次精确校验）。 */
    private fun findMatchedKeyword(root: AccessibilityNodeInfo, keywords: List<String>): String? {
        for (kw in keywords) {
            val nodes = root.findAccessibilityNodeInfosByText(kw)
            if (nodes.isNullOrEmpty()) continue
            for (node in nodes) {
                val text = node.text?.toString()?.trim() ?: ""
                val desc = node.contentDescription?.toString()?.trim() ?: ""
                if (PaymentAccessibilityRecognition.isSuccessKeywordMatch(text, kw) ||
                    PaymentAccessibilityRecognition.isSuccessKeywordMatch(desc, kw)
                ) {
                    return kw
                }
            }
        }
        return null
    }

    /** 在节点树中找第一个匹配金额格式的文本（正则来自 profile/全局配置）。 */
    private fun findAmount(root: AccessibilityNodeInfo, regex: Regex): String? =
        findTextByPattern(root, regex)

    /** 查找商家名：找配置标签附近的文字。 */
    private fun findMerchant(root: AccessibilityNodeInfo, labels: List<String>): String? {
        for (label in labels) {
            val nodes = root.findAccessibilityNodeInfosByText(label)
            if (nodes.isNullOrEmpty()) continue
            for (node in nodes) {
                val parent = node.parent ?: continue
                for (i in 0 until parent.childCount) {
                    val child = parent.getChild(i) ?: continue
                    val text = child.text?.toString()?.trim()
                    if (!text.isNullOrBlank() && text != label && text.length in 2..30 && !text.contains("¥")) {
                        return text
                    }
                }
            }
        }
        return null
    }

    /**
     * 收集整页文本（trim、1..50 字、text/desc 去重）。gate 的判据与 pageText 都复用这一份。
     */
    private fun collectAllNodeTexts(node: AccessibilityNodeInfo, result: MutableList<String>) {
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrBlank() && text.length in 1..50) result.add(text)
        val desc = node.contentDescription?.toString()?.trim()
        if (!desc.isNullOrBlank() && desc.length in 1..50 && desc != text) result.add(desc)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllNodeTexts(child, result)
        }
    }

    /** 递归查找匹配正则的节点文本。 */
    private fun findTextByPattern(node: AccessibilityNodeInfo, pattern: Regex): String? {
        val text = node.text?.toString()
        if (text != null) {
            val match = pattern.find(text)
            if (match != null) return match.value
        }
        val desc = node.contentDescription?.toString()
        if (desc != null) {
            val match = pattern.find(desc)
            if (match != null) return match.value
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findTextByPattern(child, pattern)
            if (result != null) return result
        }
        return null
    }
}
