package com.aibill.android.domain.notification

/**
 * 一条已捕获但未能解析的通知原文（`auth_pending` 行）。
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
 * 鉴权补偿 module：独占「401 会话过期后的原文落库 + 同号重登重放 + 终态」这一条策略。
 *
 * ## 为什么只剩 401
 *
 * 解析失败（超时 / 5xx / 5001 / 网络异常）已经**不再留痕**：它是**不确定**，既不是「是账」
 * 也不是「不是账」，让用户面对一条没有金额/分类的原文没法审（等于形式上留痕、实质上丢弃）。
 * 留痕责任在服务端 `ai_parse_logs`，客户端只计数 + warn（见 PRODUCT §五.2）。
 * 401 是另一回事——会话过期不是解析失败，同号重登后重放既有语义不变。
 *
 * > 历史遗留的 `raw` 行（≤1.6.1 的解析失败原文）仍由待审池 module（[ReviewQueue]）正常读出、
 * > 通知中心仍展示与忽略；本 module 既不写也不删 `raw`（不写迁移、不丢历史数据）。
 *
 * 状态字符串（`auth_pending` / `auth_replayed`）只在这里定义与使用；通知中心读同一张表，
 * 但读路径归待审池 module（[ReviewQueue]）。
 *
 * 容错：落库失败只记日志、不向上抛——捕获链路的兜底不能自己把协程崩掉。
 */
interface CaptureArchive {

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
        /** AI 因 401 未能解析，等重登录后重放。 */
        const val STATUS_AUTH_PENDING = "auth_pending"
    }
}
