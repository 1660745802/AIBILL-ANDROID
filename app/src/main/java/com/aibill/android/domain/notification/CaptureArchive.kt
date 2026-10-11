package com.aibill.android.domain.notification

/**
 * 一条已捕获但未能解析的通知原文（`raw` 或 `auth_pending` 行）。
 *
 * 刻意不复用 `notification_records` 的 Entity：域层不依赖 data 层（见 CONTRIBUTING §三）。
 */
data class CapturedText(
    val id: Long,
    val packageName: String,
    val title: String,
    val content: String,
    val receivedAt: Long,
)

/**
 * 捕获与补偿 module：独占「未解析原文的落库语义 + 待补偿重放 + 终态」这一条策略。
 *
 * ## 两种留痕，两种后续
 *
 * - [captureUnparsed]：解析失败（超时 / 5xx / 5001 / 网络异常 / 未知异常）→ 落 `raw`。
 *   语义是「捕获到但没解析」，**不做自动重放**：靠用户在通知中心看见、手动补金额。
 * - [captureAuthPending]：401 → 落 `auth_pending`。这不是解析失败而是会话过期，
 *   同号重登后由 [replay] 逐条重来。
 *
 * 状态字符串（`raw` / `auth_pending` / `auth_replayed`）只在这里定义与使用；
 * 通知中心读的是同一张表，但读路径归待审池 module（[ReviewQueue]）。
 *
 * 容错：落库失败只记日志、不向上抛——捕获的兜底不能自己把协程崩掉。
 */
interface CaptureArchive {

    /** 解析失败：把通知原文永久留痕为待人工确认（金额/分类留空）。 */
    suspend fun captureUnparsed(capture: Capture, reason: String)

    /** 鉴权失败（401）：留痕为待补偿，等同号重登后重放。 */
    suspend fun captureAuthPending(capture: Capture)

    /**
     * 重放待补偿队列。
     *
     * 逐条把原文交回 [replay] 重新解析；**无论成败都标终态**，避免死循环。
     * 超过保留时长的记录不再自动重放（AI 分类已过时）。
     * 由 `AuthRepositoryImpl` 在「同号重登、本地数据一张未动」之后触发。
     */
    suspend fun replay(replay: suspend (CapturedText) -> Unit)

    companion object {
        /** 捕获到但没解析：金额/分类留空，等用户在通知中心手动补。 */
        const val STATUS_RAW = "raw"

        /** AI 因 401 未能解析，等重登录后重放。 */
        const val STATUS_AUTH_PENDING = "auth_pending"
    }
}
