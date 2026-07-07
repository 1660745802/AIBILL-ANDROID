package com.aibill.android.util

/**
 * 通知来源包名 → 友好名称 统一映射
 *
 * 阶段 2.1：扩展到 30+ 家银行/三方支付平台。
 * 添加新包名只需在此处添加一行，WHITELIST_PACKAGES 引用此 map 的 keys。
 */
object NotificationSourceMapping {
    private val SOURCE_NAMES = mapOf(
        // === 支付平台 ===
        "com.tencent.mm" to "微信支付",
        "com.eg.android.AlipayGphone" to "支付宝",
        // === 短信 App ===
        "com.android.mms" to "短信",
        "com.google.android.apps.messaging" to "短信",
        "com.samsung.android.messaging" to "短信",
        "com.miui.mms" to "短信",
        "com.huawei.message" to "短信",
        "com.oppo.mms" to "短信",
        "com.vivo.mms" to "短信",
        // === 国有大行 ===
        "com.icbc" to "工商银行",
        "com.chinamworld.bocmbci" to "中国银行",
        "com.ccb.start" to "建设银行",
        "com.abchina.abc" to "农业银行",
        "com.chinamworld.main" to "交通银行",
        "com.psbc.mbank" to "邮储银行",
        // === 股份制银行 ===
        "cmb.pb" to "招商银行",
        "com.cmbchina.ccd.pluto.cmbActivity" to "招行信用卡",
        "com.cmbc.cc.mbank" to "民生银行",
        "com.spdbccc.app" to "浦发信用卡",
        "com.spdb.mobilebank.per" to "浦发银行",
        "com.pingan.paces.ccardi" to "平安信用卡",
        "com.pingan.mobilebank" to "平安银行",
        "com.cib.cibmb" to "兴业银行",
        "com.ecitic.bank.mobile" to "中信银行",
        "com.cgbchina.xpt" to "广发银行",
        "com.chinaebocloud.hx" to "华夏银行",
        "com.cebbank.mobile.cemb" to "光大银行",
        // === 城商行 ===
        "com.bonc.njcb" to "南京银行",
        "com.nbbank" to "宁波银行",
        "com.csii.boc_jiangsu" to "江苏银行",
        "com.bankofbeijing.mobilebank" to "北京银行",
        "com.shbank.mper" to "上海银行",
        // === 三方支付/电商/生活服务 ===
        "com.jd.jrapp" to "京东金融",
        "com.jingdong.app.mall" to "京东",
        "com.meituan.meituan" to "美团",
        "com.sankuai.meituan.takeoutnew" to "美团外卖",
        "me.ele" to "饿了么",
        "com.taobao.taobao" to "淘宝",
        "com.tmall.wireless" to "天猫",
        "com.achievo.vipshop" to "唯品会",
        "com.xunmeng.pinduoduo" to "拼多多",
        "com.didi.sdk" to "滴滴出行",
        "com.sdu.didi.psnger" to "滴滴出行",
        "com.unionpay" to "云闪付",
        "com.bestpay.vip" to "翼支付",
        "com.suning.mobile.ebuy" to "苏宁易购",
        "com.douban.frodo" to "豆瓣",
        "com.dangdang.buy2" to "当当",
        // === 出行/住宿 ===
        "ctrip.android.view" to "携程",
        "com.Qunar" to "去哪儿",
        "com.MobileTicket" to "铁路12306",
        "com.autonavi.minimap" to "高德地图",
        "com.baidu.BaiduMap" to "百度地图",
        "com.Lbsyun.amap" to "哈啰出行",
        "com.jingyao.easybike" to "哈啰",
        "com.mfw.roadbook" to "马蜂窝",
        // === 生活缴费/理财 ===
        "com.tencent.liteapp.wepay" to "微信支付分",
        "com.ant.credit.score" to "芝麻信用",
        "com.alipay.mobile.ant.fincommon" to "蚂蚁财富",
        "com.tianhong.fund" to "天弘基金(余额宝)",
        "com.eastmoney" to "东方财富",
        "com.hexin.plat.android" to "同花顺",
        "com.lufax.android" to "陆金所",
        // === 保险 ===
        "com.zhongan.health" to "众安保险",
        // === 记账/信用卡管理 ===
        "com.cardinfo.boss" to "51信用卡",
        // === 数字人民币 ===
        "cn.gov.pbc.dcep" to "数字人民币",
    )

    /** 所有已知支付相关包名集合，供白名单使用 */
    val KNOWN_PACKAGES: Set<String> = SOURCE_NAMES.keys

    fun friendlyName(packageName: String): String =
        SOURCE_NAMES[packageName] ?: packageName
}
