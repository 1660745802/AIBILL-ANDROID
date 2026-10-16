package com.aibill.android.data.notification

import com.aibill.android.data.local.dao.CaptureSampleDao
import com.aibill.android.data.local.entity.CaptureSampleEntity
import com.aibill.android.domain.a11y.A11yRecognition
import com.aibill.android.domain.notification.CaptureKind
import com.aibill.android.domain.notification.CaptureSample
import com.aibill.android.domain.notification.CaptureSampleInput
import com.aibill.android.domain.notification.CaptureSampler
import com.aibill.android.domain.notification.PrefilterReport
import com.aibill.android.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [CaptureSampler] 的实现：内容处理（截断/片段/hash）+ 窗口内去重 + 每天限流 + 待发队列。
 *
 * ## 内容形态（契约 §11.3，按 kind 分两种）
 *
 * - `notification`：`text` = **通知全文**（超 [CaptureSampler.MAX_TEXT_LEN] 截断），
 *   `text_len` = 原文长度（截断前）；
 * - `a11y_page`：**不采整页**。`text` = 命中片段（命中词/金额前后各
 *   [CaptureSampler.FRAGMENT_PADDING] 字，多段以 ` … ` 连接），`text_len` = 整页长度。
 *   整页动辄上千字，直接上报既淹没 admin 也把「这一页哪里像账」淹掉。
 *
 * ## 去重与限流（契约 §11.8）
 *
 * - 去重键 = `(content_hash, blocked_by)`，**本窗口**一次（`drain` 即开新窗口）。
 *   服务端还有 `UNIQUE(user_id, content_hash, blocked_by)` 兜底，所以重发是安全的；
 *   客户端这层去重只是省流量；
 * - 每天限流：每 `(package, rule)` [CaptureSampler.MAX_PER_RULE_PER_DAY] 条、
 *   每用户 [CaptureSampler.MAX_PER_USER_PER_DAY] 条，超了一律丢弃（计数 + 首次 warn）。
 *   限流按**样本自带的 `receivedAt`** 分天（北京时间），不用「机器当前时间」——
 *   样本的时刻才代表它属于哪一天，也让单测不必注入时钟。
 *
 * ## 线程与代价
 *
 * 被四条路径并发调用（NLS/SMS/A11Y 主线程 + 预筛协程），全部状态在一个对象锁里改，
 * 且只做内存操作 —— 采样绝不能阻塞用户操作（best-effort 的底线）。
 * 队列**只在内存**（进程重启丢）：P1 样本是信息收集，不是账，不值得为它写磁盘。
 *
 * ## 但内容要留盘（可观测性，与上报并行）
 *
 * 上面那句「不值得为它写磁盘」针对的是**发送队列**，它确实不落盘；
 * 可 2026-10 的线上事故恰恰吃了这个亏：用户报「有几笔账单面板里看不到」，
 * 而 `not_payment_activity` / `no_success_keyword` / `root_mismatch` 三条规则
 * **一个字的正文都没留下**（上报要去重、要限流，且随进程消失）→ 事后不可还原。
 *
 * 所以入队时**额外镜像**一份到 [CaptureSampleDao]（Room），与内存队列**并行、互不影响**：
 * - 上报口径（去重/限流/发送/重发）**一个字都没改**；
 * - 只有 `text != null` 的样本才落库（无内容的行对查漏账零信息量，只会挤占查询窗口）；
 * - 写入在独立 [scope] 上做，**不拖慢热路径**；失败只 warn，绝不影响上报。
 */
@Singleton
class CaptureSamplerImpl @Inject constructor(
    private val appLogger: AppLogger,
    private val captureSampleDao: CaptureSampleDao,
) : CaptureSampler {

    /** 本地留存的写入/裁剪用；与上报队列彻底解耦，且不占用调用方线程。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 待发样本（`drain` 取走）。 */
    private val pending = ArrayDeque<CaptureSample>()

    /** 本窗口已入队的 `(content_hash, blocked_by)`。 */
    private val seenInWindow = HashSet<String>()

    /** `day|package|blockedBy` → 当日已入队条数。 */
    private val perRuleToday = HashMap<String, Int>()

    /** `day` → 当日已入队条数（每用户上限）。 */
    private val totalToday = HashMap<String, Int>()

    /** 已经 warn 过的限流键（避免刷屏；每键只 warn 一次）。 */
    private val warnedKeys = HashSet<String>()

    private val lock = Any()

    private var duplicates = 0L
    private var droppedPerRule = 0L
    private var droppedTotal = 0L

    /** 限流/去重丢弃的计数（诊断与测试用；不参与上报）。 */
    data class Counters(
        val duplicates: Long,
        val droppedPerRule: Long,
        val droppedTotal: Long,
    )

    override fun record(input: CaptureSampleInput) {
        val sample = build(input)
        val dayChanged: Boolean
        synchronized(lock) {
            dayChanged = rotateDayIfNeeded(dayKeyOf(sample.receivedAt))
            val dedupKey = "${sample.contentHash}|${sample.blockedBy}"
            if (!seenInWindow.add(dedupKey)) {
                duplicates++
                return
            }
            val day = dayKeyOf(sample.receivedAt)
            if ((totalToday[day] ?: 0) >= CaptureSampler.MAX_PER_USER_PER_DAY) {
                droppedTotal++
                warnOnce("total|$day", "采样已达每用户每天上限 ${CaptureSampler.MAX_PER_USER_PER_DAY} 条，丢弃: pkg=${sample.packageName}")
                return
            }
            val ruleKey = "$day|${sample.packageName}|${sample.blockedBy}"
            if ((perRuleToday[ruleKey] ?: 0) >= CaptureSampler.MAX_PER_RULE_PER_DAY) {
                droppedPerRule++
                warnOnce("rule|$ruleKey", "采样已达每规则每天上限 ${CaptureSampler.MAX_PER_RULE_PER_DAY} 条，丢弃: " +
                    "pkg=${sample.packageName} rule=${sample.blockedBy}")
                return
            }
            pending += sample
            totalToday[day] = (totalToday[day] ?: 0) + 1
            perRuleToday[ruleKey] = (perRuleToday[ruleKey] ?: 0) + 1
        }
        // 只有真有内容的样本才值得留：text==null 的行查不出任何东西，
        // 只会把表撑满、将有内容的行挤出查询窗口。
        if (sample.text != null) mirrorToArchive(sample)
        // 跨天才裁剪：白天才有「昨天以前」这个概念，而且天然摊薄到每天一次。
        if (dayChanged) pruneArchive()
    }

    /**
     * 镜像一条样本到本地留存（Room）。
     *
     * 失败只 warn：留存是**旁路可观测性**，绝不能因为它把上报或记账带崩。
     * [CancellationException] 原样传播（破坏它会让「进程回收」被当成落库失败）。
     */
    private fun mirrorToArchive(sample: CaptureSample) {
        scope.launch {
            try {
                captureSampleDao.insert(
                    CaptureSampleEntity(
                        channel = PrefilterReport.channelName(sample.channel),
                        packageName = sample.packageName,
                        kind = sample.kind.wire,
                        stage = sample.stage.wire,
                        blockedBy = sample.blockedBy,
                        receivedAt = sample.receivedAt,
                        text = sample.text,
                        textLen = sample.textLen,
                        contentHash = sample.contentHash,
                        tier = sample.tier,
                        redacted = sample.redacted,
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appLogger.warn("SAMPLER", "样本落库失败（不影响上报）: ${e.message}")
            }
        }
    }

    /** 30 天时间窗 + 条数兜底（与服务端 `client_capture_samples` 保留期一致，契约 §11.4）。 */
    private fun pruneArchive() {
        val before = System.currentTimeMillis() - ARCHIVE_RETENTION_MS
        scope.launch {
            try {
                captureSampleDao.cleanBefore(before)
                captureSampleDao.trimToKeep(ARCHIVE_MAX_ROWS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appLogger.warn("SAMPLER", "样本留存裁剪失败: ${e.message}")
            }
        }
    }

    override fun drain(): List<CaptureSample> = synchronized(lock) {
        val out = pending.toList()
        pending.clear()
        seenInWindow.clear()   // 新窗口：同一内容允许在下个窗口再采一次（服务端仍会按 UNIQUE 去重）
        out
    }

    fun counters(): Counters = synchronized(lock) {
        Counters(duplicates = duplicates, droppedPerRule = droppedPerRule, droppedTotal = droppedTotal)
    }

    /** 跨天时清掉计数（只留当天，防地图无界增长）。返回 true = 确实跨了天。 */
    private fun rotateDayIfNeeded(day: String): Boolean {
        if (totalToday.keys.all { it == day }) return false
        totalToday.keys.retainAll { it == day }
        perRuleToday.keys.retainAll { it.substringBefore('|') == day }
        warnedKeys.retainAll { it.contains(day) }
        return true
    }

    private fun warnOnce(key: String, message: String) {
        if (warnedKeys.add(key)) appLogger.warn("SAMPLER", message)
    }

    // ────────────────────────── 内容处理 ──────────────────────────

    private fun build(input: CaptureSampleInput): CaptureSample {
        val raw = input.rawText
        val text = when {
            raw.isNullOrEmpty() -> null
            input.kind == CaptureKind.A11Y_PAGE -> fragment(raw, input.anchors)
            else -> raw.take(CaptureSampler.MAX_TEXT_LEN)
        }
        return CaptureSample(
            channel = input.channel,
            packageName = input.packageName,
            kind = input.kind,
            stage = input.stage,
            blockedBy = input.blockedBy,
            receivedAt = input.receivedAt,
            text = text,
            textLen = raw?.length ?: 0,
            contentHash = "sha256:" + sha256Hex(text.orEmpty()),
            tier = input.tier,
            // P1：默认采原文（契约 §11.5，信息收集期已确认不设隐私开关）
            redacted = false,
        )
    }

    /**
     * `a11y_page` 的片段：命中锚点（成功词/排除词/金额）前后各 [FRAGMENT_PADDING] 字。
     *
     * 锚点一个都没命中时退化为「第一个金额候选」前后各 [FRAGMENT_PADDING] 字
     * （`list_page` 之外的 A11Y 丢弃大多与金额有关）；仍没有 → null（不编造内容）。
     */
    private fun fragment(pageText: String, anchors: List<String>): String? {
        val ranges = ArrayList<IntRange>(MAX_FRAGMENTS)
        for (anchor in anchors) {
            if (anchor.isBlank() || ranges.size >= MAX_FRAGMENTS) continue
            var index = pageText.indexOf(anchor)
            while (index >= 0 && ranges.size < MAX_FRAGMENTS) {
                ranges += windowOf(pageText, index, anchor.length)
                index = pageText.indexOf(anchor, index + anchor.length)
            }
        }
        if (ranges.isEmpty()) {
            for (match in A11yRecognition.LIST_PAGE_AMOUNT_REGEX.findAll(pageText)) {
                if (ranges.size >= MAX_FRAGMENTS) break
                ranges += windowOf(pageText, match.range.first, match.value.length)
            }
        }
        if (ranges.isEmpty()) return null
        return mergeRanges(ranges)
            .joinToString(FRAGMENT_SEPARATOR) { pageText.substring(it.first, it.last + 1) }
            .take(CaptureSampler.MAX_TEXT_LEN)
    }

    private fun windowOf(text: String, start: Int, length: Int): IntRange {
        val from = (start - CaptureSampler.FRAGMENT_PADDING).coerceAtLeast(0)
        val to = (start + length + CaptureSampler.FRAGMENT_PADDING - 1).coerceAtMost(text.lastIndex)
        return from..to
    }

    /** 合并重叠/相邻的片段窗口，避免同一段文字重复出现。 */
    private fun mergeRanges(ranges: List<IntRange>): List<IntRange> {
        val sorted = ranges.sortedBy { it.first }
        val merged = ArrayList<IntRange>(sorted.size)
        for (range in sorted) {
            val last = merged.lastOrNull()
            if (last == null || range.first > last.last + 1) {
                merged += range
            } else {
                merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
            }
        }
        return merged
    }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun dayKeyOf(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(BEIJING_ZONE).format(DAY_FORMATTER)

    private companion object {
        /** 单条 a11y 样本最多几段片段（再多就不是「片段」而是整页了）。 */
        const val MAX_FRAGMENTS = 5

        const val FRAGMENT_SEPARATOR = " … "

        /** 本地留存保留 30 天（与服务端 `client_capture_samples` 一致，契约 §11.4）。 */
        const val ARCHIVE_RETENTION_MS = 30L * 24 * 60 * 60 * 1000

        /**
         * 条数兜底：正常路径 30 天 ≤ 200×30 = 6000 条，根本触不到；
         * 只防「某天限流被绕过」这类异常把表写爆。
         */
        const val ARCHIVE_MAX_ROWS = 8_000

        val BEIJING_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

        /** 分天口径：北京时间日期（与服务端「无时区按北京解释」的窗口口径一致）。 */
        val DAY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
    }
}
