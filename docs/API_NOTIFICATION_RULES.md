# 通知记账规则云控 API 需求文档

## 1. 背景

AIBILL Android 客户端的自动记账功能依赖三套规则引擎：
- **NLS（通知监听）排除层**：判断通知是否"可能是账务"
- **A11Y（无障碍服务）识别层**：判断当前屏幕是否为"支付结果页"
- **SMS 垃圾过滤层**：排除营销/广告短信

目前所有规则硬编码在客户端，变更需要发版。电商/支付平台频繁改版，规则需要快速响应更新。

## 2. 目标

提供一个统一的规则配置下发接口，客户端启动时/定时拉取最新规则，本地缓存 + 硬编码兜底。

## 3. 接口定义

### 3.1 获取通知规则配置

**请求**

```
GET /api/config/notification-rules
```

**Query 参数**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| app_version | string | 否 | 客户端 App 版本号（便于灰度下发） |
| device_brand | string | 否 | 设备品牌（部分规则可能品牌相关） |

**请求头**

| Header | 说明 |
|--------|------|
| X-App-Key | 固定 App 密钥（防随意爬取，非用户级认证） |
| If-None-Match | 客户端当前规则版本号，如 `"15"` |

**响应**

- **200 OK**：规则有更新，返回完整规则 + `ETag` 响应头
- **304 Not Modified**：版本未变，空 body（客户端继续用本地缓存）

```
-- 请求示例 --
GET /api/config/notification-rules HTTP/1.1
X-App-Key: aibill_android_2026
If-None-Match: "15"

-- 响应（有更新）--
HTTP/1.1 200 OK
ETag: "16"
Content-Type: application/json

{
  "code": 0,
  "data": {
    "version": 16,
    "updated_at": "2026-08-01T10:00:00Z",
    "rules": { ... }
  }
}

-- 响应（无更新）--
HTTP/1.1 304 Not Modified
```

### 3.2 规则结构详细定义

#### 3.2.1 NLS 规则（通知监听排除层）

```json
{
  "nls": {
    "payment_signal_regex": "[¥￥$]|RMB|CNY|人民币|元|支付|已付|付款|实付|...",
    
    "wechat": {
      "package_name": "com.tencent.mm",
      "direct_pass_titles": ["微信支付", "微信支付凭证"],
      "direct_pass_title_contains": ["零钱"],
      "message_prefixes": ["[转账]", "[微信红包]"],
      "amount_symbols": ["¥", "￥"]
    },
    
    "alipay": {
      "package_name": "com.eg.android.AlipayGphone",
      "allowed_title_keywords": ["交易提醒", "支付", "账单", "花呗", "余额", "到账", "收款", "退款"]
    },
    
    "bank_package_patterns": ["bank", "cmb", "icbc", "ccb", "boc", "abchina"],
    
    "sms_packages": [
      "com.android.mms",
      "com.google.android.apps.messaging",
      "com.samsung.android.messaging",
      "com.miui.mms",
      "com.huawei.message",
      "com.oppo.mms",
      "com.vivo.mms"
    ]
  }
}
```

#### 3.2.2 A11Y 规则（无障碍识别层）

```json
{
  "a11y": {
    "embedded_payment_apps": [
      "me.ele",
      "com.sankuai.meituan",
      "com.dianping.v1",
      "com.taobao.taobao",
      "com.tmall.wireless",
      "com.xunmeng.pinduoduo",
      "cn.damai",
      "com.taobao.idlefish",
      "com.autonavi.minimap"
    ],
    
    "success_keywords": ["支付成功", "付款成功", "交易成功", "支付完成"],
    
    "embedded_success_keywords": ["订单支付成功", "等待商家接单", "订单已提交"],
    
    "common_exclude_keywords": [
      "购物车", "加入购物车", "立即购买", "去支付", "确认订单",
      "极速付款", "立即付款", "确认付款", "更改付款方式",
      "查看物流", "再次购买", "评价", "申请售后", "退款成功",
      "已收货", "已签收", "待发货", "已发货", "待收货",
      "提交订单", "确认收货",
      "删除订单", "追加评价", "申请退款", "交易已取消"
    ],
    
    "wechat_alipay_exclude_keywords": [
      "朋友圈", "通讯录", "发现", "搜索小程序", "扫一扫",
      "看一看", "视频号", "直播", "购物", "游戏",
      "消息", "收藏", "相册", "表情", "设置"
    ],
    
    "amount_regex": "[¥￥]\\s*(\\d+\\.?\\d{0,2})|(\\d+\\.?\\d{0,2})元",
    
    "cooldown_minutes": 5
  }
}
```

#### 3.2.3 SMS 规则（垃圾短信过滤）

```json
{
  "sms": {
    "spam_keywords": [
      "订购", "退订", "办理", "开通", "激活", "贷款", "借款",
      "提额", "申请", "审批", "邀请", "回复R", "回复TD",
      "免费领", "中奖", "恭喜", "点击链接"
    ]
  }
}
```

#### 3.2.4 来源映射（App 包名 → 友好名称）

```json
{
  "source_mapping": {
    "com.tencent.mm": "微信支付",
    "com.eg.android.AlipayGphone": "支付宝",
    "com.icbc": "工商银行",
    "com.chinamworld.bocmbci": "中国银行",
    "com.ccb.start": "建设银行",
    "com.xunmeng.pinduoduo": "拼多多",
    "com.taobao.taobao": "淘宝",
    "...": "..."
  }
}
```

#### 3.2.5 Processor 规则（AI 处理器配置）

```json
{
  "processor": {
    "scoring_window_seconds": 10,
    "dedup_window_seconds": 60,
    "marketing_suffix_cutoffs": [
      "点击领取", "点击查看", "点击开启", "戳我领", "立即领取",
      "去领", "快来领", "可领取", "赶紧领"
    ],
    "max_amount_cents": 10000000,
    "min_amount_cents": 1
  }
}
```

## 4. 客户端行为

### 4.1 拉取时机

| 时机 | 说明 |
|------|------|
| App 启动（冷启动） | Application.onCreate 异步拉取 |
| NLS/A11Y 服务启动 | onServiceConnected 时拉取 |
| 每 6 小时定时 | WorkManager PeriodicWorkRequest |
| 下拉刷新 | 用户主动刷新首页时顺带刷新 |

### 4.2 缓存策略

```
请求 → 版本号对比 → 有更新则存入 SharedPreferences → 通知使用方
                  → 版本相同 → 跳过

使用方读取顺序：
1. 内存缓存（最快）
2. SharedPreferences（本地持久化）
3. 硬编码默认值（兜底，永远可用）
```

### 4.3 容错

- 网络失败：静默失败，使用本地缓存或硬编码默认值
- JSON 解析失败：丢弃该字段，对应部分用默认值
- 部分字段缺失：按字段粒度 fallback，不影响其他字段
- 服务端返回空数组：视为"清空该规则"（如某个 App 被移出监听列表）

## 5. 安全

### 5.1 认证分级

| 接口 | 认证方式 | 说明 |
|------|---------|------|
| `GET /api/config/notification-rules` | X-App-Key（固定密钥） | 规则是公共配置，不含用户隐私；不做用户级认证是因为 NLS/A11Y 服务启动时可能尚未登录 |
| `POST /api/admin/notification-rules` | Admin Bearer Token | 强认证，仅管理员可写入 |
| `PUT /api/admin/notification-rules/:id/activate` | Admin Bearer Token | 强认证 |

### 5.2 安全措施

- 仅通过 HTTPS 传输
- X-App-Key 硬编码在 App 中（混淆保护），防止随意爬取
- 读取接口可走 CDN 缓存（不含用户信息，所有客户端共享一份）
- 客户端对规则做基本校验（如正则语法验证），异常规则不启用
- 管理接口加操作日志审计

## 6. 扩展性

### 6.1 预留字段（未来可能加入）

| 字段 | 用途 |
|------|------|
| `a11y.app_specific_rules` | 按 App 粒度定义特殊规则（如拼多多只监听特定 Activity） |
| `nls.title_blacklist` | 按 title 精确黑名单排除 |
| `processor.auto_category_mapping` | 商户→分类自动映射规则 |
| `processor.amount_validation` | 金额合理性校验规则（如单笔 > 50万 → 待确认） |
| `user_overrides` | 用户个人自定义规则（覆盖全局） |
| `ab_test` | A/B 实验配置 |

### 6.2 灰度下发

服务端可根据 `app_version` / `device_brand` / 用户分组 返回不同版本的规则，实现灰度发布。

## 7. 数据量评估

- 完整 JSON 响应体约 5-8 KB（gzip 后约 1-2 KB）
- 变更频率预估：每周 0-2 次
- 无需考虑高并发（用户级别百级）

## 8. 后端实现建议

### 8.1 存储

简单方案：单表 `notification_rules`

```sql
CREATE TABLE notification_rules (
  id INT PRIMARY KEY AUTO_INCREMENT,
  version INT NOT NULL,
  rules JSON NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  is_active BOOLEAN DEFAULT TRUE
);
```

### 8.2 管理接口（内部）

```
POST /api/admin/notification-rules      -- 创建新版本
GET  /api/admin/notification-rules       -- 查看历史版本
PUT  /api/admin/notification-rules/:id/activate  -- 激活指定版本
```

### 8.3 缓存

- Redis 缓存当前生效版本，TTL 5 分钟
- 客户端带 version 参数，版本相同返回 304（省带宽）

## 9. 里程碑

| 阶段 | 内容 | 优先级 |
|------|------|--------|
| P0 | 后端接口 + 客户端 RulesManager + A11Y/NLS 规则读取 | 高 |
| P1 | 管理后台 UI + 版本管理 + 灰度 | 中 |
| P2 | 用户自定义规则 + A/B 实验 | 低 |
