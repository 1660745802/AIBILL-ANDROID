package com.aibill.android.domain.notification

/**
 * **捕获层漏斗计数**：把「服务端日志看不见的那一段」变成可计数。
 *
 * ## 为什么需要它（PRODUCT §三「漏账率」唯一缺的拼图）
 *
 * 现在整条链路只有**判定**有计数：[PrefilterStats] 记「预筛拦了什么」，服务端有
 * `ai_parse_logs` / `ai_parse_filters`。而**捕获层是黑盒**——A11Y 白名单外的 App、
 * 没命中成功词、防抖/冷却丢弃、NLS 早退……这些记录**从未到达服务端**，也不进预筛，
 * 所以日志里查不到。后果是「漏账」无法诊断：只能看到「没记上」，看不到是在**哪一层**丢的。
 *
 * 本接口只做一件事：在三个平台 adapter（NLS / A11Y / SMS）的每个**早退分支**记一次
 * [record]，于是「收到多少 → 各层丢掉多少」第一次成为可观测的漏斗。
 *
 * ## 与 [PrefilterStats] 的分工（**不重复计数**）
 *
 * - 捕获层管「adapter 自己拦下的」：见下方 stage 清单；
 * - 预筛管「三渠道同一套文本判据」拦下的（自身通知 / 空正文 / 日报汇总 / 营销词 / 账务判据…）——
 *   这些**已经在** [PrefilterStats] 计数，捕获层**不再重复**。
 *
 * ## stage 命名（稳定、进报表；只用本文件的常量，别散落字面量）
 *
 * 形式固定为 `received.<channel>` / `dropped.<channel>.<reason>`，第二段必须是渠道名，
 * 供 [Snapshot.reportCountsFor] 按渠道归因（⑤ 上报要求按渠道拆窗）。
 *
 * **入口（3 个）**
 * - [RECEIVED_NLS] · [RECEIVED_A11Y] · [RECEIVED_SMS]
 *
 * **NLS 早退（`NotificationMonitorService.handleNotification`）**
 * - [DROPPED_NLS_NO_PACKAGE]：`sbn.packageName == null`
 * - [DROPPED_NLS_NO_EXTRAS]：`notification.extras == null`
 * - 空正文等**已在预筛计数**（[BlockReason.BLANK_TEXT]），此处不重复计。
 *
 * **A11Y 早退（`PaymentAccessibilityService`，重点）**
 * - [DROPPED_A11Y_NOT_WHITELISTED]：`onAccessibilityEvent` 包名不在 `paymentApps`
 * - [DROPPED_A11Y_NO_ROOT]：`rootInActiveWindow == null`（STATE / CONTENT 两处）
 * - [DROPPED_A11Y_ROOT_MISMATCH]：`isRootForEvent` 跨包拒绝（STATE / CONTENT 两处）
 * - [DROPPED_A11Y_NO_ACTIVITY]：CONTENT 事件拿不到可用 Activity
 * - [DROPPED_A11Y_NOT_PAYMENT_ACTIVITY]：CONTENT 事件所在 Activity 不像支付页
 * - [DROPPED_A11Y_NO_PAGE_TOKEN]：CONTENT 事件没有当前页面 token
 * - [DROPPED_A11Y_NO_SUCCESS_KEYWORD]：`findMatchedKeyword` 未命中成功词
 * - [DROPPED_A11Y_EXCLUDED]：命中排除词
 * - [DROPPED_A11Y_BAD_AMOUNT]：没找到金额，或金额不在 0.01~100000.00 元（含 `amountKey` 解析失败）
 * - [DROPPED_A11Y_HISTORY_DATE]：`isRecentPayment` 判为历史账单
 * - [DROPPED_A11Y_DEBOUNCE]：三处时间窗去重合并计此一档——CONTENT 3s 节流、
 *   全树扫描 1.5s 节流、同内容 8s 防抖（都是「同页面太频繁」，报表看总量即可）
 * - [DROPPED_A11Y_PAGE_DEDUP]：`金额|商家|页面` 冷却去重命中
 * - 列表页（[BlockReason.LIST_PAGE]）走的是**预筛**，在 [PrefilterStats] 计数，此处不重复。
 *
 * **SMS 早退（`SmsReceiverService.onReceive`，按实际分支）**
 * - [DROPPED_SMS_NOT_ACTION]：action 不是 `SMS_RECEIVED`
 * - [DROPPED_SMS_NO_MESSAGES]：`getMessagesFromIntent` 为空
 * - [DROPPED_SMS_BLANK_TEXT]：`fullText` 为空（在预筛之前，故必须在此计数）
 * - **没有「非银行短信」分支**：银行/账务判据在预筛（[BlockReason.NOT_FINANCIAL]），已由
 *   [PrefilterStats] 计数——这也是任务里 `dropped.sms.not_bank` 这类命名的落点，故不在这里另立 stage。
 *
 * **AI 响应侧（不是捕获层早退，别混）**
 * - [aiExtraItemsStage]（形如 `ai.<channel>.extra_items`）：一次 AI 响应里「除被采用的那一笔外
 *   还有多余条目」。两种来源：
 *   - 新服务端按请求的 `max_items` 裁剪后，用响应里的 `extra_items_dropped` 告知条数；
 *   - 旧服务端（不支持 `max_items`）仍可能回多条，客户端确定性取 `items.first()`。
 *   两种情况都记**一次**本 stage（计的是「发生了这种感觉的响应」次数，条数在 warn 日志里），
 *   绝不静默丢弃。上报键固定 [AI_EXTRA_ITEMS_REPORT_KEY]（`client_only.ai_extra_items`，
 *   渠道段不体现在 key 里，由请求的 `channel` 字段承载）。
 *
 * **AI 失败丢弃（客户端门禁）**
 * - [unparsedFailedStage]（形如 `unparsed.<channel>.ai_failed`）：`NotificationProcessor` 收到
 *   [com.aibill.android.domain.notification.ParseOutcome.Unparsed]（服务端 `outcome=unavailable`：
 *   AI 超时 / 上游 502·504 / 格式异常 5002 / 网络异常 / 未知异常）时记一次。
 *   ⚠️ 「模型空结果 code=5001」带 `outcome=not_a_bill` 是**判非账**，不算失败、不进本计数。解析失败 = **不确定**，既不是「是账」也不是「不是账」：
 *   客户端**不落待审、不发通知、不打扰用户**，留痕责任在服务端 `ai_parse_logs`。
 *   本 stage 把「因 AI 失败而丢掉多少条」变成可上报的失败率——它是 AI 不可用**唯一**的客户端信号。
 *   上报键固定 [UNPARSED_AI_FAILED_REPORT_KEY]（`client_only.unparsed_ai_failed`，
 *   渠道段不体现在 key 里，由请求的 `channel` 字段承载）。
 *
 * ## 进报表
 *
 * [Snapshot.reportCountsFor] 把 stage 机械映射成 `client_only.capture_<stage 点换下划线>`
 * （如 `dropped.a11y.no_root` → `client_only.capture_dropped_a11y_no_root`）；
 * AI 响应侧与 AI 失败丢弃则各用固定键（见上）。三项均供 `PrefilterReportImpl` 合并进
 * `/api/ai/filter-report` 的 `counts`。
 * 前缀必须落在 `client_only.*`（服务端只接受 `precheck.*` / `client_only.*`，第三个前缀 400）。
 *
 * ## 窗口
 *
 * 进程内内存计数，重启清零（服务的是「最近一段时间的分布」，不是累计账）。
 * [snapshot] 读当前窗口，[reset] 由 `PrefilterReportImpl` 在取走上报数据后调用。
 */
interface CaptureFunnelStats {

    /** 记一次漏斗事件。[stage] 必须取本接口伴生对象里的常量（见类 KDoc 清单）。 */
    fun record(stage: String)

    /** 当前窗口的计数快照（不清零）。诊断页 / 日志导出用。 */
    fun snapshot(): Snapshot

    /** 清零（⑤ 上报取走数据后调用，或诊断用）。 */
    fun reset()

    data class Snapshot(
        /** 各渠道入口收到的总数（来自 `received.*` 三项）。 */
        val received: Map<SourceChannel, Long>,
        /**
         * 各 stage 计数（**不含** `received.*`）。
         *
         * 含三类：捕获层早退 `dropped.*`、AI 响应侧 `ai.*`、AI 失败丢弃 `unparsed.*`。
         */
        val stages: Map<String, Long>,
    ) {
        val receivedTotal: Long get() = received.values.sum()

        /**
         * 捕获层早退总数（只算 `dropped.*`）。
         *
         * **不含** AI 响应侧 `ai.*` 与 AI 失败丢弃 `unparsed.*`：那是「AI 已经应答之后」的观测，
         * 不是捕获层丢的，混进来会让「早退丢弃」失真。两者分别看 [aiResponseTotal] / [unparsedTotal]。
         */
        val droppedTotal: Long get() = stages
            .filterKeys { !isAiResponseStage(it) && !isUnparsedStage(it) }
            .values.sum()

        /** AI 响应侧（`ai.*`）发生次数合计（见 [aiExtraItemsStage]）。 */
        val aiResponseTotal: Long get() = stages.filterKeys { isAiResponseStage(it) }.values.sum()

        /** AI 失败丢弃（`unparsed.*`）次数合计（见 [unparsedFailedStage]）——失败率分子。 */
        val unparsedTotal: Long get() = stages.filterKeys { isUnparsedStage(it) }.values.sum()

        /**
         * 某渠道的计数，键已映射为上报键（一般为 `client_only.capture_*`）。
         *
         * 归因规则：`received.<channel>` 直接看渠道；带渠道段的 stage（`dropped.*` / `ai.*` /
         * `unparsed.*`）取第二段。
         */
        fun reportCountsFor(channel: SourceChannel): Map<String, Long> = buildMap {
            received[channel]?.takeIf { it > 0L }?.let { put(reportKey(receivedKey(channel)), it) }
            stages.forEach { (stage, count) ->
                if (channelOf(stage) == channel) put(reportKey(stage), count)
            }
        }
    }

    companion object {
        /** 入口前缀（`received.`）。 */
        const val RECEIVED_PREFIX = "received."

        /** 早退前缀（`dropped.`）。 */
        const val DROPPED_PREFIX = "dropped."

        /** 上报键在 `client_only.` 之后再加的一段（机械拼接，无映射表）。 */
        const val REPORT_KEY_PREFIX = "capture_"

        const val RECEIVED_NLS = "received.nls"
        const val RECEIVED_A11Y = "received.a11y"
        const val RECEIVED_SMS = "received.sms"

        /**
         * AI 响应侧 stage 前缀：与捕获侧的 `received.` / `dropped.` 并列，但语义是
         * **「AI 已经应答之后」的观测，不是捕获层早退**（渲染/合计据此分开）。
         */
        const val AI_RESPONSE_PREFIX = "ai."

        /** AI 响应侧 stage 的原因段。 */
        const val AI_EXTRA_ITEMS_REASON = "extra_items"

        /**
         * AI 失败丢弃 stage 前缀（`unparsed.`）。
         *
         * 与 `dropped.` / `ai.` 并列的第三个家族：它发生在 AI 已经应答（失败）之后，
         * 是**客户端对「不确定」的处置**，既不是捕获层早退，也不是 AI 响应裁剪。
         */
        const val UNPARSED_PREFIX = "unparsed."

        /** AI 失败丢弃 stage 的原因段。 */
        const val UNPARSED_AI_FAILED_REASON = "ai_failed"

        /**
         * AI 响应侧唯一上报键：`client_only.ai_extra_items`。
         *
         * 渠道段不体现在 key 里（请求已带 `channel` 字段），避免为每渠道各造一个键。
         */
        const val AI_EXTRA_ITEMS_REPORT_KEY = "client_only.ai_extra_items"

        /**
         * AI 失败丢弃唯一上报键：`client_only.unparsed_ai_failed`。
         *
         * 渠道段不体现在 key 里（请求已带 `channel` 字段），避免为每渠道各造一个键。
         */
        const val UNPARSED_AI_FAILED_REPORT_KEY = "client_only.unparsed_ai_failed"

        const val DROPPED_NLS_NO_PACKAGE = "dropped.nls.no_package"
        const val DROPPED_NLS_NO_EXTRAS = "dropped.nls.no_extras"

        const val DROPPED_A11Y_NOT_WHITELISTED = "dropped.a11y.not_whitelisted"
        const val DROPPED_A11Y_NO_ROOT = "dropped.a11y.no_root"
        const val DROPPED_A11Y_ROOT_MISMATCH = "dropped.a11y.root_mismatch"
        const val DROPPED_A11Y_NO_ACTIVITY = "dropped.a11y.no_activity"
        const val DROPPED_A11Y_NOT_PAYMENT_ACTIVITY = "dropped.a11y.not_payment_activity"
        const val DROPPED_A11Y_NO_PAGE_TOKEN = "dropped.a11y.no_page_token"
        const val DROPPED_A11Y_NO_SUCCESS_KEYWORD = "dropped.a11y.no_success_keyword"
        const val DROPPED_A11Y_EXCLUDED = "dropped.a11y.excluded"
        const val DROPPED_A11Y_BAD_AMOUNT = "dropped.a11y.bad_amount"
        const val DROPPED_A11Y_HISTORY_DATE = "dropped.a11y.history_date"
        const val DROPPED_A11Y_DEBOUNCE = "dropped.a11y.debounce"
        const val DROPPED_A11Y_PAGE_DEDUP = "dropped.a11y.page_dedup"

        const val DROPPED_SMS_NOT_ACTION = "dropped.sms.not_action"
        const val DROPPED_SMS_NO_MESSAGES = "dropped.sms.no_messages"
        const val DROPPED_SMS_BLANK_TEXT = "dropped.sms.blank_text"

        /** `received.<channel>` 常量。 */
        fun receivedKey(channel: SourceChannel): String = "$RECEIVED_PREFIX${channel.logTag.lowercase()}"

        /**
         * 构造某渠道的 AI 响应侧 stage：`ai.<channel>.extra_items`。
         *
         * 第二段必须是渠道名（与捕获层同风格），[channelOf] 才能按渠道归因；
         * 上报键则统一为 [AI_EXTRA_ITEMS_REPORT_KEY]。
         */
        fun aiExtraItemsStage(channel: SourceChannel): String =
            "$AI_RESPONSE_PREFIX${channel.logTag.lowercase()}.$AI_EXTRA_ITEMS_REASON"

        /** 是否是 AI 响应侧 stage。 */
        fun isAiResponseStage(stage: String): Boolean = stage.startsWith(AI_RESPONSE_PREFIX)

        /**
         * 构造某渠道的 AI 失败丢弃 stage：`unparsed.<channel>.ai_failed`。
         *
         * 第二段必须是渠道名（与其它家族同风格），[channelOf] 才能按渠道归因；
         * 上报键则统一为 [UNPARSED_AI_FAILED_REPORT_KEY]。
         */
        fun unparsedFailedStage(channel: SourceChannel): String =
            "$UNPARSED_PREFIX${channel.logTag.lowercase()}.$UNPARSED_AI_FAILED_REASON"

        /** 是否是 AI 失败丢弃 stage。 */
        fun isUnparsedStage(stage: String): Boolean = stage.startsWith(UNPARSED_PREFIX)

        /** 从 stage 的第二段解析渠道；缺渠道段返回 null（这类 key 不上报，防 400）。 */
        fun channelOf(stage: String): SourceChannel? {
            val parts = stage.split('.')
            if (parts.size < 2) return null
            val tag = parts[1]
            return SourceChannel.entries.firstOrNull { it.logTag.equals(tag, ignoreCase = true) }
        }

        /**
         * stage → 上报键。捕获侧机械拼为 `client_only.capture_<stage 点换下划线>`；
         * AI 响应侧固定为 [AI_EXTRA_ITEMS_REPORT_KEY]（`client_only.ai_extra_items`）；
         * AI 失败丢弃固定为 [UNPARSED_AI_FAILED_REPORT_KEY]。
         *
         * 纯机械拼接（与 [PrefilterStats.clientOnlyKey] 同思路），**没有映射表**：
         * 服务端只需按前缀剥离即可对账，新增 stage 不需要改服务端。
         */
        fun reportKey(stage: String): String = when {
            isAiResponseStage(stage) -> AI_EXTRA_ITEMS_REPORT_KEY
            isUnparsedStage(stage) -> UNPARSED_AI_FAILED_REPORT_KEY
            else -> PrefilterStats.CLIENT_ONLY_PREFIX + REPORT_KEY_PREFIX + stage.replace('.', '_')
        }

        /**
         * 渲染成可直接贴进日志导出的诊断文本。
         *
         * 放在 domain 伴生对象（而不是 data 实现）：presentation 只依赖接口就能拿到可读文本，
         * 不必反向依赖 data 层实现（与 [PrefilterStats.render] 同理）。
         * 先给出三渠道入口总数（漏斗顶），再按条数降序列各 early-exit stage。
         */
        fun render(snapshot: Snapshot): String = buildString {
            appendLine("--- 捕获层漏斗（本次进程 / 当前窗口）---")
            if (snapshot.receivedTotal == 0L && snapshot.stages.isEmpty()) {
                appendLine("（暂无捕获记录）")
                return@buildString
            }
            appendLine(
                "入口: " + SourceChannel.entries.joinToString(" · ") { channel ->
                    "${channel.logTag} 收到 ${snapshot.received[channel] ?: 0L}"
                } + " · 合计 ${snapshot.receivedTotal}"
            )
            snapshot.stages
                .filterKeys { !isAiResponseStage(it) && !isUnparsedStage(it) }
                .entries
                .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
                .forEach { (stage, count) ->
                    appendLine("  $count  $stage  ${LABELS[stage] ?: ""}")
                }
            val unparsedStages = snapshot.stages.filterKeys { isUnparsedStage(it) }
            if (unparsedStages.isNotEmpty()) {
                appendLine("AI 失败丢弃（不确定：不落待审、不打扰用户，留痕在服务端）:")
                unparsedStages.entries.sortedByDescending { it.value }.forEach { (stage, count) ->
                    appendLine("  $count  $stage  ${LABELS[stage] ?: ""}")
                }
            }
            val aiStages = snapshot.stages.filterKeys { isAiResponseStage(it) }
            if (aiStages.isNotEmpty()) {
                appendLine("AI 响应侧（已应答，仅供对账）:")
                aiStages.entries.sortedByDescending { it.value }.forEach { (stage, count) ->
                    appendLine("  $count  $stage  ${LABELS[stage] ?: ""}")
                }
            }
            append("合计: 收到 ${snapshot.receivedTotal} · 早退丢弃 ${snapshot.droppedTotal}")
            if (snapshot.unparsedTotal > 0L) {
                append(" · AI 失败丢弃 ${snapshot.unparsedTotal}")
            }
            if (snapshot.aiResponseTotal > 0L) {
                append(" · AI 响应裁剪 ${snapshot.aiResponseTotal} 次")
            }
            appendLine()
        }

        private val LABELS: Map<String, String> = mapOf(
            DROPPED_NLS_NO_PACKAGE to "无包名",
            DROPPED_NLS_NO_EXTRAS to "无 extras",
            DROPPED_A11Y_NOT_WHITELISTED to "包名不在支付白名单",
            DROPPED_A11Y_NO_ROOT to "根节点不可用",
            DROPPED_A11Y_ROOT_MISMATCH to "根节点跨包",
            DROPPED_A11Y_NO_ACTIVITY to "无可用 Activity",
            DROPPED_A11Y_NOT_PAYMENT_ACTIVITY to "非支付页 Activity",
            DROPPED_A11Y_NO_PAGE_TOKEN to "无当前页面 token",
            DROPPED_A11Y_NO_SUCCESS_KEYWORD to "未命中支付成功词",
            DROPPED_A11Y_EXCLUDED to "排除词命中",
            DROPPED_A11Y_BAD_AMOUNT to "金额缺失/异常",
            DROPPED_A11Y_HISTORY_DATE to "历史日期",
            DROPPED_A11Y_DEBOUNCE to "防抖/节流(1.5~8s)",
            DROPPED_A11Y_PAGE_DEDUP to "页面冷却去重",
            DROPPED_SMS_NOT_ACTION to "非短信广播",
            DROPPED_SMS_NO_MESSAGES to "空短信数组",
            DROPPED_SMS_BLANK_TEXT to "空正文",
            aiExtraItemsStage(SourceChannel.NLS) to AI_EXTRA_ITEMS_LABEL,
            aiExtraItemsStage(SourceChannel.A11Y) to AI_EXTRA_ITEMS_LABEL,
            aiExtraItemsStage(SourceChannel.SMS) to AI_EXTRA_ITEMS_LABEL,
            unparsedFailedStage(SourceChannel.NLS) to UNPARSED_AI_FAILED_LABEL,
            unparsedFailedStage(SourceChannel.A11Y) to UNPARSED_AI_FAILED_LABEL,
            unparsedFailedStage(SourceChannel.SMS) to UNPARSED_AI_FAILED_LABEL,
        )

        /** AI 响应侧唯一 stage 的人读标签。 */
        private const val AI_EXTRA_ITEMS_LABEL = "AI 响应裁剪多余条目（按次数）"

        /** AI 失败丢弃 stage 的人读标签。 */
        private const val UNPARSED_AI_FAILED_LABEL = "AI 解析失败，丢弃（不确定，不进待审）"
    }
}
