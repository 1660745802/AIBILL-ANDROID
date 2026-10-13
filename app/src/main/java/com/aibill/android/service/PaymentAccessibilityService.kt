package com.aibill.android.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.aibill.android.domain.notification.A11yGlobalProfile
import com.aibill.android.domain.notification.A11yPackageProfile
import com.aibill.android.domain.notification.A11yProfileResolver
import com.aibill.android.domain.notification.Capture
import com.aibill.android.domain.notification.CaptureFunnelStats
import com.aibill.android.domain.notification.NotificationPrefilter
import com.aibill.android.domain.notification.NotificationRuleSource
import com.aibill.android.domain.notification.SourceChannel
import com.aibill.android.domain.notification.Verdict
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap

internal object PaymentAccessibilityRecognition {
    fun pageToken(packageName: String, className: String, windowId: Int): String =
        "${packageName.trim().lowercase()}|$windowId|${className.trim().lowercase()}"

    fun pageState(packageName: String, className: String, windowId: Int): Pair<String, String> =
        className to pageToken(packageName, className, windowId)

    /**
     * root 是否可用于处理该事件：**仅跨包才拒绝**。
     *
     * **刻意不比较 windowId。** 同包不同 windowId 是常态（2026-10-04 实测）：
     * - `class=Dialog  root=MISMATCH(rootWin=37735 evWin=37741)` ← 支付宝支付完成瞬间
     * - `class=FrameLayout root=MISMATCH(rootWin=39136 evWin=39235)` ← 微信视频通话全程
     *
     * 旧实现在 windowId 不等时直接 return，会把整批真交易丢掉（日志里那笔
     * ¥20.00 就是这么丢的，NLS 侧又同时碰上 401 + 排除词误杀）。
     * 放宽后的误扫风险由调用方多重兜底承担：debounce(8s) + cooldown(30s)
     * + 评分窗口 + 金额去重。
     *
     * @param rootPackage `rootInActiveWindow` 的包名
     */
    fun isRootForEvent(rootPackage: String?, eventPackage: String): Boolean =
        rootPackage == eventPackage

    fun amountKey(amountText: String): String? {
        val value = amountText
            .replace("¥", "")
            .replace("￥", "")
            .replace("元", "")
            .replace(",", "")
            .replace(" ", "")
            .trim()
            .toBigDecimalOrNull() ?: return null
        return value.setScale(2, RoundingMode.HALF_UP).movePointRight(2).toBigInteger().toString()
    }

    fun isSuccessKeywordMatch(text: String, keyword: String): Boolean {
        val value = text.trim()
        if (value == keyword) return true
        if (value.length > keyword.length * 3 || !value.contains(keyword)) return false
        val suffix = value.substring(value.indexOf(keyword) + keyword.length)
        if (suffix.isBlank()) return true
        if (suffix == "了" || suffix.startsWith("了 ") || suffix.startsWith("了，") || suffix.startsWith("了。") || suffix.startsWith("了！")) {
            return true
        }
        return suffix.first().isWhitespace() || suffix.first() in "!！。,.，:：¥￥(（[【"
    }

    fun retryAnchor(texts: List<String>, successKeyword: String): Set<String> =
        texts.asSequence()
            .map(String::trim)
            .filter { it.isNotBlank() && it.length <= 50 }
            .filterNot { it == successKeyword || amountKey(it) != null }
            .distinct()
            .take(6)
            .toSet()

    fun matchesRetryAnchor(anchor: Set<String>, currentTexts: List<String>): Boolean {
        if (anchor.isEmpty()) return false
        val current = currentTexts.mapTo(HashSet(), String::trim)
        return anchor.count(current::contains) >= minOf(2, anchor.size)
    }

    fun paymentPackages(embeddedPackages: List<String>): Set<String> =
        setOf("com.tencent.mm", "com.eg.android.AlipayGphone") +
            embeddedPackages.asSequence().map(String::trim).filter(String::isNotBlank).toSet()

    fun dedupTtl(cooldownMs: Long): Long = minOf(cooldownMs, 30_000L)

    fun containsExcludeKeyword(texts: List<String>, keywords: List<String>): Boolean =
        keywords.any { keyword -> keyword.isNotBlank() && texts.any { it.contains(keyword) } }

    fun isRecentTextRange(texts: List<String>, today: java.time.LocalDate): Boolean {
        if (texts.any { it.contains("昨天") || it.contains("昨日") || it.contains("天前") || it.contains("月前") }) {
            return false
        }
        val monthDay = Regex("""(\d{1,2})月(\d{1,2})日""")
        // Bare M-D strings are commonly order IDs; only accept full ISO dates.
        val dashDate = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})""")
        return texts.none { text ->
            val chinese = monthDay.find(text)
            if (chinese != null) {
                chinese.groupValues[1].toIntOrNull() != today.monthValue ||
                    chinese.groupValues[2].toIntOrNull() != today.dayOfMonth
            } else {
                val dashed = dashDate.find(text)
                dashed != null && (
                    dashed.groupValues[2].toIntOrNull() != today.monthValue ||
                        dashed.groupValues[3].toIntOrNull() != today.dayOfMonth
                    )
            }
        }
    }

    /** 列表页「金额节点」数下限：真实支付结果页最多 2 个（实付 + 原价/优惠）。 */
    const val LIST_PAGE_MIN_AMOUNTS = 3

    /**
     * 列表词必须出现在 `pageText` 前 N 个字符内（页面标题区）。
     *
     * 真交易结果页常有 ≥3 个金额（实付 + 原价 + 优惠），底部又有「查看账单」这类**入口按钮**
     * （含列表词「账单」）——只看「金额 ≥3 且命中列表词」会把它们当列表页拦掉 = 误杀真账（P0）。
     * 页面标题（「账单」「交易记录」）在节点树里靠前，入口按钮在底部；把命中范围收窄到前 N 个
     * 字符即可放行真结果页。
     *
     * **取舍：宁可漏挡（噪声多调一次 AI），不可误杀（丢真账）。** 收窄后若某真列表页的标题不在
     * 前 N 字符内，只是漏挡一次（该页逐行过预筛后仍会走 AI），不丢账；反之放宽就会拦掉真交易。
     * 该常量是纯客户端页面结构判据的内置初值；现在可被云控
     * `a11y.app_profiles.<pkg>.list_page_head_chars` 覆盖（缺省 = 本值；见 `A11yProfileResolver`）。
     */
    const val LIST_PAGE_KEYWORD_HEAD_CHARS = A11yProfileResolver.DEFAULT_LIST_PAGE_HEAD_CHARS

    /**
     * 列表页启发式的**内置兜底**列表词（7 个）。
     *
     * 生产取值是**云控优先、内置兜底**：服务端已放行 `a11y.list_page_keywords` /
     * `a11y.list_page_min_amounts`（可选键，出现即做形状校验——空数组 / 非正阈值 / 类型错会被拒），
     * 无云控或缺键时回退到本列表与 [LIST_PAGE_MIN_AMOUNTS]。
     */
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
     * 列表页启发式：**金额数 ≥ [minAmounts] 且列表词出现在页面前 [LIST_PAGE_KEYWORD_HEAD_CHARS]
     * 个字符内（标题区）** → 判为账单/明细页。
     *
     * 三条判据都是刻意的：
     * - 只靠金额数会误杀结果页（实付+原价+优惠 = 3 个金额）；
     * - 列表词单独出现（如「查看账单」入口）只有 1 个金额，也不该拦；
     * - 列表词再限定在标题区：真结果页底部的「查看账单」按钮含「账单」，若不限位置，
     *   「3 个金额 + 入口按钮」就会把真交易页当列表页拦掉（P0 误杀）。
     *
     * @param minAmounts 金额数下限，生产由快照下发（云控优先、内置兜底）。
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
 * 支付页面无障碍识别服务——**平台翻译 adapter**。
 *
 * v3 → v4 改进：
 * 1. 同时监听 TYPE_WINDOW_STATE_CHANGED + TYPE_WINDOW_CONTENT_CHANGED
 *    - STATE_CHANGED：立即处理（Activity 切换）
 *    - CONTENT_CHANGED：3s 节流（同 Activity 内 Fragment/WebView 页面更新）
 * 2. 延迟重试：首次有成功关键词但无金额时，500ms 后再试一次（解决渐进渲染）
 * 3. cooldown 去重改为"金额+商家"组合键（避免同金额不同商家被误杀）
 * 4. isRecentPayment 只检查金额节点附近的日期文本（避免页面无关文本触发误杀）
 *
 * ## 收口后本类只剩树上的平台事实（架构候选 2）
 *
 * 三重条件（命中支付成功词 / 排除词 / 金额文本 / 非历史账单）、
 * 8s 内容防抖、金额|商家|页面 冷却、扫描节流、延迟重试**全部留在 adapter**——
 * 它们依赖的是 `AccessibilityNodeInfo` 与事件时序，不是「这条通知值不值得当账」。
 * 识别成功后产出的摘要交给 [NotificationPrefilter]（与 NLS/SMS 同一套判据）再入库。
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
        @com.aibill.android.di.ApplicationScope
        fun applicationScope(): kotlinx.coroutines.CoroutineScope
    }

    private lateinit var notificationProcessor: NotificationProcessor
    private lateinit var prefilter: NotificationPrefilter
    private lateinit var appLogger: com.aibill.android.util.AppLogger
    private lateinit var rulesManager: NotificationRulesManager
    private lateinit var ruleSource: NotificationRuleSource
    private lateinit var captureFunnelStats: CaptureFunnelStats

    // PR 修复：改用进程级 ApplicationScope，避免 Service 每次实例化都泄漏一个 SupervisorJob。
    private lateinit var serviceScope: kotlinx.coroutines.CoroutineScope
    private val handler = Handler(Looper.getMainLooper())

    /** 防抖：同内容 8s 内不重复（从 10s 降低以减少快速支付场景的遗漏） */
    private var lastHash: Int = 0
    private var lastTime: Long = 0L

    /** cooldown：同"金额+商家"组合 N 分钟内只触发一次 */
    private val recentPayments = ConcurrentHashMap<String, Long>()

    /** CONTENT_CHANGED 节流：同页面 3s 内只处理一次 */
    private val contentChangeThrottle = ConcurrentHashMap<String, Long>()

    /** 同页面全树扫描节流：STATE + CONTENT 统一走这里（延迟重试不走） */
    private val scanThrottle = ConcurrentHashMap<String, Long>()

    /** 延迟重试：每个页面最多挂起一次，切页后旧 token 会自动失效 */
    private val pendingRetryTokens = ConcurrentHashMap.newKeySet<String>()

    // ═══════════════════════════════════════════════════════════════
    // 从云控规则读取（每次事件时从内存缓存刷新）
    // ═══════════════════════════════════════════════════════════════
    private var embeddedPaymentApps: Set<String> = emptySet()
    private var paymentApps: Set<String> = emptySet()

    /**
     * A11Y 摘取策略（服务端 `docs/API.md` 附录 C §10）：全局基底 + 逐键回退后的 per-App 覆盖。
     *
     * 解析（schema 守卫 + 逐键回退 + regex 编译 + 回退告警）在 [NotificationRuleSource]
     * 快照里**只做一次**，与预筛共用同一份结果；这里只按代际取用。
     */
    private var a11yGlobal: A11yGlobalProfile = A11yGlobalProfile()
    private var a11yProfiles: Map<String, A11yPackageProfile> = emptyMap()
    private var cooldownMs: Long = 5 * 60 * 1000L
    private var lastRulesGeneration: Int = -1

    companion object {
        private const val DEBOUNCE_MS = 8_000L
        private const val CONTENT_CHANGE_THROTTLE_MS = 3_000L

        /**
         * 同页面重复扫描节流。
         *
         * 微信/支付宝在一次 Activity 切换里会连发 STATE + CONTENT（乃至两次
         * STATE）事件，而原先 contentChangeThrottle 只作用于 CONTENT 分支，
         * STATE 分支零去重 → 同一页面全树扫描 + 落库两次（日志里大量成对
         * 出现的 A11Y_PAGE 即来源于此）。
         */
        private const val SCAN_THROTTLE_MS = 1_500L
        private const val RETRY_DELAY_MS = 500L

        /** [Capture.pageText] 去噪后最多保留的节点数与字数（列表页只看结构，不看全文）。 */
        private const val PAGE_TEXT_MAX_NODES = 60
        private const val PAGE_TEXT_MAX_CHARS = 500
        private const val PACKAGE_WECHAT = "com.tencent.mm"
        private const val PACKAGE_ALIPAY = "com.eg.android.AlipayGphone"

        /** 无障碍渠道产出的摘要标题：与页面上「支付成功」一致，也是判据链的 title 输入。 */
        private const val A11Y_ITEM_TITLE = "支付成功"

        /**
         * 无 profile 的包走全局默认：[A11yProfileResolver.resolve] 在 `override == null` 时
         * 不会触发任何回退，不应产生告警（否则每个 A11Y 事件都会写一条）。
         */
        private val NO_PROFILE_WARN = A11yProfileResolver.Warn { _, _, _ -> }

        /**
         * 服务是否已成功连接到无障碍框架。
         *
         * 与「设置里的开关是否打开」是两件事：
         * 国内 ROM 经常在后台杀掉无障碍服务进程/断开连接，但设置开关仍是开的。
         * [A11yHealthCheckWorker] 之前只看开关，完全检测不到这种「开着但没连上」
         * 的真实故障——而这恰恰是自动记账静默失效最常见的原因。
         *
         * 进程被杀时这个静态值会随进程一起消失 → 读到的 false 会退化成
         * 「进程没在跑」，与「开关开着但没连上」无法区分。因此 Worker 侧
         * 只用它做**辅助信号**，主判据仍是设置开关。
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
        serviceScope = entryPoint.applicationScope()

        refreshRulesIfNeeded(force = true)
        appLogger.info("A11Y", "无障碍服务已连接 (v4: STATE+CONTENT, 节流+重试)")
    }

    private fun refreshRulesIfNeeded(force: Boolean = false) {
        val snapshot = rulesManager.getSnapshot()
        if (!force && snapshot.generation == lastRulesGeneration) return
        val rules = snapshot.rules
        embeddedPaymentApps = rules.a11y.embeddedPaymentApps.map(String::trim).filter(String::isNotBlank).toSet()
        paymentApps = PaymentAccessibilityRecognition.paymentPackages(rules.a11y.embeddedPaymentApps)
        // A11Y 摘取策略：schema 守卫 + 逐键回退 + regex 编译 + 回退告警都在快照层做完了
        //（与预筛共用），这里直接取用，不再各自维护一份全局字段。
        val a11ySnapshot = ruleSource.snapshot()
        a11yGlobal = a11ySnapshot.a11yGlobalProfile
        a11yProfiles = a11ySnapshot.a11yPackageProfiles
        cooldownMs = rules.a11y.cooldownMinutes * 60 * 1000L
        lastRulesGeneration = snapshot.generation
        serviceInfo = serviceInfo?.apply { packageNames = paymentApps.toTypedArray() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val ev = event ?: return
        val packageName = ev.packageName?.toString() ?: return
        captureFunnelStats.record(CaptureFunnelStats.RECEIVED_A11Y)

        refreshRulesIfNeeded()
        if (packageName !in paymentApps) {
            // 捕获层早退：白名单外的 App 无支付语义，预筛看不到（黑盒中最大的一块）
            captureFunnelStats.record(CaptureFunnelStats.DROPPED_A11Y_NOT_WHITELISTED)
            return
        }

        val eventTypeName = when (ev.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "STATE"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "CONTENT"
            else -> "OTHER(${ev.eventType})"
        }

        when (ev.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val className = ev.className?.toString() ?: ""
                val activity = if (isActivityClassName(className)) {
                    currentActivity[packageName] = className
                    className
                } else {
                    currentActivity[packageName] ?: className
                }
                val windowId = ev.windowId
                val pageToken = PaymentAccessibilityRecognition.pageToken(packageName, activity, windowId)
                currentWindowId[packageName] = windowId
                currentPageToken[packageName] = pageToken
                pendingRetryTokens.removeIf { it != pageToken }

                val root = rootInActiveWindow
                if (root == null) {
                    captureFunnelStats.record(CaptureFunnelStats.DROPPED_A11Y_NO_ROOT)
                    appLogger.debug("A11Y_EVENT", "$eventTypeName pkg=$packageName class=${className.substringAfterLast('.')} root=NULL")
                    return
                }
                if (!PaymentAccessibilityRecognition.isRootForEvent(
                        root.packageName?.toString(), packageName,
                    )) {
                    captureFunnelStats.record(CaptureFunnelStats.DROPPED_A11Y_ROOT_MISMATCH)
                    appLogger.debug("A11Y_EVENT", "$eventTypeName pkg=$packageName class=${className.substringAfterLast('.')} root=MISMATCH(rootPkg=${root.packageName} rootWin=${root.windowId} evWin=$windowId)")
                    return
                }
                processPaymentCheck(root, packageName, activity, windowId, isRetry = false)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val savedWindowId = currentWindowId[packageName] ?: -1
                val root = rootInActiveWindow
                if (root == null) {
                    // CONTENT_CHANGED root为null很常见（频繁事件），不记日志避免爆量
                    captureFunnelStats.record(CaptureFunnelStats.DROPPED_A11Y_NO_ROOT)
                    return
                }
                if (!PaymentAccessibilityRecognition.isRootForEvent(
                        root.packageName?.toString(), packageName,
                    )) {
                    captureFunnelStats.record(CaptureFunnelStats.DROPPED_A11Y_ROOT_MISMATCH)
                    return
                }
                val activity = currentActivity[packageName]
                    // ★ 兑底：首个 STATE 尚未到达时，CONTENT 事件会拿到 null 而被整批丢掉。
                    //   实测 `A11Y_EVENT CONTENT 无已知Activity，跳过` 出现在支付宝支付
                    //   页上。STATE 分支本来就有 `?: className` 兑底，这里补齐对称逻辑。
                    ?: ev.className?.toString()?.takeIf { isActivityClassName(it) }
                    ?: run {
                        captureFunnelStats.record(CaptureFunnelStats.DROPPED_A11Y_NO_ACTIVITY)
                        appLogger.debug("A11Y_EVENT", "CONTENT pkg=$packageName 无可用 Activity，跳过")
                        return
                    }
                if (!isPotentialPaymentActivity(activity, packageName)) {
                    // 非支付相关Activity的CONTENT_CHANGED，不记日志（太多）
                    captureFunnelStats.record(CaptureFunnelStats.DROPPED_A11Y_NOT_PAYMENT_ACTIVITY)
                    return
                }

                val pageToken = currentPageToken[packageName]
                if (pageToken == null) {
                    captureFunnelStats.record(CaptureFunnelStats.DROPPED_A11Y_NO_PAGE_TOKEN)
                    return
                }
                val now = System.currentTimeMillis()
                val lastCheck = contentChangeThrottle[pageToken] ?: 0L
                if (now - lastCheck < CONTENT_CHANGE_THROTTLE_MS) {
                    // 节流中，不记日志
                    captureFunnelStats.record(CaptureFunnelStats.DROPPED_A11Y_DEBOUNCE)
                    return
                }
                contentChangeThrottle[pageToken] = now
                processPaymentCheck(root, packageName, activity, savedWindowId, isRetry = false)
            }
        }
    }

    /** 记录每个包名当前所在的 Activity */
    private val currentActivity = ConcurrentHashMap<String, String>()
    private val currentPageToken = ConcurrentHashMap<String, String>()
    private val currentWindowId = ConcurrentHashMap<String, Int>()

    /**
     * 判断类名是否是一个 Activity（而非 View/Dialog 等）。
     * 参考反编译项目：只有包含 activity/home/welcome/appbrandui 等特征才算。
     */
    private fun isActivityClassName(className: String): Boolean {
        if (className.isBlank()) return false
        val lower = className.lowercase()
        // 系统 View 类不算 Activity
        if (lower.startsWith("android.widget.") || lower.startsWith("android.view.")) return false
        // 常见 Activity 特征
        return lower.contains("activity") || lower.contains("fragment")
                || lower.contains("home") || lower.contains("appbrandui")
                || lower.contains("welcome") || lower.contains("launcher")
                || lower.contains("main") || lower.contains("page")
    }

    /**
     * 页面是否含金额样式文本。仅用于「未命中成功词」那行汇总日志的诊断字段，
     * **不参与任何放行/拦截判定**。
     *
     * 启动器页的子应用名常带数字（美团丨外卖团购特价美食酒店电影 / 三国：冰河时代），
     * 所以 hasAmount=true 并不代表这页与交易有关——这正是原实��把它当「疑似支付页」
     * 落库原文的起因。
     */
    private fun hasAmountLikeText(allTexts: List<String>, regex: Regex): Boolean =
        allTexts.any { regex.containsMatchIn(it) }

    /**
     * 判断当前 Activity 是否"可能是支付结果页"——只在这些页面上才响应 CONTENT_CHANGED。
     *
     * 策略（借鉴反编译项目的 Activity 白名单思路，但用模糊匹配而非硬编码）：
     * - 微信/支付宝：几乎所有 Activity 都可能展示支付结果（小程序容器等）
     * - 内嵌 App（美团/饿了么等）：只有包含 pay/order/result/cashier/success 等特征的 Activity
     */
    private fun isPotentialPaymentActivity(activity: String, packageName: String): Boolean {
        // 微信和支付宝的支付结果可能出现在任何容器页面
        if (packageName == PACKAGE_WECHAT || packageName == PACKAGE_ALIPAY) return true

        // 内嵌支付 App：只在可能的支付/订单页面上响应 CONTENT_CHANGED
        val lower = activity.lowercase()
        return paymentActivityHints.any { lower.contains(it) }
    }

    /** 内嵌 App 中可能是支付结果页的 Activity 类名关键词 */
    private val paymentActivityHints = listOf(
        "pay", "payment", "cashier", "order", "result", "success",
        "trade", "checkout", "confirm", "receipt", "bill", "transaction"
    )

    /**
     * 核心识别逻辑。
     * @param isRetry 是否是延迟重试调用
     */
    private fun processPaymentCheck(
        rootNode: AccessibilityNodeInfo,
        packageName: String,
        className: String,
        windowId: Int,
        isRetry: Boolean,
    ) {
        try {
            val shortClassName = className.substringAfterLast('.')
            val pageToken = PaymentAccessibilityRecognition.pageToken(packageName, className, windowId)

            // 同页面短时间重复事件（STATE+CONTENT 双触发）不重复全树扫描。
            // 延迟重试必须绕过节流，否则渐进渲染的金额会被节流吃掉。
            if (!isRetry) {
                val scanNow = System.currentTimeMillis()
                val lastScan = scanThrottle[pageToken] ?: 0L
                if (scanNow - lastScan < SCAN_THROTTLE_MS) {
                    captureFunnelStats.record(CaptureFunnelStats.DROPPED_A11Y_DEBOUNCE)
                    return
                }
                scanThrottle[pageToken] = scanNow
                if (scanThrottle.size > 64) {
                    scanThrottle.entries.removeIf { scanNow - it.value > 10_000L }
                }
            }

            // 单次遍历收集页面文本；后续判断（排除词/历史日期/商家上下文）全部复用
            // 这一份结果——原实现每个判断都重新 collectAllNodeTexts 整棵树，
            // 单次事件最多跑 4 遍全树遍历。
            val allTexts = mutableListOf<String>()
            collectAllNodeTexts(rootNode, allTexts)

            // 判断是否为内嵌支付 App
            val isEmbeddedApp = packageName in embeddedPaymentApps
            // A11Y 摘取策略：per-App profile 逐键回退后的最终值；无 profile = 全局作用域默认。
            // ⚠️ 只影响「选/取文本」，不参与「是不是账」判定（原则 1）。
            val policy = a11yProfiles[packageName]
                ?: A11yProfileResolver.resolve(packageName, isEmbeddedApp, null, a11yGlobal, NO_PROFILE_WARN)
            val activeKeywords = policy.successKeywords

            // 条件1：有支付成功关键词
            val matchedKeyword = findMatchedKeyword(rootNode, activeKeywords)
            if (matchedKeyword == null) {
                captureFunnelStats.record(CaptureFunnelStats.DROPPED_A11Y_NO_SUCCESS_KEYWORD)
                // 未命中成功词 = 不是支付结果页，一律**只记一行汇总**。
                //
                // 2026-10-09 真机日志证据：原实现在这里分两支，有金额的支支
                // 会把 15 条页面原文 + 节点树全写进 app_logs：
                //   A11Y_MISS 有金额无成功词: [LauncherUI] 浮窗|退出浮窗|最近|搜索小程序 搜索栏|
                //     ...|氢动国旅,|氢动国旅|小米食堂Lite,...          ← 微信小程序名
                //   A11Y_MISS 有金额无成功词: [LauncherUI] 浮窗|...|儒宝|晚上6:50|[视频通话]|kiro|
                //     下午4:44|✅ 已开始新对话|富人区|...              ← 联系人昵称 + 聊天时间
                //   A11Y_MISS 有金额无成功词: [AppBrandUI00] ...|订单编号：1788869210552871|
                //     凭证码：1883336738|...|单价：88.00               ← 订单号 + 凭证码
                // 那一轮 18 条 A11Y_MISS **无一条是真实支付页**，全是启动器/小程序首页，
                // 却把聊天气泡、订单号、凭证码写进了**可导出的** app_logs。
                // 启动器页为什么会有金额？子应用名里就带数字（美团丨外卖团购特价美食酒店电影），
                // amountRegex 只需匹配到一处即 hasAmount=true。
                //
                // 未命中成功词时页面内容**对排障没有价值**（真正要看的 A11Y_PAGE 在命中后
                // 才记），所以统一收敛成一行：既堵住隐私泄露也让日志能看了。
                val hasAmount = hasAmountLikeText(allTexts, policy.amountRegex)
                appLogger.debug(
                    "A11Y_SKIP",
                    "未命中支付成功词: pkg=$packageName class=$shortClassName " +
                        "texts=${allTexts.size} hasAmount=$hasAmount",
                )
                return
            }

            // 命中成功词后才记页面文本：这是排查漏识别真正需要的内容
            appLogger.debug(
                "A11Y_PAGE",
                "[$packageName/$shortClassName] texts=${allTexts.size} retry=$isRetry ${allTexts.take(20).joinToString("|")}",
            )
            // 条件2：排除非支付结果页
            val activeExcludeKeywords = policy.excludeKeywords
            if (hasAnyKeyword(allTexts, activeExcludeKeywords)) {
                captureFunnelStats.record(CaptureFunnelStats.DROPPED_A11Y_EXCLUDED)
                appLogger.debug("A11Y_SKIP", "排除词命中: [$shortClassName] pkg=$packageName")
                return
            }

            // 条件3：有金额文字
            val amountText = findAmount(rootNode, policy.amountRegex)
            if (amountText == null) {
                captureFunnelStats.record(CaptureFunnelStats.DROPPED_A11Y_BAD_AMOUNT)
                // 有成功词但没找到金额 → 可能页面还没渲染完
                if (!isRetry) {
                    scheduleRetry(
                        packageName,
                        className,
                        windowId,
                        PaymentAccessibilityRecognition.retryAnchor(allTexts, matchedKeyword),
                    )
                } else {
                    appLogger.debug("A11Y_SKIP", "重试后仍无金额: [$shortClassName]")
                }
                return
            }

            // 条件3.5：金额合理性校验（排除 ¥0.00、超大金额等异常值）。
            // 一律整数分比较（CONTRIBUTING §三：禁止 Double/Float 参与金额计算）；
            // 上下限与旧实现一致：0.01 元 ~ 100,000.00 元。
            val amountCents = extractNumericAmountCents(amountText)
            if (amountCents == null || amountCents < 1 || amountCents > 100_000_00) {
                captureFunnelStats.record(CaptureFunnelStats.DROPPED_A11Y_BAD_AMOUNT)
                appLogger.debug("A11Y_SKIP", "金额异常($amountCents): [$shortClassName] raw=$amountText")
                return
            }

            // 条件4：不是历史账单（只检查金额节点附近文本）
            if (!isRecentPayment(allTexts, amountText)) {
                captureFunnelStats.record(CaptureFunnelStats.DROPPED_A11Y_HISTORY_DATE)
                appLogger.debug("A11Y_SKIP", "历史日期拦截: [$shortClassName] amount=$amountText")
                return
            }

            // ===== 三重条件全部满足 → 构建摘要 =====
            val merchant = findMerchant(rootNode, policy.merchantLabels)
            val summary = if (merchant != null) {
                "$A11Y_ITEM_TITLE $amountText $merchant"
            } else {
                val context = collectNearbyText(allTexts, amountText)
                "$A11Y_ITEM_TITLE $amountText $context"
            }

            // 防抖：同内容 8s 内不重复
            val hash = summary.hashCode()
            val now = System.currentTimeMillis()
            if (hash == lastHash && (now - lastTime) < DEBOUNCE_MS) {
                captureFunnelStats.record(CaptureFunnelStats.DROPPED_A11Y_DEBOUNCE)
                appLogger.debug("A11Y_SKIP", "防抖拦截(${DEBOUNCE_MS / 1000}s): $amountText")
                return
            }
            lastHash = hash
            lastTime = now

            // cooldown：同规范化金额+页面 N 分钟内只触发一次
            val amountKey = PaymentAccessibilityRecognition.amountKey(amountText)
            if (amountKey == null) {
                captureFunnelStats.record(CaptureFunnelStats.DROPPED_A11Y_BAD_AMOUNT)
                return
            }
            val merchantKey = merchant?.trim()?.lowercase().orEmpty()
            val deduKey = "$amountKey|$merchantKey|$pageToken"
            val dedupTtl = PaymentAccessibilityRecognition.dedupTtl(cooldownMs)
            val lastPayTime = recentPayments[deduKey]
            if (lastPayTime != null && (now - lastPayTime) < dedupTtl) {
                captureFunnelStats.record(CaptureFunnelStats.DROPPED_A11Y_PAGE_DEDUP)
                appLogger.debug("A11Y_SKIP", "页面去重(${dedupTtl / 1000}s同金额+商家): $deduKey")
                return
            }
            recentPayments[deduKey] = now
            // 清理过期条目
            recentPayments.entries.removeIf { now - it.value > dedupTtl }

            appLogger.info(
                "A11Y",
                "✓识别支付页: $summary pkg=$packageName keyword=$matchedKeyword tree=${buildNodeTree(rootNode, maxDepth = 5)}",
            )

            // 交给预筛（与通知/短信同一套判据）；放行后才进 Processor
            serviceScope.launch {
                try {
                    val verdict = prefilter.evaluate(
                        Capture(
                            channel = SourceChannel.A11Y,
                            packageName = packageName,
                            title = A11Y_ITEM_TITLE,
                            fullText = summary,
                            receivedAt = now,
                            pageText = buildPageText(allTexts),
                        )
                    )
                    if (verdict is Verdict.Block) {
                        appLogger.debug("A11Y_SKIP", "预筛拦截(${verdict.reason}): pkg=$packageName")
                        return@launch
                    }
                    val passed = (verdict as Verdict.Pass).capture
                    notificationProcessor.process(passed)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 进程级 Scope 没有 CoroutineExceptionHandler，预筛/落库的 Room 异常
                    // 会直接崩进程；按 §10「本地 Room 异常 → 降级 + 日志」。
                    appLogger.error("A11Y", "支付页处理异常: pkg=$packageName err=${e.message}")
                }
            }
        } finally {
            // rootNode 不再需要手动 recycle (Android 14+ deprecated)
        }
    }

    /**
     * 延迟重试：500ms 后重新获取 rootNode 再解析一次。
     * 解决支付结果页渐进渲染导致首次扫描时金额还未出现的问题。
     */
    private fun scheduleRetry(packageName: String, className: String, windowId: Int, contentAnchor: Set<String>) {
        val pageToken = PaymentAccessibilityRecognition.pageToken(packageName, className, windowId)
        if (!pendingRetryTokens.add(pageToken)) return

        appLogger.debug("A11Y_RETRY", "调度延迟重试: pkg=$packageName class=$className")

        handler.postDelayed({
            pendingRetryTokens.remove(pageToken)
            val retryRoot = rootInActiveWindow ?: return@postDelayed
            if (!PaymentAccessibilityRecognition.isRootForEvent(
                    retryRoot.packageName?.toString(), packageName,
                )) return@postDelayed
            val currentTexts = mutableListOf<String>()
            collectAllNodeTexts(retryRoot, currentTexts)
            if (
                currentPageToken[packageName] == pageToken &&
                PaymentAccessibilityRecognition.matchesRetryAnchor(contentAnchor, currentTexts)
            ) {
                processPaymentCheck(retryRoot, packageName, className, windowId, isRetry = true)
            }
        }, RETRY_DELAY_MS)
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
        // PR 修复：serviceScope 已改用 @ApplicationScope 注入，
        // 进程级 Scope 由 CoroutineScopeModule 持有，不在 Service 这里 cancel。
        // 仅清理本地 handler 回调。
    }

    // ═══════════════════════════════════════════════════════════════
    // 辅助方法
    // ═══════════════════════════════════════════════════════════════

    /** Exclusion rules are substring rules (for example, "购物车" in "加入购物车"). */
    private fun hasAnyKeyword(texts: List<String>, keywords: List<String>): Boolean {
        if (keywords.isEmpty()) return false
        return PaymentAccessibilityRecognition.containsExcludeKeyword(texts, keywords)
    }

    /**
     * 查找匹配的关键词。
     *
     * findAccessibilityNodeInfosByText 是模糊匹配（系统 API 行为），
     * 所以需要二次验证：节点文本必须**精确包含**关键词，
     * 且关键词不能只是更长文本的一部分（如 "支付成功率" 不应匹配 "支付成功"）。
     *
     * 借鉴反编译项目：它对每个 findByText 结果都做了 getText().equals(str) 的二次校验。
     */
    private fun findMatchedKeyword(root: AccessibilityNodeInfo, keywords: List<String>): String? {
        for (kw in keywords) {
            val nodes = root.findAccessibilityNodeInfosByText(kw)
            if (nodes.isNullOrEmpty()) continue
            for (node in nodes) {
                val text = node.text?.toString()?.trim() ?: ""
                val desc = node.contentDescription?.toString()?.trim() ?: ""
                // 精确匹配：节点文本等于关键词，或文本短于关键词的 3 倍（避免在长段落中误命中）
                if (isExactKeywordMatch(text, kw) || isExactKeywordMatch(desc, kw)) {
                    return kw
                }
            }
        }
        return null
    }

    /**
     * 精确关键词匹配：
     * - 文本等于关键词 → 匹配
     * - 文本很短（<= 关键词长度 * 3）且包含关键词 → 匹配（如 "支付成功！"）
     * - 文本很长（如整段描述）且包含关键词 → 不匹配（避免 "查看支付成功的订单" 误触发）
     */
    private fun isExactKeywordMatch(text: String, keyword: String): Boolean {
        return PaymentAccessibilityRecognition.isSuccessKeywordMatch(text, keyword)
    }

    /** 从金额文本中提取整数分（如 "¥12.50" → 1250）；解析不出返回 null */
    private fun extractNumericAmountCents(amountText: String): Int? =
        PaymentAccessibilityRecognition.amountKey(amountText)?.toIntOrNull()

    /** 在节点树中找第一个匹配金额格式的文本（正则来自 profile/全局配置） */
    private fun findAmount(root: AccessibilityNodeInfo, regex: Regex): String? {
        return findTextByPattern(root, regex)
    }

    /** 查找商家名：找配置标签（`a11y.merchant_labels` / profile 覆盖）附近的文字。 */
    private fun findMerchant(root: AccessibilityNodeInfo, labels: List<String>): String? {
        for (label in labels) {
            val nodes = root.findAccessibilityNodeInfosByText(label)
            if (nodes.isNullOrEmpty()) continue
            for (node in nodes) {
                val parent = node.parent
                if (parent != null) {
                    for (i in 0 until parent.childCount) {
                        val child = parent.getChild(i) ?: continue
                        val text = child.text?.toString()?.trim()
                        if (!text.isNullOrBlank() && text != label && text.length in 2..30 && !text.contains("¥")) {
                            return text
                        }
                    }
                }
            }
        }
        return null
    }

    /**
     * 判断页面是否是"刚刚发生的支付"而非"历史账单"。
     *
     * v4 改进：只检查金额节点附近（上下 5 个节点）的日期文本，
     * 避免页面底部/顶部无关文本（如 "昨天 13:00 发货"）触发误杀。
     */
    private fun isRecentPayment(allTexts: List<String>, amountText: String): Boolean {
        // 找到金额所在位置，只检查附近范围
        val amountClean = amountText.replace("¥", "").replace("￥", "")
        val amountIdx = allTexts.indexOfFirst { it.contains(amountClean) }
        val checkRange = if (amountIdx >= 0) {
            val start = (amountIdx - 5).coerceAtLeast(0)
            val end = (amountIdx + 5).coerceAtMost(allTexts.size - 1)
            allTexts.subList(start, end + 1)
        } else {
            // 找不到金额位置时，只检查前 15 个节点（支付结果通常在页面上方）
            allTexts.take(15)
        }

        return PaymentAccessibilityRecognition.isRecentTextRange(checkRange, java.time.LocalDate.now())
    }

    /**
     * 收集金额节点附近的文本作为上下文。
     */
    private fun collectNearbyText(allTexts: List<String>, amountText: String): String {
        val amountIdx = allTexts.indexOfFirst { it.contains(amountText.replace("¥", "").replace("￥", "")) }
        if (amountIdx < 0) return allTexts.take(5).joinToString(" ")
        val start = (amountIdx - 3).coerceAtLeast(0)
        val end = (amountIdx + 3).coerceAtMost(allTexts.size - 1)
        return allTexts.subList(start, end + 1)
            .filter { it != amountText && it.length in 2..30 }
            .joinToString(" ")
            .take(80)
    }

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

    /**
     * A11Y 专用：把整页文本（复用 [collectAllNodeTexts] 结果）去噪后拼成 [Capture.pageText]。
     *
     * 服务于「判列表页」这一页面级结构判据，**不参与 AI 输入**（AI 仍只看 `fullText`=summary）。
     * 去噪刻意简单：丢掉长度 <2、纯数字/纯符号项，`distinct()` 去重，最多 60 个节点、500 字。
     */
    private fun buildPageText(allTexts: List<String>): String =
        allTexts.asSequence()
            .map(String::trim)
            .filter { it.length >= 2 }
            .filterNot(::isNoiseText)
            .distinct()
            .take(PAGE_TEXT_MAX_NODES)
            .joinToString(" ")
            .take(PAGE_TEXT_MAX_CHARS)

    /** 纯数字（`32.50` / `1,000`）或纯符号（`¥` / `——`）对判「列表页」无信息量。 */
    private fun isNoiseText(text: String): Boolean {
        val pureNumber = text.all { it.isDigit() || it == '.' || it == ',' }
        val pureSymbol = text.none { it.isLetterOrDigit() }
        return pureNumber || pureSymbol
    }

    /**
     * 构建节点树摘要字符串（用于日志分析页面结构）。
     */
    private fun buildNodeTree(node: AccessibilityNodeInfo, maxDepth: Int, depth: Int = 0): String {
        if (depth > maxDepth) return ""
        val sb = StringBuilder()
        val viewId = node.viewIdResourceName?.substringAfterLast('/') ?: ""
        val text = node.text?.toString()?.trim()?.take(30) ?: ""
        val desc = node.contentDescription?.toString()?.trim()?.take(30) ?: ""
        val cls = node.className?.toString()?.substringAfterLast('.') ?: ""

        if (text.isNotBlank() || desc.isNotBlank() || viewId.isNotBlank()) {
            sb.append("L$depth:")
            if (viewId.isNotBlank()) sb.append("[$viewId]")
            if (text.isNotBlank()) sb.append("\"$text\"")
            if (desc.isNotBlank()) sb.append("{$desc}")
            sb.append("($cls)")
            sb.append("|")
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            sb.append(buildNodeTree(child, maxDepth, depth + 1))
        }
        return if (sb.length > 500) sb.substring(0, 500) + "..." else sb.toString()
    }

    /** 递归查找匹配正则的节点文本 */
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
