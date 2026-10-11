package com.aibill.android.domain.notification

/**
 * 单来源 AI 熔断 module：独占「每来源连续失败计数与静音窗口」这一条策略。
 *
 * ## 为什么存在
 *
 * 资讯/推送类 App 会把同一条内容反复 post，而支付信号判据（含裸「元」「消费」等）
 * 无法把新闻标题与真实账务区分开，于是「同一条新闻标题 6 次调 AI、5 次 5001」。
 * 熔断按**来源**计数：连续失败达阈值后对该来源静音一段时间，把重复烧 AI 压下去。
 *
 * ## 作用域是调用方的责任
 *
 * 本 module 只回答「这个来源是否静音」，**不对白名单做判断**：
 * 是否查询由调用方（预筛 module）决定，且**只对不在白名单的包查询**——
 * 银行/微信/支付宝永不被静音，避免网络抖动把真实账单误杀。
 * 这条边界故意不搬进来：谁能被熔断是放行策略，属于预筛，不是熔断器的状态。
 *
 * [now] 显式传入（而非内部取时钟）是为了让静音窗口的边界能在虚拟时间下被钉死。
 */
interface AiCircuitBreaker {

    /** 该来源是否处于静音期（连续失败达阈值后的一段时间）。 */
    fun isMuted(source: String, now: Long = System.currentTimeMillis()): Boolean

    /** 记录一次 AI 调用结果：失败累计，成功立即清零。 */
    fun recordOutcome(source: String, success: Boolean, now: Long = System.currentTimeMillis())
}
