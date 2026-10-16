package com.aibill.android.service

import com.aibill.android.domain.notification.CaptureDrop
import com.aibill.android.domain.notification.CaptureDropHandler
import com.aibill.android.domain.notification.SourceChannel
import com.aibill.android.data.notification.rules.NotificationRulesManager
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
import com.aibill.android.domain.notification.CaptureIngress
import com.aibill.android.domain.notification.NotificationRuleSource
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.ConcurrentHashMap

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
        fun captureIngress(): CaptureIngress
        fun appLogger(): com.aibill.android.util.AppLogger
        fun rulesManager(): NotificationRulesManager
        fun ruleSource(): NotificationRuleSource
        fun captureFunnelStats(): CaptureFunnelStats
        fun a11yAdmissionGate(): A11yAdmissionGate
        fun a11yClock(): A11yClock
        fun captureDropHandler(): CaptureDropHandler
    }

    private lateinit var ingress: CaptureIngress
    private lateinit var appLogger: com.aibill.android.util.AppLogger
    private lateinit var rulesManager: NotificationRulesManager
    private lateinit var ruleSource: NotificationRuleSource
    private lateinit var captureFunnelStats: CaptureFunnelStats
    private lateinit var captureDropHandler: CaptureDropHandler
    private lateinit var gate: A11yAdmissionGate
    private lateinit var clock: A11yClock

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

    /** [setServiceInfo] 的下发时机闸门（见 [A11yServiceInfoGate]）。 */
    private val serviceInfoGate = A11yServiceInfoGate()

    companion object {
        private const val PACKAGE_WECHAT = "com.tencent.mm"
        private const val PACKAGE_ALIPAY = "com.eg.android.AlipayGphone"

        /** A11Y 节点文本拼成「整页」时的分隔符（与预筛列表页判据同一形状）。 */
        private const val PAGE_TEXT_SEPARATOR = " "

        /** 无 profile 的包走全局默认：不触发回退告警。 */
        private val NO_PROFILE_WARN = A11yProfileResolver.Warn { _, _, _ -> }

        /** 内嵌 App 中可能是支付结果页的 Activity 类名关键词。 */
        private val PAYMENT_ACTIVITY_HINTS = listOf(
            "pay", "payment", "cashier", "order", "result", "success",
            "trade", "checkout", "confirm", "receipt", "bill", "transaction",
        )

        /**
         * 节点树遍历的**深度上限**。
         *
         * 支付页的真实文本都在浅层；深的是支付宝/微信的 WebView 容器（可达上百层）。
         * 无上限的递归遇上那种树会 `StackOverflowError` → 进程死 → 服务解绑 →
         * （无 a11y rebind API）只能用户手动重开。截断的代价只是「这一页读不全」，
         * 与崩溃的代价比差着量级：读不全 → 当作未命中成功词丢弃，与今天的失败路径一致。
         */
        private const val MAX_TREE_DEPTH = 40

        /**
         * 单次扫描的**节点数上限**。同上：防「很浅但极宽」的畸形树把主线程/无障碍线程拖死。
         */
        private const val MAX_TREE_NODES = 4000

        /** 计入整页文本的节点文本长度上限（沿用改动前的口径：1..50 字）。 */
        private const val MAX_NODE_TEXT_LEN = 50

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
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, A11yEntryPoint::class.java)
        ingress = entryPoint.captureIngress()
        appLogger = entryPoint.appLogger()
        rulesManager = entryPoint.rulesManager()
        ruleSource = entryPoint.ruleSource()
        captureFunnelStats = entryPoint.captureFunnelStats()
        // ⚠️ 680307b 加 CaptureDropHandler 时漏了这一行（EntryPoint 声明了却没赋值）。
        //    a11y **丢弃**路径上的 onDrop 因此抛 UninitializedPropertyAccessException：
        //    1.6.2 无 try/catch → 异常逃出事件回调 → 进程崩溃 → 无障碍服务解绑
        //    （即「无障碍反复失效、需手动重开」的真正成因）；
        //    1.6.3+ 有兜底 → 不崩，但漏斗计数与采样全丢（onDrop 根本没进去）。
        captureDropHandler = entryPoint.captureDropHandler()
        gate = entryPoint.a11yAdmissionGate()
        clock = entryPoint.a11yClock()

        refreshRulesIfNeeded(force = true)
        // 连接态是下发 serviceInfo 的安全时机（此刻不在事件分发链路上）。
        applyPendingServiceInfo()
        appLogger.info("A11Y", "无障碍服务已连接 (v6: 事件回调不再下发 serviceInfo)")

        // ⚠️ 必须放在**最后**：改动前它在方法第一行置位，于是
        // 「初始化中途抛异常」这种最需要被发现的情况反而会被报成「服务正常」，
        // 而此时每个事件都会崩 → 静默永久失效，只有手动 toggle 能救。
        // 现在初始化失败就保持 false，让心跳如实报「开关开着但服务没连上」。
        isServiceConnected = true
    }

    private fun refreshRulesIfNeeded(force: Boolean = false) {
        val snapshot = rulesManager.getSnapshot()
        if (!force && snapshot.generation == lastRulesGeneration) return
        val rules = snapshot.rules
        embeddedPaymentApps = rules.a11y.embeddedPaymentApps.map(String::trim).filter(String::isNotBlank).toSet()
        paymentApps = A11yRecognition.paymentPackages(rules.a11y.embeddedPaymentApps)
        // A11Y 摘取策略：schema 守卫 + 逐键回退在快照层做完（与预筛共用）。
        val a11ySnapshot = ruleSource.snapshot()
        a11yGlobal = a11ySnapshot.a11yGlobalProfile
        a11yProfiles = a11ySnapshot.a11yPackageProfiles
        lastRulesGeneration = snapshot.generation
        // 只**记下**待下发的包名，不在这里下发——本方法会在事件回调链路上被调用
        // （onAccessibilityEvent 第一件事就是它），而连接态下改 serviceInfo 可能被系统
        // 解绑。真正的下发由 [applyPendingServiceInfo] 在安全时机完成。
        serviceInfoGate.onGenerationChanged(paymentApps.toTypedArray())
    }

    /**
     * 下发 `serviceInfo.packageNames`——**只允许在不在事件分发链路时调用**。
     *
     * 安全时机： [onServiceConnected] 与 [onInterrupt]（系统保证此刻没有事件在派发）。
     * 若此刻正在分发，[A11yServiceInfoGate] 会把更新留在 pending，
     * 等下一次安全时机补上——不丢，也不冒险。
     */
    private fun applyPendingServiceInfo() {
        val packages = serviceInfoGate.takeIfApplicable(serviceInfo?.packageNames) ?: return
        serviceInfo = serviceInfo?.apply { this.packageNames = packages }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val ev = event ?: return
        // 绝不让异常逃出事件回调：进程一死，无障碍服务解绑，而 Android 没有 a11y rebind API
        // → 只能用户手动去设置里关掉再开。单个页面的识别失败远不值得赔上整个服务。
        // catch Throwable（含 StackOverflowError / OOM 这类 Error）而不是 Exception。
        serviceInfoGate.enterEvent()
        try {
            handleAccessibilityEvent(ev)
        } catch (e: Throwable) {
            if (::appLogger.isInitialized) {
                appLogger.error(
                    "A11Y",
                    "事件处理异常（已兜住，服务保活）: ${e.javaClass.simpleName}: ${e.message} " +
                        "pkg=${ev.packageName}",
                )
            }
        } finally {
            serviceInfoGate.exitEvent()
        }
    }

    private fun handleAccessibilityEvent(ev: AccessibilityEvent) {
        val packageName = ev.packageName?.toString() ?: return
        captureFunnelStats.recordReceived(SourceChannel.A11Y)

        refreshRulesIfNeeded()
        if (packageName !in paymentApps) {
            // 白名单外默认不做树遍历（成本）；只有归表对这个丢弃点标了 sample 才付这个代价。
            dropEarly(
                drop = CaptureDrop.A11Y_NOT_WHITELISTED,
                packageName = packageName,
                samePkgRoot = rootInActiveWindow?.takeIf { it.packageName?.toString() == packageName },
                receivedAt = clock.now().epochMillis,
            )
            return
        }

        val kind = when (ev.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> PageEventKind.STATE
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> PageEventKind.CONTENT
            else -> return
        }

        // root 校验放在平台翻译层。顺序对齐改前：CONTENT 是 root → activity → pageToken；
        // STATE 先更新 token 再校验 root。
        val root = rootInActiveWindow
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
            val tokenStr = A11yRecognition.pageToken(packageName, activity, ev.windowId)
            currentPageToken[packageName] = tokenStr
            token = PageToken(tokenStr)
            if (!checkRoot(root, packageName)) return
        } else {
            if (!checkRoot(root, packageName)) return
            activity = currentActivity[packageName]
                ?: ev.className?.toString()?.takeIf { isActivityClassName(it) }?.also {
                    currentActivity[packageName] = it
                }
                ?: run {
                    dropEarly(CaptureDrop.A11Y_NO_ACTIVITY, packageName, root, clock.now().epochMillis)
                    return
                }
            if (!isPotentialPaymentActivity(activity, packageName)) {
                // 三天 9235 次、最可能藏真账的一类：这里以前完全不采内容，
                // 导致 content_hash 恒为 sha256("")、窗口内被去重压成 1 条空样本。
                dropEarly(CaptureDrop.A11Y_NOT_PAYMENT_ACTIVITY, packageName, root, clock.now().epochMillis)
                return
            }
            val tokenStr = currentPageToken[packageName]
            if (tokenStr == null) {
                dropEarly(CaptureDrop.A11Y_NO_PAGE_TOKEN, packageName, root, clock.now().epochMillis)
                return
            }
            token = PageToken(tokenStr)
        }

        val usableRoot = root ?: return

        // ── phase 1：节流（返回 Throttled 则不遍历树） ──
        val now = clock.now()
        if (gate.beforeScan(token, kind, now) == ScanDecision.Throttled) {
            // 三天 76667 次：归表没给它 sample，所以恒定零遍历（早退的意义就在这里）。
            dropEarly(CaptureDrop.A11Y_DEBOUNCE, packageName, usableRoot, now.epochMillis)
            return
        }

        processSnapshot(usableRoot, packageName, activity, token, now)
    }

    /**
     * 早退分支的统一出口：**先问要不要内容，再决定要不要遍历**。
     *
     * ## 为什么不无条件 `collectAllNodeTexts`
     *
     * 早退分支存在的意义就是「不遍历树」（省成本）：`debounce` 三天 76667 次，
     * 白名单外连规则表都不用查。无条件遍历会把「早退」变成「全量扫描」，
     * 拿最大的一类事件去换最小的一类收益。
     *
     * ## 为什么不干脆不采
     *
     * `CaptureSamplerImpl` 的 `content_hash` 是对 `text` 取的；`text == null` 时恒为
     * `sha256("")`，窗口内同 `blocked_by` 的样本会被去重压成 **1 条**。于是
     * `not_payment_activity`（三天 9235 次、最可能藏真账的一类）永远只能拿到空样本。
     *
     * 所以折中在 [CaptureDropHandler.needsRawText]：归表标了 `sample` 才遍历。
     *
     * ## [samePkgRoot] 为什么必须同包
     *
     * `root_mismatch` 的 root 属于**另一个 App**。采它既会把别的 App 的文本错记到
     * [packageName] 头上，也超出了「采集本 App 的通知」这条边界，故那处恒传 null。
     *
     * ## anchors 为什么留空
     *
     * 早退点**没跑过 gate**，本就没有「命中了哪个成功词/排除词」可言。
     * 采样侧 [com.aibill.android.data.notification.CaptureSamplerImpl.fragment] 会退化为
     * 「首个金额候选前后各 30 字」——而这些丢弃点绝大多数正是与金额/页型有关的。
     */
    private fun dropEarly(
        drop: CaptureDrop,
        packageName: String,
        samePkgRoot: AccessibilityNodeInfo?,
        receivedAt: Long,
    ) {
        if (samePkgRoot == null || !captureDropHandler.needsRawText(drop)) {
            captureDropHandler.onDrop(drop, packageName, receivedAt = receivedAt)
            return
        }
        val texts = mutableListOf<String>()
        collectAllNodeTexts(samePkgRoot, texts)
        captureDropHandler.onDrop(
            drop = drop,
            packageName = packageName,
            rawText = texts.takeIf { it.isNotEmpty() }?.joinToString(PAGE_TEXT_SEPARATOR),
            receivedAt = receivedAt,
        )
    }

    /** root 校验：null → NO_ROOT，跨包 → ROOT_MISMATCH。返回 false = 已记档并应早退。 */
    private fun checkRoot(root: AccessibilityNodeInfo?, packageName: String): Boolean {
        // 两处都传 null：**没有同包根节点**。root_mismatch 的 root 属于另一个 App，
        // 采它既会把别的 App 的文本错记到 packageName 头上，也超出了「采集本 App」的边界。
        // 文本为 null 不影响其余流程（采样侧照常去重/限流，只是拿不到内容）。
        if (root == null) {
            dropEarly(CaptureDrop.A11Y_NO_ROOT, packageName, samePkgRoot = null, receivedAt = clock.now().epochMillis)
            return false
        }
        if (!A11yRecognition.isRootForEvent(root.packageName?.toString(), packageName)) {
            dropEarly(CaptureDrop.A11Y_ROOT_MISMATCH, packageName, samePkgRoot = null, receivedAt = clock.now().epochMillis)
            return false
        }
        return true
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
                // gate 丢的是**整页**（成功词/排除词/金额/历史日期已在树里摘过）：
                // 把整页文本与命中锚点交给采样侧，由它只取命中片段（不采整页）。
                captureDropHandler.onDrop(
                    drop = funnelStage(admission.reason),
                    packageName = packageName,
                    rawText = allTexts.joinToString(PAGE_TEXT_SEPARATOR),
                    anchors = listOfNotNull(successKeyword, excludeKeyword, amountText),
                    receivedAt = now.epochMillis,
                )
                appLogger.debug("A11Y_SKIP", admission.detail)
                admission.retry?.let { scheduleRetry(packageName, it) }
            }
        }
    }

    /** gate 放行 → 入管道（预筛 → 入账协调者，与 NLS/SMS 同一套）。 */
    private fun routeToPrefilter(admission: Admission.Captured, packageName: String) {
        appLogger.info("A11Y", "✓识别支付页: ${admission.capture.fullText} pkg=$packageName")
        ingress.admit(admission.capture)
    }

    /**
     * 延迟重试：`plan.delayMs` 后重新获取 rootNode，把「当前页 token + 当前页文本」当值回传 gate。
     * 重试**决定**在 gate（token 是否仍是当前页 / anchor 是否匹配），这里只做调度。
     */
    private fun scheduleRetry(packageName: String, plan: RetryPlan) {
        handler.postDelayed({
            val retryRoot = rootInActiveWindow
            val usableRoot = retryRoot?.takeIf {
                A11yRecognition.isRootForEvent(it.packageName?.toString(), packageName)
            }
            val currentTexts = if (usableRoot != null) {
                mutableListOf<String>().also { collectAllNodeTexts(usableRoot, it) }
            } else {
                emptyList()
            }
            val now = clock.now()
            // 无论 root 是否可用都调 onRetryDue：让它清理挂起态（不可用时 anchor 必不匹配 → 放弃）。
            when (val outcome = gate.onRetryDue(plan.token, currentTexts, now)) {
                is RetryOutcome.Reread -> {
                    val root = usableRoot ?: return@postDelayed
                    val activity = currentActivity[packageName] ?: return@postDelayed
                    processSnapshot(root, packageName, activity, outcome.token, now)
                }
                is RetryOutcome.Abandoned -> Unit
            }
        }, plan.delayMs)
    }

    /** [DropReason] → 捕获层漏斗档位（穷尽 `when`，编译器保证新增原因不漏计数）。 */
    private fun funnelStage(reason: DropReason): CaptureDrop = when (reason) {
        DropReason.NOT_SUCCESS_PAGE -> CaptureDrop.A11Y_NO_SUCCESS_KEYWORD
        DropReason.EXCLUDED -> CaptureDrop.A11Y_EXCLUDED
        DropReason.NO_AMOUNT,
        DropReason.AMOUNT_UNPARSABLE,
        DropReason.AMOUNT_OUT_OF_RANGE,
        -> CaptureDrop.A11Y_BAD_AMOUNT
        DropReason.HISTORY_DATE -> CaptureDrop.A11Y_HISTORY_DATE
        DropReason.DEBOUNCE_8S -> CaptureDrop.A11Y_DEBOUNCE
        DropReason.PAGE_DEDUP_30S -> CaptureDrop.A11Y_PAGE_DEDUP
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
        // gate 是进程级 @Singleton：显式清瞬时状态，等价于改前「Service 重建即归零」。
        if (::gate.isInitialized) gate.reset()
    }

    /** 查找匹配的关键词（系统 findByText 是模糊匹配，需二次精确校验）。 */
    private fun findMatchedKeyword(root: AccessibilityNodeInfo, keywords: List<String>): String? {
        for (kw in keywords) {
            val nodes = root.findAccessibilityNodeInfosByText(kw)
            if (nodes.isNullOrEmpty()) continue
            for (node in nodes) {
                val text = node.text?.toString()?.trim() ?: ""
                val desc = node.contentDescription?.toString()?.trim() ?: ""
                if (A11yRecognition.isSuccessKeywordMatch(text, kw) ||
                    A11yRecognition.isSuccessKeywordMatch(desc, kw)
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
     * 收集整页文本（trim、≤ [MAX_NODE_TEXT_LEN] 字、text/desc 去重）。gate 的判据与 pageText 都复用这一份。
     *
     * ⚠️ **深度与节点数都有上限**（[MAX_TREE_DEPTH] / [MAX_TREE_NODES]）：
     * 改动前是无上限递归，而支付宝/微信的 WebView 容器深达上百层 → `StackOverflowError`
     * → 进程死 → 服务解绑 → 无 a11y rebind API → 用户手动重开。
     * 截断的代价只是「这一页读不全」（表现为未命中成功词，与今天的失败路径一致），
     * 比崩溃小几个量级。预算对象跨递归共享，顺手也防住「很浅但极宽」的畸形树。
     */
    private fun collectAllNodeTexts(node: AccessibilityNodeInfo, result: MutableList<String>) {
        collectAllNodeTexts(node, result, depth = 0, budget = TreeScanBudget())
    }

    private fun collectAllNodeTexts(
        node: AccessibilityNodeInfo,
        result: MutableList<String>,
        depth: Int,
        budget: TreeScanBudget,
    ) {
        if (depth > MAX_TREE_DEPTH || budget.exhausted()) {
            budget.markTruncated()
            return
        }
        budget.spend()
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrBlank() && text.length <= MAX_NODE_TEXT_LEN) result.add(text)
        val desc = node.contentDescription?.toString()?.trim()
        if (!desc.isNullOrBlank() && desc.length <= MAX_NODE_TEXT_LEN && desc != text) result.add(desc)
        for (i in 0 until node.childCount) {
            if (budget.exhausted()) {
                budget.markTruncated()
                return
            }
            val child = node.getChild(i) ?: continue
            collectAllNodeTexts(child, result, depth + 1, budget)
        }
    }

    /** 一次树扫描的节点预算；跨递归共享同一个实例。[truncated] 供诊断。 */
    private class TreeScanBudget {
        private var remaining: Int = MAX_TREE_NODES

        /** 本次扫描是否因深度/节点上限被截断（诊断用）。 */
        var truncated: Boolean = false

        fun exhausted(): Boolean = remaining <= 0

        fun spend() {
            remaining--
        }

        fun markTruncated() {
            truncated = true
        }
    }

    /** 递归查找匹配正则的节点文本。 */
    private fun findTextByPattern(node: AccessibilityNodeInfo, pattern: Regex): String? =
        findTextByPattern(node, pattern, depth = 0, budget = TreeScanBudget())

    /** 与 [collectAllNodeTexts] 同一条防线：递归必须有深度/节点上限（否则深 WebView 树会栈溢出）。 */
    private fun findTextByPattern(
        node: AccessibilityNodeInfo,
        pattern: Regex,
        depth: Int,
        budget: TreeScanBudget,
    ): String? {
        if (depth > MAX_TREE_DEPTH || budget.exhausted()) {
            budget.markTruncated()
            return null
        }
        budget.spend()
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
            if (budget.exhausted()) {
                budget.markTruncated()
                return null
            }
            val child = node.getChild(i) ?: continue
            val result = findTextByPattern(child, pattern, depth + 1, budget)
            if (result != null) return result
        }
        return null
    }
}
