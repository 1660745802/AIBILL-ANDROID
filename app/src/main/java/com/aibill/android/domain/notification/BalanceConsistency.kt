package com.aibill.android.domain.notification

/**
 * 余额自洽校验：用「上一条同卡余额 ± 本笔金额 ≈ 本条余额」自证金额没抽错。
 *
 * ## 为什么只在本地、且只降级不拒收
 *
 * 这是从第三方方案（ColorOS 钱包）借鉴的唯一一条：它把手里的余额拿来自证交易。
 * 但那条链路里余额是卡片侧读出来的硬事实；我们这里的余额来自**文本抽取**
 * （服务端 S3 的 `balance_after_cents` / `balance_text`），所以判据天然弱一档：
 *
 * - 余额字段可能不是同一个语义（「可用余额」vs「账户余额」）→ 用 **text 前缀**做键，
 *   **只做同字段自比**，不同前缀永不互比；
 * - 中间可能有我们没记到的交易（手续费、他行扣款）→ 不一致**只降级为待审**，
 *   绝不拒收（宁可多一次确认，不丢真账）。
 *
 * 判定不出来（缺字段、transfer 方向不明、首次见到该卡）一律 [Verdict.UNKNOWN]，
 * 对入账决策**零影响**——它只在「有把握的矛盾」上说话。
 */
interface BalanceConsistency {

    /**
     * 记录本笔余额，并判断它与上一条是否自洽。
     *
     * 无论判定结果如何都会把本笔余额记为下一次的基线（文本里的余额是银行自己的声明，
     * 即使对不上上一笔，它仍然是下一条的正确起点）。
     */
    fun checkAndRecord(
        cardTail: String?,
        balanceText: String?,
        balanceAfterCents: Int?,
        amountCents: Int,
        type: String,
    ): Verdict

    enum class Verdict {
        /** 判不出来（缺字段 / transfer / 首次见到该卡）→ 不影响入账决策 */
        UNKNOWN,

        /** 与上一条自洽：上一条余额 ± 本笔金额 = 本条余额 */
        CONSISTENT,

        /** 有把握的矛盾 → 该笔不自动入账（进待审池），但**不拒收** */
        INCONSISTENT,
    }
}
