package com.aibill.android.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.util.AppLogger
import com.aibill.android.util.NotificationHelper
import com.aibill.android.util.NotificationParser
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 通知监听服务 v4
 *
 * 架构：排除层 → 内存去重 → DB 1s 去重 → 直接交给 NotificationProcessor
 *
 * v4 改动：
 * - 砍掉 5s 合并池（合并出 800+ 字把 AI 后端打挂，HTTP 400）
 * - 砍掉 NotificationBuffer 长窗口去重（移到 NotificationProcessor 里按金额后置去重）
 * - 每条通知直接调 AI，短文本（≤ 200 字）通过率 100%
 */
/**
 * NLS 文本分类：识别「统计摘要」类通知。
 *
 * 单独一个 object 而不是内联到 [NotificationMonitorService]——它是纯函数，
 * 可直接单测（服务里无法直接测）。
 */
internal object NlsTextClassifier {

    /**
     * 是否为「汇总/日报」类通知——这类是**统计摘要**而不是单笔交易，
     * 记进账本会造成重复/虚假消费。
     *
     * 背景（2026-10-05 服务端 `/api/admin/ai-parse-logs` 全量 1403 条实测）：
     * ```
     * 【记账日报】昨天共有1笔支出 昨日支出28.90元，共1笔              → 被记成 ¥28.90
     * 【记账日报】昨日消费支出比平日高500.77% 昨日支出1868.98元，共2笔 → 被记成 ¥1868.98
     * ```
     * 这类通知的金额是**昨日/今日总计**，不是新增交易；且当天的单笔通常已由
     * 银行/支付渠道单独通知记过一次 → 造成重复记账。
     *
     * **为什么不能靠 `default_rule.exclude_content_contains` 拦**：
     * 词表命中后还要过 `STRONG_TXN_MARKERS` 豁免（见 NotificationRulesManager），
     * 而日报文案里含「支出」（第二条例还含「消费」），两者都在 markers 里
     * → 豁免生效照样放行。把「支出」移出 markers 会误杀 61 条真实交易
     * （实测它们仅靠这一个词豁免）。所以走独立的代码层判定。
     *
     * **特征选择**：用「共 N 笔」（数字 + 量词「笔」）。
     * 真实账单通知写的是「今日消费1次，共支出93.00元」——量词是「次」「元」而非「笔」；
     * 实测 1403 条里「共N笔」只出现在日报汇总中，真交易 0 例。
     */
    private val SUMMARY_PATTERNS = listOf(
        Regex("""记账日报|消费日报|账单日报|收支日报|每日账单"""),
        Regex("""(昨日|今日|本日|上周|本月).{0,12}共\s*\d+\s*笔"""),
        Regex("""共\s*\d+\s*笔"""),
    )

    fun isSummaryNotification(text: String): Boolean =
        SUMMARY_PATTERNS.any { it.containsMatchIn(text) }

    /**
     * 是否为**促销句式**——「看起来像账务、实际是广告」的结构性特征。
     *
     * 2026-10-09 21:04 真实漏记（com.netease.yanxuan）：
     * ```
     * 🧧双11省钱卡已开抢！ 双11省钱卡花3得29，一笔回本🔥加赠99积分0元兑云音乐月卡👉
     * ```
     * 58 个 `exclude_content_contains` 一个都没命中：文本有 🧧 但没「红包」二字、
     * 「0元兑」不是词表里的「0元领」、「省钱卡」不在表内。而 `payment_signal_regex`
     * 里的裸「元」被「0元兑」命中 → 放行调 AI → AI 把促销句式**「花3得29」的 3**
     * 当成金额返回 ¥3.00。
     *
     * ## ⚠️ 为什么这里**只留句式、不留营销词**
     *
     * 最初这里还写了 `开抢|回本|省钱卡|省钱券|神券|膨胀券`，自己审的时候发现
     * **`省钱卡`是招行/中信的真实支付方式名**——用户用省钱卡刷卡时，通知里就会
     * 出现「您尾号1234的招行省钱卡支付成功」，会被无差别丢掉。
     * 这与服务端 verify.ts 踩过的坑完全同类（那里是 `首页` 命中弱噪声，误杀了
     * `支付成功 ￥30.26 … 好想来零食乐园 完成`）。
     *
     * 服务端已经把营销词做进 `WEAK_NOISE`，且**正向信号优先**（有金额+交易动词
     * 就不查弱噪声）。所以客户端这边只保留**不会与真实交易用词冲突的结构性句式**：
     * `花N得M` 里的「得」是促销语法，真实交易描述里不出现。
     *
     * 宁可漏放（多调一次 AI，被校验 5 拦成待审）也不误杀——这是本模块一贯的取舍。
     */
    private val PROMO_PATTERNS = listOf(
        // 花3得29 / 花100得200：促销语法，真实交易描述不会这么写。
        // 中间容忍单位与量词：花3**元得**29元 / 花3得**券**29 同样要拦。
        //
        // ⚠️ 不加这个容忍，kiro 找到一个双层同时失效的绕过：
        // 「花3元得29元」既不匹配严格句式，而 AI 把 3 当金额时**「3元」自带货币单位**
        // → 校验 5 也认它有佐证 → 自动入账。促销拦截与金额佐证两层同时被打穿，
        // 正好是本次改动要杀的那类 bug。
        Regex("""花\s*\d+(?:\.\d+)?\s*\S{0,2}?\s*得\s*\S{0,2}?\s*\d+"""),
        // 0元兑 / 1元送 / 20元抵扣：带边界的零元兑换，避免命中「3.90元」
        Regex("""(?<![\d.])0\s*元\s*[兑换送赠换抵领购]"""),
        // 加赠99积分：赠品而非付款
        Regex("""加赠\s*\d+"""),
    )

    /**
     * 促销句式判定。与 [isSummaryNotification] 一样放在这里（而不是 `rules.json`），
     * 原因见类 KDoc：不改云控、不影响在线用户放行率。
     *
     * 本函数**只挡结构性促销句式**，营销词交给服务端——见 [PROMO_PATTERNS] 的说明。
     */
    fun isPromoNotification(text: String): Boolean =
        PROMO_PATTERNS.any { it.containsMatchIn(text) }
}

class NotificationMonitorService : NotificationListenerService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface NlsEntryPoint {
        fun notificationParser(): NotificationParser
        fun notificationRecordDao(): NotificationRecordDao
        fun notificationProcessor(): NotificationProcessor
        fun appLogger(): AppLogger
        fun rulesManager(): NotificationRulesManager
        @com.aibill.android.di.ApplicationScope
        fun applicationScope(): kotlinx.coroutines.CoroutineScope
    }

    private lateinit var notificationParser: NotificationParser
    private lateinit var notificationRecordDao: NotificationRecordDao
    private lateinit var notificationProcessor: NotificationProcessor
    private lateinit var appLogger: AppLogger
    private lateinit var rulesManager: NotificationRulesManager

    // PR 修复：改用进程级 @ApplicationScope，
    // 避免 NLS 每次 onCreate 时泄漏一个 SupervisorJob+IO。
    private lateinit var serviceScope: kotlinx.coroutines.CoroutineScope

    // ═══════════════════════════════════════════════════════════════
    // 从云控规则读取
    // ═══════════════════════════════════════════════════════════════
    private var paymentSignalRegex: Regex = PAYMENT_SIGNAL
    private var wechatDirectPassTitles: List<String> = emptyList()
    private var wechatDirectPassTitleContains: List<String> = emptyList()
    private var wechatMessagePrefixes: List<String> = emptyList()
    private var wechatAmountSymbols: List<String> = emptyList()
    private var alipayAllowedTitleKeywords: List<String> = emptyList()
    private var bankPackagePatterns: List<String> = emptyList()
    private var smsSpamKeywords: List<String> = emptyList()
    private var smsPackages: Set<String> = emptySet()
    private var perPackageRules: List<com.aibill.android.service.PerPackageRule> = emptyList()
    private var defaultExcludeContent: List<String> = emptyList()

    /** 跟踪已加载的规则代际，避免重复 load */
    private var lastRulesGeneration: Int = -1

    companion object {
        private const val DEDUP_WINDOW_MS = 1000L

        /** 内部内容去重窗口：同内容 hash 在此窗口内只处理一次 */
        private const val CONTENT_DEDUP_WINDOW_MS = 5_000L

        /**
         * 已送 AI 的内容记忆窗口。
         *
         * 5s 短窗挡不住系统对同一通知的反复 post（实测资讯类推送间隔
         * 6s / 12s / 5min / 25min），导致同一条新闻标题重复烧 AI 且连续
         * 5001。长窗命中只跳过「调 AI」，DB 记录与去重逻辑不受影响。
         */
        private const val AI_CONTENT_MEMORY_MS = 5 * 60_000L

        /** 去重日志采样间隔：同一包名的去重命中最多每分钟记一条 */
        private const val DEDUP_LOG_SAMPLE_MS = 60_000L

        /**
         * 支付特征关键词正则（向后兼容：SmsReceiverService 引用此字段）。
         * 运行时会被 rulesManager 覆盖，这里保留作为 static fallback。
         */
        val PAYMENT_SIGNAL = Regex(
            "[¥￥$]|RMB|CNY|人民币|元|支付|已付|付款|实付|付出|刷卡|收款|收入|到账|入账|" +
            "转入|转出|转账|汇款|消费|交易|扣款|扣费|代扣|缴费|充值|提现|退款|退货|红包|" +
            "余额|账单|还款|欠款|尾号|卡号|信用卡|储蓄卡|银行卡|收益|利息|分期|贷款|工资|薪资|报销"
        )

        /** 全局可读的 NLS 连接状态，供权限引导页检测"权限有但未连接" */
        @Volatile
        var isConnected: Boolean = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createNotificationChannel(this)

        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, NlsEntryPoint::class.java)
        notificationParser = entryPoint.notificationParser()
        notificationRecordDao = entryPoint.notificationRecordDao()
        notificationProcessor = entryPoint.notificationProcessor()
        appLogger = entryPoint.appLogger()
        rulesManager = entryPoint.rulesManager()
        serviceScope = entryPoint.applicationScope()

        loadRules()
    }

    private fun loadRules() {
        val snapshot = rulesManager.getSnapshot()
        val rules = snapshot.rules
        val currentGen = snapshot.generation

        paymentSignalRegex = try {
            Regex(rules.nls.paymentSignalRegex)
        } catch (e: Exception) {
            Timber.w(e, "NLS: invalid paymentSignalRegex from rules, using hardcoded fallback")
            PAYMENT_SIGNAL
        }
        wechatDirectPassTitles = rules.nls.wechat.directPassTitles
        wechatDirectPassTitleContains = rules.nls.wechat.directPassTitleContains
        wechatMessagePrefixes = rules.nls.wechat.messagePrefixes
        wechatAmountSymbols = rules.nls.wechat.amountSymbols
        alipayAllowedTitleKeywords = rules.nls.alipay.allowedTitleKeywords
        bankPackagePatterns = rules.nls.bankPackagePatterns
        smsSpamKeywords = rules.sms.spamKeywords
        smsPackages = rules.nls.smsPackages.toSet()
        perPackageRules = rules.nls.perPackage
        defaultExcludeContent = rules.nls.defaultExcludeContent
        lastRulesGeneration = currentGen

        appLogger.info(
            "NLS",
            "规则已加载 app=${com.aibill.android.BuildConfig.VERSION_NAME}" +
                "(${com.aibill.android.BuildConfig.VERSION_CODE}) gen=$currentGen " +
                "wechatDirectPassTitles=${wechatDirectPassTitles.size} " +
                "bankPatterns=${bankPackagePatterns.size} " +
                "spamKeywords=${smsSpamKeywords.size} " +
                "knownPackages=${rulesManager.getAllKnownPackages().size}"
        )
    }

    /**
     * 如果规则已更新（代际变化），则重新加载本地派生值。
     * 每次 onNotificationPosted 调用，避免创建新 Notification 时规则滞后。
     */
    private fun refreshRulesIfNeeded() {
        val currentGen = rulesManager.getSnapshot().generation
        if (currentGen != lastRulesGeneration) {
            Timber.d("NLS: rules generation changed $lastRulesGeneration → $currentGen, reloading...")
            loadRules()
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        appLogger.info("NLS", "通知监听服务已连接")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        appLogger.warn("NLS", "通知监听服务断开，尝试自动恢复")
        try {
            requestRebind(android.content.ComponentName(this, NotificationMonitorService::class.java))
            appLogger.info("NLS", "已请求 requestRebind")
        } catch (e: Exception) {
            appLogger.error("NLS", "requestRebind 失败: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isConnected = false
        // ★ 切勿在这里 cancel serviceScope！
        // serviceScope 是进程级 @ApplicationScope（Hilt @Singleton，全 App 共享：
        // SMS/A11Y/外部 Intent/小组件等所有协程都跑在它上面）。
        // NLS 被系统 requestRebind 销毁重建后，cancel 会让整个 App 的协程
        // 静默失效（launch 不执行也不抛异常）：通知零日志、AI 零调用，
        // 直到进程被杀才恢复。（2026-09 回归根因，与 PaymentAccessibilityService
        // 的处理保持一致：进程级 Scope 由 CoroutineScopeModule 持有，不在 Service 里 cancel。）
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        serviceScope.launch { handleNotification(notification) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // v3: 不再做通知撤回降级，AI 入库的就是对的
    }

    // ═══════════════════════════════════════════════════════════════
    // L1: 排除层 → L2: 内存去重 → L3: DB 去重 → 交给 Processor
    // ═══════════════════════════════════════════════════════════════

    /** 内存级去重：防止系统短时间内对同一通知多次触发 onNotificationPosted */
    private val recentNotificationKeys = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * 已送 AI 的内容指纹（长窗）。
     * 放在过滤层之后：只挡「同一条内容被系统反复 post」导致的重复 AI 调用，
     * 不会影响不同内容的正常交易通知。
     */
    private val aiCalledContentKeys = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 去重日志采样：同一包名的去重命中日志最小间隔 */
    private val lastDedupLoggedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private suspend fun handleNotification(sbn: StatusBarNotification) {
        // 1. 动态包名白名单（含云控 sourceMapping + smsPackages + bankPatterns）
        val packageName = sbn.packageName ?: return

        // 按需刷新规则（代际无变化则跳过）
        refreshRulesIfNeeded()

        // ★ 自身通知必须丢弃（2026-10-01 日志回归）。
        // 本 App 自己会发两类通知：自动入库的「已记账 · …」和待审的
        // 「💰 检测到一笔支出 …」。它们会被自己的 NLS 当成新账务再解析一遍，
        // 实测 16 次 AI 调用里 3 次是自激，产生的第二个候选靠
        // 60s 金额去重侥幸挡下（AI 稍慢就会真的记两笔）。
        // 手动记账 / 小组件 / 外部 Intent 走 AutoRecordActionReceiver 直写 DB，
        // 不经过 NLS，所以这里丢弃自身通知零副作用。
        if (packageName == applicationContext.packageName) {
            appLogger.debug("NLS", "自身通知，跳过: pkg=$packageName")
            return
        }

        // 2. 提取通知文本
        val extras = sbn.notification?.extras ?: return
        val title = sanitizeNotificationText(
            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        )
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString().orEmpty()

        // 合并文本：bigText 是 text 的展开完整版，优先用 bigText（text 可能是截断摘要）。
        // 群聊类通知（QQ/微信）的 bigText 往往以 title 开头，此时不再重复拼 title，
        // 否则 AI 会收到「209嗨 209嗨 太越林上火型: …」这种双份噪音。
        val body = sanitizeNotificationText(bigText.ifBlank { text })
        val fullText = buildFullText(title, body, subText, infoText)

        // 0. 内存级去重（用内容hash，防系统重复触发+协程竞态）。
        //    必须排在下面所有早退分支之前：系统「正在运行」分组摘要会以
        //    完全相同的文本被 post 数十次（实测单秒 10 条），原先排在
        //    分支之后导致这层去重对它们完全失效。
        val contentKey = "$packageName:${fullText.hashCode()}"
        val now = System.currentTimeMillis()
        val lastSeen = recentNotificationKeys.put(contentKey, now)
        if (lastSeen != null && (now - lastSeen) < CONTENT_DEDUP_WINDOW_MS) {
            // 去重是常态（微信视频通话、系统服务会刷出大量重复通知），
            // 每次都写日志会瞬间冲掉账务结果行（曾出现单秒 6 条）。
            // 采样：同一包名 60s 内只记一次。
            if (now - (lastDedupLoggedAt[packageName] ?: 0L) > DEDUP_LOG_SAMPLE_MS) {
                lastDedupLoggedAt[packageName] = now
                appLogger.debug("NLS", "内存去重(${CONTENT_DEDUP_WINDOW_MS}ms): pkg=$packageName")
            }
            return
        }
        // 每次清理过期 key（>10s），防泄漏
        recentNotificationKeys.entries.removeIf { now - it.value > 10_000L }

        if (fullText.isBlank()) {
            appLogger.debug("NLS", "空文本: pkg=$packageName")
            return
        }

        // 排除系统运行通知和通知分组摘要（无意义）。
        // 注：分组摘要在 MIUI 上表现为 title 就是「"短信"正在运行」的
        // 独立通知（text 为空），不是靠类名匹配，所以只判正文。
        if (fullText.contains("正在运行")) {
            appLogger.debug("NLS", "系统/分组摘要: pkg=$packageName title=$title")
            return
        }

        // 排除「记账日报」等统计汇总通知：金额是区间总计而非单笔交易。
        // 必须早于营销词表判断——日报含「支出」会触发强交易特征豁免，
        // 靠词表拦不住（实测 1403 条 AI 日志里被误记 2 次）。
        if (NlsTextClassifier.isSummaryNotification(fullText)) {
            appLogger.debug("NLS", "汇总/日报通知（非单笔交易）: pkg=$packageName text=${fullText.take(50)}")
            return
        }

        // 排除「促销句式」推送：花3得29 / 0元兑 / 加赠99积分 这类结构，
        // 看着有数字有「元」但不是真实付款（会从促销句式里幻觉出金额）。
        // 必须排在调 AI 之前——排后面就只是把噪音从待审池挪到记账失败。
        if (NlsTextClassifier.isPromoNotification(fullText)) {
            appLogger.debug("NLS", "促销推送拦截: pkg=$packageName text=${fullText.take(50)}")
            return
        }

        // 排除营销/广告/优惠券推送（含金额关键词但不是真实账务）——所有渠道生效
        if (isLikelySpamSms(fullText)) {
            appLogger.debug("NLS", "营销推送拦截: pkg=$packageName text=${fullText.take(50)}")
            return
        }

        // 3. 排除层：过滤明显不是账务的通知
        if (!isLikelyFinancial(packageName, title, fullText)) {
            appLogger.debug("NLS", "排除(非账务): pkg=$packageName title=${title.take(30)} text=${fullText.take(50)}")
            return
        }

        // v6 default_rule 的设计承诺："新增 App 默认自动生效，无需配置"。
        // 白名单不再一票否决，仅用于日志观测（区分已知/未知来源）——移到
        // 这里打：它原本在函数最前面无条件打印，占了日志表 ~1/3 的行，
        // 把真正的账务结果行挤出了导出窗口。
        val knownSource = rulesManager.isKnownOrBankPackage(packageName)
        if (!knownSource) {
            appLogger.debug("NLS", "包名不在白名单，走 default_rule 兜底: pkg=$packageName")
        }

        appLogger.info("NLS", "✓通过过滤: pkg=$packageName title=${title.take(30)} fullText=${fullText.take(80)}")

        // 4. 长窗内容记忆：同一条内容 5 分钟内不重复调 AI。
        //    资讯类 App 会把同一条推送反复 post（实测 6 次调 AI / 5 次 5001），
        //    5s 短窗拦不住，长窗把重复调用压到 1 次。
        val aiMemoryKey = "$packageName:${fullText.hashCode()}"
        aiCalledContentKeys.entries.removeIf { now - it.value > AI_CONTENT_MEMORY_MS }
        val lastAiCall = aiCalledContentKeys[aiMemoryKey]
        if (lastAiCall != null && (now - lastAiCall) < AI_CONTENT_MEMORY_MS) {
            appLogger.debug(
                "NLS",
                "长窗内容记忆(${AI_CONTENT_MEMORY_MS / 60_000}min)，跳过重复调AI: pkg=$packageName"
            )
            return
        }

        // 5. 单包 AI 熔断：非支付类包名连续 AI 失败 N 次后静音一段时间。
        //    只对「不在白名单」的包生效，银行/微信/支付宝永不被熔断，
        //    避免真交易因网络抖动被误杀。
        if (!knownSource && notificationProcessor.isAiMuted(packageName)) {
            appLogger.debug("NLS", "AI 熔断中（该包连续失败），跳过: pkg=$packageName")
            return
        }
        aiCalledContentKeys[aiMemoryKey] = now

        // 6. 1s 内容去重（防同一条通知被系统多次分发）
        val since = System.currentTimeMillis() - DEDUP_WINDOW_MS
        val duplicate = notificationRecordDao.findDuplicate(packageName, fullText, since)
        if (duplicate != null) {
            appLogger.debug("NLS", "DB去重(1s): pkg=$packageName")
            return
        }

        // 7. 直接交给 Processor（AI + 后置按金额去重）
        notificationProcessor.process(
            NotificationProcessor.Item(
                packageName = packageName,
                title = title,
                fullText = fullText,
                channel = NotificationProcessor.Channel.NLS,
            )
        )
    }

    /**
     * 合并 title / 正文 / 副标题，title 若已是正文前缀则不重复拼接。
     */
    private fun buildFullText(title: String, body: String, subText: String, infoText: String): String {
        val parts = mutableListOf<String>()
        if (title.isNotBlank() && !body.startsWith(title)) parts.add(title)
        if (body.isNotBlank()) parts.add(body)
        if (subText.isNotBlank()) parts.add(sanitizeNotificationText(subText))
        if (infoText.isNotBlank()) parts.add(sanitizeNotificationText(infoText))
        return parts.joinToString(" ").trim()
    }

    /**
     * 清洗通知原文：剔除控制字符 / 私用区字符 / 变体选择符 / 零宽字符。
     *
     * 群名里的 emoji 与代理对经常混进私用区码位（如日志里的 `209<$ÿĀ>`），
     * 原样送 AI 既浪费 token 又可能触发后端 5001。
     * 只做减法（黑名单），不做白名单过滤，避免误删正常标点与 CJK。
     */
    private fun sanitizeNotificationText(raw: String): String {
        if (raw.isEmpty()) return raw
        val sb = StringBuilder(raw.length)
        var lastWasSpace = false
        for (ch in raw) {
            val code = ch.code
            val drop = code < 0x20 ||                       // C0 控制字符（\n \t 交给下面的空白归一）
                    code == 0x7F ||                        // DEL
                    code == 0xFFFD ||                      // 替换字符 U+FFFD
                    code in 0xE000..0xF8FF ||               // 私用区
                    code in 0xF0000..0xFFFFD ||             // 私用区（增补平面 A）
                    code in 0x100000..0x10FFFD ||           // 私用区（增补平面 B）
                    code in 0xFE00..0xFE0F ||               // 变体选择符
                    code in 0x200B..0x200F ||               // 零宽字符
                    code == 0x2060 ||                      // 单词连接符
                    Character.getType(ch) == Character.FORMAT.toInt()
            if (drop) continue
            val isSpace = ch.isWhitespace()
            if (isSpace) {
                if (!lastWasSpace && sb.isNotEmpty()) sb.append(' ')
            } else {
                sb.append(ch)
            }
            lastWasSpace = isSpace
        }
        return sb.toString().trim()
    }

    /**
     * 排除层：判断通知是否"可能是账务"。
     * 返回 false = 100% 不是账务，直接丢弃。
     * 返回 true = 不确定，交给 AI 判断。
     * 设计原则：极保守，宁可多放不漏。
     */
    private fun isLikelyFinancial(packageName: String, title: String, fullText: String): Boolean {
        // 优先1：per_package 精确配置（格式特殊的App，如微信/支付宝）
        val cfg = perPackageRules.firstOrNull { it.matches(packageName) }
        if (cfg != null) {
            return evaluateByConfig(cfg, title, fullText)
        }
        // 优先2：default_rule（大多数App走这套：营销排除 + payment_signal 放行）
        if (defaultExcludeContent.isNotEmpty()) {
            // 营销词命中但含强交易特征（尾号/卡号/入账…）时交 AI 判定（宁可多放不漏）
            if (rulesManager.isLikelyMarketing(fullText)) return false
            return paymentSignalRegex.containsMatchIn(fullText)
        }
        // 回退：旧硬编码逻辑（default_rule 也为空时，向后兼容）
        return legacyIsLikelyFinancial(packageName, title, fullText)
    }

    /**
     * 规则驱动的通用判断（per_package 配置）。
     * 顺序：排除词（title/content）→ pass_all → title/前缀/金额符号放行。
     */
    private fun evaluateByConfig(cfg: com.aibill.android.service.PerPackageRule, title: String, fullText: String): Boolean {
        // 1. 排除词优先（命中直接拒绝）
        if (cfg.excludeTitleContains.any { title.contains(it) }) return false
        // 正文排除词命中时，与 default_rule 分支保持**同一语义**：含强交易特征
        // （尾号/卡号/入账/支出…）时不否决，交给后续放行条件 / AI 判定。
        // 否则会出现「营销词 + 真交易」被误杀（详见 NotificationRulesManager.isMarketingByWords）。
        if (NotificationRulesManager.isMarketingByWords(fullText, cfg.excludeContentContains)) return false
        // 2. 全放行
        if (cfg.passAll) return true
        // 3. title 精确/包含放行
        if (cfg.passTitleExact.any { title == it }) return true
        if (cfg.passTitleContains.any { title.contains(it) }) return true
        // 4. 正文前缀放行
        val textAfterTitle = fullText.substringAfter(title).trim()
        if (cfg.passMsgPrefix.any { textAfterTitle.startsWith(it) }) return true
        // 5. 金额符号放行
        if (cfg.requireAmountSymbol) {
            return wechatAmountSymbols.any { textAfterTitle.contains(it) } ||
                fullText.contains("¥") || fullText.contains("￥")
        }
        return false
    }

    /** 旧硬编码逻辑，保证向后兼容 */
    private fun legacyIsLikelyFinancial(packageName: String, title: String, fullText: String): Boolean {
        return when (packageName) {
            "com.tencent.mm" -> {
                if (wechatDirectPassTitles.any { title == it }) return true
                if (wechatDirectPassTitleContains.any { title.contains(it) }) return true
                val textAfterTitle = fullText.substringAfter(title).trim()
                if (wechatMessagePrefixes.any { textAfterTitle.startsWith(it) }) return true
                wechatAmountSymbols.any { textAfterTitle.contains(it) }
            }
            "com.eg.android.AlipayGphone" -> {
                alipayAllowedTitleKeywords.any { title.contains(it) }
            }
            else -> {
                if (bankPackagePatterns.any { packageName.contains(it) || packageName.startsWith(it) }) return true
                paymentSignalRegex.containsMatchIn(fullText)
            }
        }
    }

    /**
     * 判断短信是否是营销/广告/订购类（含金额关键词但不是真实账务）。
     */
    private fun isLikelySpamSms(text: String): Boolean {
        return rulesManager.isLikelyMarketing(text)
    }
}
