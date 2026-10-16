package com.aibill.android.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 被拦内容的**本地留存**（可观测性用；**不是**上报队列）。
 *
 * ## 它解决什么
 *
 * 上报通道（`POST /api/ai/capture-report`）只能回答「服务端收到了什么」；而
 * 「某笔账单是不是被客户端悄悄丢了」这个排查，恰恰发生在**上报之前**——
 * 采样要去重（`content_hash` + `blocked_by`，本窗口一次）、要限流（每用户每天 200 条），
 * 且队列**只在内存**（进程重启即丢）。
 *
 * 结果是 2026-10 的线上事故：「用户说有几笔账单在面板里看不到」，
 * 而三条 a11y 丢弃规则（`not_payment_activity` / `no_success_keyword` / `root_mismatch`）
 * **一个字的正文都没落过盘**——`app_logs` 只记了类名与 `hasAmount` 布尔，
 * 且保留期只有 2 天。事后**结构性不可还原**。
 *
 * 所以采样在 `record()` 入队时**同步镜像**一份到这里：本地留存不受上报的去重/限流/进程生死影响。
 *
 * ## 与上报队列的关系
 *
 * **并行、互不影响**：
 * - 上报仍由 [com.aibill.android.data.notification.CaptureSamplerImpl] 的内存队列驱动，
 *   契约 §11.8 的去重/限流口径**一个字都没改**（本表不参与上报）；
 * - 本表只在 `text != null` 时写入（无内容的行对查漏账零信息量，只会挤占查询窗口）。
 *
 * 因此**「表里有行」≠「服务端收到了」**：一行代表「客户端认为这个丢弃点值得采一条」，
 * 发送与否/是否成功由上报侧决定（失败会留在 `pending` 下个窗口重发）。
 *
 * ## 保留
 *
 * 30 天时间窗（与服务端 `client_capture_samples` 的保留期一致，契约 §11.4）
 * + [上限兜底]；由采样侧在**跨天**时顺手裁剪，不占任何调用路径的开销。
 */
@Entity(
    tableName = "capture_samples",
    indices = [
        // 「某个包在某个规则下丢了什么」——排查漏账的主查询。
        Index(value = ["package_name", "blocked_by"]),
        // 时间窗裁剪与按时间倒序导出。
        Index(value = ["received_at"]),
    ],
)
data class CaptureSampleEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,

    /** 入口归因：nls / sms / a11y（与上报同口径）。 */
    @ColumnInfo(name = "channel") val channel: String,

    @ColumnInfo(name = "package_name") val packageName: String,

    /** `notification` / `a11y_page`（上报 wire 值，不另起名）。 */
    @ColumnInfo(name = "kind") val kind: String,

    /** `capture`（捕获层早退）/ `prefilter`（预筛判据）。 */
    @ColumnInfo(name = "stage") val stage: String,

    /** 命中的 rule_id，与上报键同源（`client_only.*` / `precheck.*`）。 */
    @ColumnInfo(name = "blocked_by") val blockedBy: String,

    /** 事件到达时刻（epoch ms），分天与倒序都按它，**不是**入库时刻。 */
    @ColumnInfo(name = "received_at") val receivedAt: Long,

    /** 处理后的内容（a11y 是命中片段，不是整页）。null 的行不写本表。 */
    @ColumnInfo(name = "text") val text: String?,

    /** **原文**长度（a11y 是整页长度，不是片段长度）。 */
    @ColumnInfo(name = "text_len") val textLen: Int,

    /** `sha256:<hex>`，对 [text] 取。与上报同一份指纹，便于两边对账。 */
    @ColumnInfo(name = "content_hash") val contentHash: String,

    /** precheck 档位；捕获层早退为 null。 */
    @ColumnInfo(name = "tier") val tier: Int?,

    /** P1 恒 false（契约 §11.5：信息收集期默认采原文）。 */
    @ColumnInfo(name = "redacted") val redacted: Boolean,

    /** 入库时刻（本表内部用，与 [receivedAt] 分开：前者是写入顺序，后者是事件时刻）。 */
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
)
