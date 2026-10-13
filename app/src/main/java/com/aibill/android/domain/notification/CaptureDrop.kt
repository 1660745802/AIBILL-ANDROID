package com.aibill.android.domain.notification

/**
 * 捕获层漏斗的**丢弃档位**（C03 决策 1b/2a）。
 *
 * 渠道由类型自身携带（`recordDropped(drop)` 无法记错渠道）；[slug] 与 [label] 随类型定义，
 * 新增档位漏一个都编译不过。存储键 `dropped.<channel>.<slug>` 与上报键
 * `client_only.capture_dropped_<channel>_<slug>` 都**机械派生**，不再有手工映射表。
 *
 * ⚠️ 与 [BlockReason] 是两套：这里管「adapter 在进预筛/进 ingress 之前丢的」，
 * 那里管「预筛判据拦下的」。
 */
enum class CaptureDrop(
    val channel: SourceChannel,
    val slug: String,
    val label: String,
) {
    // NLS 早退
    NLS_NO_PACKAGE(SourceChannel.NLS, "no_package", "无包名"),
    NLS_NO_EXTRAS(SourceChannel.NLS, "no_extras", "无 extras"),

    // SMS 早退
    SMS_NOT_ACTION(SourceChannel.SMS, "not_action", "非短信广播"),
    SMS_NO_MESSAGES(SourceChannel.SMS, "no_messages", "空短信数组"),
    SMS_BLANK_TEXT(SourceChannel.SMS, "blank_text", "空正文"),

    // A11Y 早退
    A11Y_NOT_WHITELISTED(SourceChannel.A11Y, "not_whitelisted", "包名不在支付白名单"),
    A11Y_NO_ROOT(SourceChannel.A11Y, "no_root", "根节点不可用"),
    A11Y_ROOT_MISMATCH(SourceChannel.A11Y, "root_mismatch", "根节点跨包"),
    A11Y_NO_ACTIVITY(SourceChannel.A11Y, "no_activity", "无可用 Activity"),
    A11Y_NOT_PAYMENT_ACTIVITY(SourceChannel.A11Y, "not_payment_activity", "非支付页 Activity"),
    A11Y_NO_PAGE_TOKEN(SourceChannel.A11Y, "no_page_token", "无当前页面 token"),
    A11Y_NO_SUCCESS_KEYWORD(SourceChannel.A11Y, "no_success_keyword", "未命中支付成功词"),
    A11Y_EXCLUDED(SourceChannel.A11Y, "excluded", "排除词命中"),
    A11Y_BAD_AMOUNT(SourceChannel.A11Y, "bad_amount", "金额缺失/异常"),
    A11Y_HISTORY_DATE(SourceChannel.A11Y, "history_date", "历史日期"),
    A11Y_DEBOUNCE(SourceChannel.A11Y, "debounce", "防抖/节流(1.5~8s)"),
    A11Y_PAGE_DEDUP(SourceChannel.A11Y, "page_dedup", "页面冷却去重"),
}
