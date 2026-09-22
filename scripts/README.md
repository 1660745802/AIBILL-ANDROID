# 通知记账规则云控

## 概述

通知自动记账的所有规则（排除词、关键词、包名白名单等）支持服务端下发，无需发版即可调整。

客户端启动时自动拉取最新规则，失败时 fallback 到硬编码默认值。

## 快速更新规则

### 1. 编辑规则文件

修改 `scripts/rules.json`（**注意递增 version 字段**）。

### 2. 执行更新脚本

```bash
# Linux/Mac
export AIBILL_SERVER_URL=http://你的服务器:3000
export AIBILL_ADMIN_PASS=你的密码
./scripts/update_rules.sh scripts/rules.json

# Windows (PowerShell)
$env:AIBILL_SERVER_URL = "http://你的服务器:3000"
$env:AIBILL_ADMIN_PASS = "你的密码"
bash scripts/update_rules.sh scripts/rules.json
```

### 3. 验证

```bash
curl $AIBILL_SERVER_URL/api/config/notification-rules | python3 -m json.tool
```

客户端下次冷启动时自动生效。

## 手动 API 操作

### 查看当前生效规则（无需认证）

```bash
GET /api/config/notification-rules
```

支持 ETag/304 缓存。

### 创建新版本（需 admin）

```bash
POST /api/admin/notification-rules
Authorization: Bearer <token>
Content-Type: application/json

# body 就是 rules.json 的内容
```

### 激活指定版本（需 admin）

```bash
PUT /api/admin/notification-rules/:id/activate
Authorization: Bearer <token>
```

### 查看历史版本（需 admin）

```bash
GET /api/admin/notification-rules
Authorization: Bearer <token>
```

## 规则结构说明

| 模块 | 用途 | 常见修改场景 |
|------|------|-------------|
| `nls` | 通知监听排除层 | 新增支付App、调整微信/支付宝放行规则 |
| `a11y` | 无障碍识别层 | 新增/移除电商App监听、调整排除词 |
| `sms` | 短信垃圾过滤 | 新增营销短信关键词 |
| `source_mapping` | 包名→友好名称 | 新增App的展示名称 |
| `processor` | AI处理器参数 | 调整评分窗口/去重时间/营销后缀 |

## 客户端行为

- **拉取时机**：App 冷启动时异步拉取
- **缓存**：内存 → SharedPreferences → 硬编码默认值
- **ETag**：版本未变时返回 304，不重复下载
- **容错**：网络失败/解析失败静默回退到默认值，不影响使用
