package com.aibill.android.domain.notification

/**
 * 预筛放行后的落点：把一条 [Capture] 交给通知入账协调者。
 *
 * C02：这是 [CaptureIngress] 的下游 seam。`NotificationProcessor` 是唯一实现；
 * 抽成 interface 后 ingress 的测试面只是一个两行 fake，且 ingress（data）不再依赖
 * service 的具体协调者类（消除一条 data → service 反向依赖）。
 */
interface NotificationSink {
    suspend fun process(capture: Capture)
}
