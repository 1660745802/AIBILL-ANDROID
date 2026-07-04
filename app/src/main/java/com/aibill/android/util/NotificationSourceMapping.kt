package com.aibill.android.util

/**
 * 通知来源包名 → 友好名称 统一映射
 *
 * CONTRIBUTION §11.3：三处构造 PendingTransactionEntity.sourceDetail 入口
 * 必须保持一致；之前 Service 用 friendly name，ActionReceiver/ViewModel 用
 * packageName，导致同一笔账显示两种来源，且同步到后端时口径混乱。
 */
object NotificationSourceMapping {
    private val SOURCE_NAMES = mapOf(
        "com.tencent.mm" to "微信支付",
        "com.eg.android.AlipayGphone" to "支付宝",
        "com.android.mms" to "短信",
        "com.google.android.apps.messaging" to "短信",
        "com.samsung.android.messaging" to "短信",
        "com.miui.mms" to "短信",
        "com.icbc" to "工商银行",
        "com.chinamworld.bocmbci" to "中国银行",
        "com.ccb.start" to "建设银行",
        "com.abchina.abc" to "农业银行",
        "cmb.pb" to "招商银行",
        "com.chinamworld.main" to "交通银行",
        "com.cmbchina.ccd.pluto.cmbActivity" to "招行信用卡",
        "com.spdbccc.app" to "浦发信用卡",
        "com.pingan.paces.ccardi" to "平安信用卡",
    )

    fun friendlyName(packageName: String): String =
        SOURCE_NAMES[packageName] ?: packageName
}