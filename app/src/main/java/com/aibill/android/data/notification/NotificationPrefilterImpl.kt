package com.aibill.android.data.notification

import android.content.Context
import com.aibill.android.data.local.dao.NotificationRecordDao
import com.aibill.android.domain.notification.AiCircuitBreaker
import com.aibill.android.domain.notification.BlockAction
import com.aibill.android.domain.notification.BlockReason
import com.aibill.android.domain.notification.Capture
import com.aibill.android.domain.notification.CaptureKind
import com.aibill.android.domain.notification.CaptureSampleInput
import com.aibill.android.domain.notification.CaptureSampler
import com.aibill.android.domain.notification.CaptureStage
import com.aibill.android.domain.notification.MarketingCriteria
import com.aibill.android.domain.notification.MarketingSuffixCleaner
import com.aibill.android.domain.notification.NotificationRuleSnapshot
import com.aibill.android.domain.notification.NotificationPrefilter
import com.aibill.android.domain.notification.NotificationRuleSource
import com.aibill.android.domain.notification.PrefilterStats
import com.aibill.android.domain.notification.NotificationTextClassifier
import com.aibill.android.domain.notification.PerPackageCriteria
import com.aibill.android.domain.notification.PrecheckJudge
import com.aibill.android.domain.notification.PrecheckVerdict
import com.aibill.android.domain.notification.SourceChannel
import com.aibill.android.domain.notification.Verdict
import com.aibill.android.domain.a11y.A11yRecognition
import com.aibill.android.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 预筛 module 的实现：判据链 + 每源状态。
 *
 * 独占：全部文本判据（自身通知 / 空文本 / 系统摘要 / 日报汇总 / 促销句式 / 营销词 /
 * 账务判据）、内容级去重与记忆状态（内存 5s 内容去重、长窗 5min AI 内容记忆、DB 1s 去重）、
 * 单包 AI 熔断的**查询**（状态归 `domain/notification/AiCircuitBreaker`）、以及全部相关日志。
 * 三个 adapter 只负责把平台对象翻译成 [Capture] 并在放行时调用 `process`。
 *
 * ## 判定顺序（顺序本身是行为，不要随手调）
 *
 * ```
 * (-1) A11Y 账单/明细列表页【仅 A11Y 且 adapter 填了 pageText】
 * 0 自身通知   → 1 内存内容去重(5s)【仅 NLS】→ 2 空文本 → 3 系统分组摘要
 * → 4 日报汇总 → 5 促销句式 → 6 营销词 → 7 账务判据
 * → 8 长窗 AI 内容记忆(5min)【仅 NLS】→ 9 单包 AI 熔断【仅 NLS】→ 10 DB 1s 去重【仅 NLS】
 * → 11 云控 precheck（tier 0/4 拦、1-3 放行；不可用/未知 schema 降级放行）
 * ```
 *
 * ## 归表（契约附录 C §11.2）：判定链不变，**处置**可变
 *
 * 每个拦截点在返回前查一次 `prefilter_policy`（键 = 上报键，[PrefilterStats.clientOnlyKey]
 * 或云控自己的 `precheck.<reason>`）：
 *
 * - `block`（缺省）→ 与今天逐字一致；
 * - `forward` → **不拦，继续走完后续判据链**（不是提前放行：后面的判据仍可能拦它）；
 *   整条链都没拦 → 交给下游 `interpreter.read` → `POST /api/ai/parse`，由服务端预筛判并落
 *   `ai_parse_filters`（含原文）。原则：**正则与 AI 冲突以 AI 为准**；
 * - `sample` → 照旧拦 + 采一条样本（§11.3）。
 *
 * 代价是 `forward` 的条目会**计入 passed**（它们确实去调 AI 了），拦截率随之下降——
 * 这是对的，归表改的是「谁判」，不是「谁拦」。
 *
 * - **1 必须排在所有早退分支之前**：系统「正在运行」分组摘要会以完全相同的文本
 *   post 数十次（实测单秒 10 条），原先排在分支之后对它们完全失效；
 * - **4/5 必须早于 6**：日报含「支出」会触发营销词表的强交易特征豁免，靠词表拦不住
 *   （实测 1403 条 AI 日志里被误记 2 次）；促销句式同理（2026-10-09 双11漏记）；
 * - **7 的「宁可不漏」优先于「拦得干净」**：强交易特征豁免是这条链的核心取舍。
 *
 * ## 为什么 1/8/9/10 四道闸只对 NLS 生效（刻意的差异，不要顺手统一）
 *
 * **文本判据（0/2/3/4/5/6/7）三渠道一致；状态闸（内容去重 / 内容记忆 / AI 熔断 / DB 去重）只对 NLS。**
 *
 * 这四道闸是为 **NLS 的噪音** 发明的：资讯 App 把同一条推送反复 post、系统「正在运行」分组摘要
 * 单秒来 10 条、AI 对同一条新闻标题连续报 5001。它们压的都是「同一条内容被系统反复投递」。
 *
 * 而下游 `ScoredAdmission` 的设计原则明写着：**同渠道同包名同金额 = 两笔真实交易**
 * （如 10s 内两杯 ¥15 咖啡）。SMS 与 A11Y 恰好落在这条原则覆盖的场景里：
 * 两杯瑞幸、分钟相同、短信正文逐字一样——对 SMS 是**两笔真账**，不是重复投递；
 * A11Y 的「支付成功 ¥15.00 瑞幸咖啡」同理。
 * 闸一旦对它们生效，就是**静默丢真交易**（第二笔连待审都进不去，用户毫无感知）。
 *
 * A11Y 自己已有 8s 内容防抖 + 金额|商家|页面 冷却，那是**平台事实的既定口径**，
 * 不该在预筛再叠一层 5min 的更长的。SMS 侧历史上本就没有任何内容级去重，
 * 收口前只过支付特征 + 营销词就进 `process`——本次把它还原，不做扩权。
 *
 * 取舍与熔断同源：**误杀（丢真交易）> 多调一次 AI**，而这四道闸在 NLS 之外的任何渠道
 * 都只有后者那一种收益。
 */
@Singleton
class NotificationPrefilterImpl @Inject constructor(
    @ApplicationContext context: Context,
    private val ruleSource: NotificationRuleSource,
    private val notificationRecordDao: NotificationRecordDao,
    private val appLogger: AppLogger,
    private val circuitBreaker: AiCircuitBreaker,
    private val stats: PrefilterStats,
    private val sampler: CaptureSampler,
) : NotificationPrefilter {

    private val ownPackageName: String = context.packageName

    /** 内存级内容去重：防 NLS 系统短时间内对同一内容多次投递。key = `包名:内容hash`。 */
    private val recentContentKeys = ConcurrentHashMap<String, Long>()

    /**
     * 已送 AI 的内容指纹（长窗）。放在过滤层之后：只挡「同一条内容被反复 post」
     * 导致的重复 AI 调用，不会影响不同内容的正常交易通知。
     *
     * 仅 NLS 写入与查询：SMS/A11Y 不查内容级闸（理由见类 KDoc）。
     */
    private val aiCalledContentKeys = ConcurrentHashMap<String, Long>()

    /** 去重日志采样：同一包名的去重命中日志最小间隔 */
    private val lastDedupLoggedAt = ConcurrentHashMap<String, Long>()

    /** 「云控规则不可用」诊断只按代际记一次（量纲是规则数，不是通知数） */
    private val lastUnusableRuleGeneration = AtomicInteger(NO_GENERATION)

    override suspend fun evaluate(capture: Capture): Verdict {
        val packageName = capture.packageName
        val fullText = capture.fullText
        val now = capture.receivedAt
        val tag = capture.channel.logTag

        // 内容级状态闸（1/8/10）与 AI 熔断（9）都只为 NLS 开启：
        // 它们压的是「同一条内容被系统反复投递」，而 SMS/A11Y 的同源同文本两条
        // 可能是两笔真实交易（下游同源同额原则），闸在预筛就是丢账。理由见类 KDoc。
        val contentGates = capture.channel == SourceChannel.NLS

        // 规则快照：列表页判据要用云控下发的参数，提到链首取一次
        // （快照按代际缓存，重复取开销可忽略）。
        val rules = ruleSource.snapshot()

        // ── 0（A11Y 专属，最先）：页面级结构判据——账单/明细列表页。
        // 见 listPageVerdict 的 KDoc。
        listPageVerdict(capture, rules)?.let { return it }

        // 0. 自身通知必须丢弃（2026-10-01 日志回归）。
        // 本 App 自己会发两类通知：自动入库的「已记账 · …」和待审的
        // 「💰 检测到一笔支出 …」。它们会被自己的监听当成新账务再解析一遍，
        // 实测 16 次 AI 调用里 3 次是自激，产生的第二个候选靠
        // 60s 金额去重侥幸挡下（AI 稍慢就会真的记两笔）。
        // 手动记账 / 小组件 / 外部 Intent 走 AutoRecordActionReceiver 直写 DB，
        // 不经过本链路，所以这里丢弃自身通知零副作用。
        if (packageName == ownPackageName) {
            block(capture, BlockReason.SELF_NOTIFICATION, "自身通知，跳过: pkg=$packageName", rules = rules)
                ?.let { return it }
        }

        // 1. 内存级去重（用内容 hash，防系统重复触发 + 协程竞态）。仅 NLS。
        if (contentGates) {
            val lastSeen = recentContentKeys.put(contentKeyOf(packageName, fullText), now)
            if (lastSeen != null && (now - lastSeen) < CONTENT_DEDUP_WINDOW_MS) {
                // 去重是常态（微信视频通话、系统服务会刷出大量重复通知），
                // 每次都写日志会瞬间冲掉账务结果行（曾出现单秒 6 条）→ 日志采样；
                // **计数不采样**（计数是判据调参的唯一依据）。
                val shouldLog = now - (lastDedupLoggedAt[packageName] ?: 0L) > DEDUP_LOG_SAMPLE_MS
                if (shouldLog) lastDedupLoggedAt[packageName] = now
                block(
                    capture,
                    BlockReason.DUPLICATE_CONTENT,
                    "内存去重(${CONTENT_DEDUP_WINDOW_MS}ms): pkg=$packageName",
                    log = shouldLog,
                    rules = rules,
                )?.let { return it }
            }
            // 每次清理过期 key（>10s），防泄漏
            recentContentKeys.entries.removeIf { now - it.value > 10_000L }
        }

        // 2. 空文本
        if (fullText.isBlank()) {
            block(capture, BlockReason.BLANK_TEXT, "空文本: pkg=$packageName", rules = rules)
                ?.let { return it }
        }

        // 3. 排除系统运行通知和通知分组摘要（无意义）。
        // 注：分组摘要在 MIUI 上表现为 title 就是「"短信"正在运行」的
        // 独立通知（text 为空），不是靠类名匹配，所以只判正文。
        if (fullText.contains(SYSTEM_GROUP_MARKER)) {
            block(
                capture,
                BlockReason.SYSTEM_GROUP_SUMMARY,
                "系统/分组摘要: pkg=$packageName title=${capture.title}",
                rules = rules,
            )?.let { return it }
        }

        // 4. 排除「记账日报」等统计汇总通知：金额是区间总计而非单笔交易。
        // 必须早于营销词表判断——日报含「支出」会触发强交易特征豁免，靠词表拦不住。
        if (NotificationTextClassifier.isSummaryNotification(fullText)) {
            block(
                capture,
                BlockReason.SUMMARY_REPORT,
                "汇总/日报通知（非单笔交易）: pkg=$packageName text=${fullText.take(50)}",
                rules = rules,
            )?.let { return it }
        }

        // 5. 排除「促销句式」推送：花3得29 / 0元兑 / 加赠99积分 这类结构，
        // 看着有数字有「元」但不是真实付款（会从促销句式里幻觉出金额）。
        // 必须排在调 AI 之前——排后面就只是把噪音从待审池挪到记账失败。
        if (NotificationTextClassifier.isPromoNotification(fullText)) {
            block(
                capture,
                BlockReason.PROMO_SENTENCE,
                "促销推送拦截: pkg=$packageName text=${fullText.take(50)}",
                rules = rules,
            )?.let { return it }
        }

        // 6. 排除营销/广告/优惠券推送（含金额关键词但不是真实账务）——所有渠道生效
        if (rules.isMarketing(fullText)) {
            block(
                capture,
                BlockReason.MARKETING,
                "营销推送拦截: pkg=$packageName text=${fullText.take(50)}",
                rules = rules,
            )?.let { return it }
        }

        // 7. 账务判据：per_package → default_rule → 旧硬编码兜底
        if (!isLikelyFinancial(rules, packageName, capture.title, fullText)) {
            block(
                capture,
                BlockReason.NOT_FINANCIAL,
                "排除(非账务): pkg=$packageName title=${capture.title.take(30)} text=${fullText.take(50)}",
                rules = rules,
            )?.let { return it }
        }

        // v6 default_rule 的设计承诺："新增 App 默认自动生效，无需配置"。
        // 白名单不再一票否决，仅用于日志观测（区分已知/未知来源）。
        val knownSource = rules.isKnownSource(packageName)
        if (!knownSource) {
            appLogger.debug(tag, "包名不在白名单，走 default_rule 兜底: pkg=$packageName")
        }
        appLogger.info(
            tag,
            "✓通过过滤: pkg=$packageName title=${capture.title.take(30)} fullText=${fullText.take(80)}"
        )

        // 8. 长窗内容记忆：同一条内容 5 分钟内不重复调 AI。仅 NLS。
        //    资讯类 App 会把同一条推送反复 post（实测 6 次调 AI / 5 次 5001），
        //    5s 短窗拦不住，长窗把重复调用压到 1 次。
        //    ⚠️ SMS/A11Y 不查：同源同文本的两条可能是两笔真交易（见类 KDoc）。
        if (contentGates) {
            val contentKey = contentKeyOf(packageName, fullText)
            aiCalledContentKeys.entries.removeIf { now - it.value > AI_CONTENT_MEMORY_MS }
            val lastAiCall = aiCalledContentKeys[contentKey]
            if (lastAiCall != null && (now - lastAiCall) < AI_CONTENT_MEMORY_MS) {
                block(
                    capture,
                    BlockReason.AI_MEMORY,
                    "长窗内容记忆(${AI_CONTENT_MEMORY_MS / 60_000}min)，跳过重复调AI: pkg=$packageName",
                    rules = rules,
                )?.let { return it }
            }

            // 9. 单包 AI 熔断：非支付类包名连续 AI 失败 N 次后静音一段时间。
            //    只对「不在白名单」的包生效，银行/微信/支付宝永不被熔断，
            //    避免真交易因网络抖动被误杀。作用域见类 KDoc。
            if (!knownSource && circuitBreaker.isMuted(packageName, now)) {
                block(capture, BlockReason.AI_MUTED, "AI 熔断中（该包连续失败），跳过: pkg=$packageName", rules = rules)
                    ?.let { return it }
            }
            aiCalledContentKeys[contentKey] = now

            // 10. DB 1s 内容去重（防同一条通知被系统多次分发）
            val duplicate = notificationRecordDao.findDuplicate(packageName, fullText, now - DB_DEDUP_WINDOW_MS)
            if (duplicate != null) {
                block(capture, BlockReason.DB_DUPLICATE, "DB去重(1s): pkg=$packageName", rules = rules)
                    ?.let { return it }
            }
        }

        // 11. 云控 precheck：服务端下发的**同源**入站判据（跨端契约 §3）。
        //     在整条客户端自有判据链之后执行；顺序即判据顺序，取第一条命中的 tier。
        recordUnusablePrecheckRules(rules)
        // 输入串与转发给 AI / 服务端的 `input` 一致：先做营销后缀清洗再判
        // （bare_amount 的 len<=30 必须与请求体里的 input 同口径，否则 30 字边界两端分叉）。
        val precheckInput = MarketingSuffixCleaner.clean(
            text = fullText,
            suffixCutoffs = rules.marketingSuffixCutoffs,
            commaKeywords = rules.marketingCommaKeywords,
        )
        // 归表允许 `forward`：命中也不拦，继续（到这里已经没有后续判据 → 放行走 AI）。
        // ⚠️ channel 必须传真实入口：载荷可给单条信号加入口约束
        // （`signals.bare_amount.channels=['manual']`）——客户端只有 nls/sms/a11y 三个入口，
        // 永远不吃那条信号，但**仍要继续往下匹配**（可能命中 verb_only 放行 / no_signal 拦），
        // 而不是直接 tier 0。
        val blocked = when (
            val verdict = PrecheckJudge.evaluate(precheckInput, rules.precheck, capture.channel)
        ) {
            is PrecheckVerdict.Blocked -> block(
                capture,
                BlockReason.PRECHECK,
                "云控 precheck 拦截: rule=${verdict.ruleId} tier=${verdict.tier} " +
                    "pkg=$packageName text=${fullText.take(50)}",
                // 键就是服务端 rule_id（`precheck.<reason>`），⑤ 对账无需映射表
                statsKey = verdict.ruleId,
                tier = verdict.tier,
                rules = rules,
            )
            // Pass 与 NotAvailable 都放行：判据不可用（无云控 / schema 不认识）时**降级放行**
            // —— 宁可多发一次请求，不可静默丢账。
            PrecheckVerdict.Pass, PrecheckVerdict.NotAvailable -> null
        }
        return blocked ?: Verdict.Pass(capture).also { stats.recordPassed(capture.channel) }
    }

    /**
     * 判据 0（A11Y 专属，链首）：页面级结构判据——账单/明细列表页。
     *
     * 这是「整页形状」而非「这句话像不像账」，不依赖词表演进顺序，越早拦越省
     * （后续判据还要跑正则/查库）。列表页每行常带「交易成功」+ 各自金额，本地
     * deduKey=金额|商户|pageToken 因每行金额不同而逐行放行 → 整页滚动会逐行送 AI。
     * 仅在 A11Y 且 adapter 填了 pageText 时生效；pageText 为 null 时既有链逐字不变。
     *
     * 返回 null = 非列表页（或归表要求 forward）；[Verdict.Block] = 被拦。
     * 抽出来纯粹为了让 [evaluate] 的圈复杂度留在阈值内，不改变任何行为。
     */
    private fun listPageVerdict(capture: Capture, rules: NotificationRuleSnapshot): Verdict.Block? {
        val pageText = capture.pageText ?: return null
        if (capture.channel != SourceChannel.A11Y) return null
        val packageName = capture.packageName
        // 云控优先、内置兜底：空词表 / 非正阈值一律回退内置默认
        //（服务端会校验类型，但客户端不该信：阈值 0 = 拦一切）。
        val listKeywords = rules.a11yListPageKeywords
            .map(String::trim)
            .filter(String::isNotBlank)
            .ifEmpty { A11yRecognition.DEFAULT_LIST_KEYWORDS }
        val minAmounts = rules.a11yListPageMinAmounts
            .takeIf { it > 0 }
            ?: A11yRecognition.LIST_PAGE_MIN_AMOUNTS
        // 标题区窗口：per-App profile 可覆盖（缺省 = 内置 24）；profile 只改长度，不参与「是不是账」。
        val headChars = rules.a11yPackageProfiles[packageName]?.listPageHeadChars
            ?: A11yRecognition.LIST_PAGE_KEYWORD_HEAD_CHARS
        val looksLikeListPage = A11yRecognition.looksLikeListPage(
            pageText,
            A11yRecognition.LIST_PAGE_AMOUNT_REGEX,
            listKeywords,
            minAmounts,
            headChars,
        )
        if (!looksLikeListPage) return null
        return block(
            capture,
            BlockReason.LIST_PAGE,
            "账单/明细页，跳过: pkg=$packageName",
            rules = rules,
        )
    }

    /**
     * 云控 precheck 里有规则被逐条丢弃（未知 expr / 缺 flags / 引用缺失 signal）：
     * 只按**代际**记一次诊断计数 `precheck_unknown_expr`——量纲是「规则数」，不是「通知数」。
     * 逐条日志已由 `PrecheckCompiler` 在派生快照时打出。
     */
    private fun recordUnusablePrecheckRules(snapshot: NotificationRuleSnapshot) {
        if (snapshot.precheck.unusableRuleIds.isEmpty()) return
        val seen = lastUnusableRuleGeneration.get()
        if (seen != snapshot.generation &&
            lastUnusableRuleGeneration.compareAndSet(seen, snapshot.generation)
        ) {
            stats.recordUnusablePrecheckRule()
        }
    }

    /**
     * 排除层：判断通知是否"可能是账务"。
     * 返回 false = 100% 不是账务，直接丢弃。
     * 返回 true = 不确定，交给 AI 判断。
     * 设计原则：极保守，宁可多放不漏。
     */
    private fun isLikelyFinancial(
        rules: NotificationRuleSnapshot,
        packageName: String,
        title: String,
        fullText: String,
    ): Boolean {
        // 优先1：per_package 精确配置（格式特殊的App，如微信/支付宝）
        val cfg = rules.perPackage.firstOrNull { it.matches(packageName) }
        if (cfg != null) return evaluateByConfig(rules, cfg, title, fullText)
        // 优先2：default_rule（大多数App走这套：营销排除 + payment_signal 放行）
        if (rules.defaultExcludeContent.isNotEmpty()) {
            // 营销词命中但含强交易特征（尾号/卡号/入账…）时交 AI 判定（宁可多放不漏）
            if (rules.isMarketing(fullText)) return false
            return rules.paymentSignal.containsMatchIn(fullText)
        }
        // 回退：旧硬编码逻辑（default_rule 也为空时，向后兼容）
        return legacyIsLikelyFinancial(rules, packageName, title, fullText)
    }

    /**
     * 规则驱动的通用判断（per_package 配置）。
     * 顺序：排除词（title/content）→ pass_all → title/前缀/金额符号放行。
     */
    private fun evaluateByConfig(
        rules: NotificationRuleSnapshot,
        cfg: PerPackageCriteria,
        title: String,
        fullText: String,
    ): Boolean {
        // 1. 排除词优先（命中直接拒绝）
        if (cfg.excludeTitleContains.any { title.contains(it) }) return false
        // 正文排除词命中时，与 default_rule 分支保持**同一语义**：含强交易特征
        // （尾号/卡号/入账/支出…）时不否决，交给后续放行条件 / AI 判定。
        // 否则会出现「营销词 + 真交易」被误杀（详见 MarketingCriteria）。
        if (MarketingCriteria.isMarketingByWords(fullText, cfg.excludeContentContains)) return false
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
            return rules.legacy.wechatAmountSymbols.any { textAfterTitle.contains(it) } ||
                fullText.contains("¥") || fullText.contains("￥")
        }
        return false
    }

    /** 旧硬编码逻辑，保证向后兼容（云控 per_package 与 default_rule 都落空时才会走到）。 */
    private fun legacyIsLikelyFinancial(
        rules: NotificationRuleSnapshot,
        packageName: String,
        title: String,
        fullText: String,
    ): Boolean {
        val legacy = rules.legacy
        return when (packageName) {
            PACKAGE_WECHAT -> {
                if (legacy.wechatDirectPassTitles.any { title == it }) return true
                if (legacy.wechatDirectPassTitleContains.any { title.contains(it) }) return true
                val textAfterTitle = fullText.substringAfter(title).trim()
                if (legacy.wechatMessagePrefixes.any { textAfterTitle.startsWith(it) }) return true
                legacy.wechatAmountSymbols.any { textAfterTitle.contains(it) }
            }
            PACKAGE_ALIPAY -> legacy.alipayAllowedTitleKeywords.any { title.contains(it) }
            else -> {
                if (rules.bankPackagePatterns.any { packageName.contains(it) || packageName.startsWith(it) }) {
                    return true
                }
                rules.paymentSignal.containsMatchIn(fullText)
            }
        }
    }

    /**
     * 拦截统一从这里走：**查归表** + 计数 + 日志都只在这里做一次，避免每处拦截各写一份口径。
     *
     * 返回 `null` = 归表要求 `forward`：**不拦**，调用方必须继续走后续判据链
     * （而不是提前放行——后面还可能有 `block` 的判据）。
     *
     * @param log 是否写日志（去重等高频路径采样写，避免冲掉账务结果行）；**计数不受它影响**。
     * @param statsKey 上报键（缺省 = `client_only.<reason>`；云控 precheck 传它下发的 `rule_id`）
     * @param tier 云控 precheck 的档位（采样样本可选字段）
     */
    private fun block(
        capture: Capture,
        reason: BlockReason,
        message: String,
        log: Boolean = true,
        statsKey: String = PrefilterStats.clientOnlyKey(reason),
        tier: Int? = null,
        rules: NotificationRuleSnapshot,
    ): Verdict.Block? = when (rules.actionFor(statsKey)) {
        BlockAction.BLOCK -> recordBlock(capture, reason, message, log, statsKey, BlockAction.BLOCK)

        // sample：照旧丢，额外采一条样本（§11.3）。内容形态按渠道：A11Y 只采命中片段（不采整页）。
        BlockAction.SAMPLE -> {
            val isA11yPage = capture.channel == SourceChannel.A11Y
            sampler.record(
                CaptureSampleInput(
                    channel = capture.channel,
                    packageName = capture.packageName,
                    kind = if (isA11yPage) CaptureKind.A11Y_PAGE else CaptureKind.NOTIFICATION,
                    stage = CaptureStage.PREFILTER,
                    blockedBy = statsKey,
                    receivedAt = capture.receivedAt,
                    rawText = if (isA11yPage) capture.pageText ?: capture.fullText else capture.fullText,
                    tier = tier,
                ),
            )
            recordBlock(capture, reason, message, log, statsKey, BlockAction.SAMPLE)
        }

        // forward：不拦，交服务端判（原则：正则与 AI 冲突以 AI 为准）。
        // 日志保留：oper 需要看到「哪些本该被拦的现在被转发调 AI 了」。
        BlockAction.FORWARD -> {
            appLogger.info(
                capture.channel.logTag,
                "归表 forward，不拦（交服务端预筛判）: rule=$statsKey pkg=${capture.packageName} " +
                    "text=${capture.fullText.take(50)}",
            )
            null
        }
    }

    private fun recordBlock(
        capture: Capture,
        reason: BlockReason,
        message: String,
        log: Boolean,
        statsKey: String,
        action: BlockAction,
    ): Verdict.Block {
        stats.recordBlocked(capture.channel, statsKey)
        if (log) appLogger.debug(capture.channel.logTag, message)
        return Verdict.Block(reason, message, blockedBy = statsKey, action = action)
    }

    /** 内容级去重的键：包名 + 正文 hash（内容相同即视为同一次投递）。 */
    private fun contentKeyOf(packageName: String, fullText: String): String =
        "$packageName:${fullText.hashCode()}"

    private companion object {
        const val DB_DEDUP_WINDOW_MS = 1_000L

        /** 还没有记录过任何规则的代际 */
        const val NO_GENERATION = Int.MIN_VALUE

        /** 内部内容去重窗口：同内容 hash 在此窗口内只处理一次 */
        const val CONTENT_DEDUP_WINDOW_MS = 5_000L

        /**
         * 已送 AI 的内容记忆窗口。
         *
         * 5s 短窗挡不住系统对同一通知的反复 post（实测资讯类推送间隔
         * 6s / 12s / 5min / 25min），导致同一条新闻标题重复烧 AI 且连续
         * 5001。长窗命中只跳过「调 AI」，DB 记录与去重逻辑不受影响。
         */
        const val AI_CONTENT_MEMORY_MS = 5 * 60_000L

        /** 去重日志采样间隔：同一包名的去重命中最多每分钟记一条 */
        const val DEDUP_LOG_SAMPLE_MS = 60_000L

        /** MIUI 的分组摘要标记（只判正文，title 上没有） */
        const val SYSTEM_GROUP_MARKER = "正在运行"

        /**
         * 旧硬编码分支按**字面量**匹配包名，与云控 `wechat.package_name` 无关。
         *
         * 保持字面量是刻意不动：改成读云控会让「云控漏字段」时的兜底行为变化，
         * 那属于放行策略变更，不在本次收口范围内。
         */
        const val PACKAGE_WECHAT = "com.tencent.mm"
        const val PACKAGE_ALIPAY = "com.eg.android.AlipayGphone"
    }
}
