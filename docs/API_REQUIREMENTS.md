# AIBILL Android App 后端接口需求文档

> 本文档列出 Android App 端需要后端提供的所有 API 接口。  
> 后端基于已有的 AIBILL 项目（Node.js + Fastify + SQLite），大部分接口已存在，本文档标注哪些需要新增或调整。

---

## 基础协议
| 项 | 值 |
|----|-----|
| Base URL | `http(s)://<server-ip>:3000/api` |
| 认证方式 | `Authorization: Bearer <jwt>` |
| Content-Type | `application/json` |
| 响应格式 | `{ "code": 0, "data": any, "message": "" }` |
| 成功 code | `0` |
| 错误 code | 非 0（详见错误码表） |
| Token 有效期 | 30 天 |
| JSON 命名 | snake_case |
| 金额单位 | 整数，单位：分（¥32.50 = 3250） |

---

## 一、认证模块 ✅ 已有

### POST /api/auth/login

```json
// Request
{ "username": "admin", "password": "123456" }

// Response
{
  "code": 0,
  "data": {
    "token": "eyJhbGci...",
    "user": { "id": 1, "username": "admin", "nickname": "管理员", "role": "admin" }
  }
}
```

### POST /api/auth/register

```json
// Request
{ "username": "test", "password": "123456", "invite_code": "ABC123", "nickname": "测试用户" }

// Response（同 login）
```

### GET /api/auth/me

```json
// Response
{
  "code": 0,
  "data": { "id": 1, "username": "admin", "nickname": "管理员", "role": "admin" }
}
```

**App 用途**：启动时校验 Token 是否有效，401 则跳转登录。

---

## 二、交易模块 ✅ 已有（需确认字段）

### POST /api/transactions — 批量创建

**⚠️ 需要确认**：App 端会传以下字段，请确保后端支持。

```json
// Request
{
  "items": [
    {
      "client_id": "uuid-v4",           // ⚠️ 必须支持，用于幂等
      "client_type": "app_android",     // ⚠️ 新增字段，标识来源客户端
      "source": "manual",               // manual | ai | app_notification
      "source_detail": null,            // 通知原始文本（通知记账时有值）
      "type": "expense",                // expense | income | transfer
      "amount": 3200,                   // 分
      "category_id": 1,
      "account_id": 1,
      "target_account_id": null,        // 仅 transfer 时有值
      "description": "午饭",
      "date": "2026-07-02",
      "time": "12:30",
      "tags": ["工作日"],
      "client_created_at": "2026-07-02T12:30:00+08:00",
      "ai_raw_input": "午饭32"          // AI 记账时的原始输入
    }
  ]
}

// Response
{
  "code": 0,
  "data": {
    "created": [{ "id": 1, "client_id": "uuid-v4", ... }],
    "duplicates": []   // ⚠️ 同一 user_id + client_id 重复提交时返回这里
  }
}
```

**幂等要求**：同一 `user_id + client_id` 不重复入库，重复提交返回 `duplicates` 数组。

### GET /api/transactions — 分页查询

```
GET /api/transactions?page=1&page_size=20&start_date=2026-07-01&end_date=2026-07-31&type=expense&category_id=1&keyword=午饭
```

```json
// Response
{
  "code": 0,
  "data": {
    "items": [
      {
        "id": 1,
        "client_id": "uuid-v4",
        "type": "expense",
        "amount": 3200,
        "category_id": 1,
        "category_name": "餐饮",
        "category_icon": "restaurant",
        "account_id": 1,
        "account_name": "微信",
        "target_account_id": null,
        "target_account_name": null,
        "description": "午饭",
        "date": "2026-07-02",
        "time": "12:30",
        "tags": ["工作日"],
        "created_at": "2026-07-02T12:30:00Z",
        "updated_at": "2026-07-02T12:30:00Z"
      }
    ],
    "total": 156,
    "page": 1,
    "page_size": 20
  }
}
```

**⚠️ 需确认**：响应中每条交易是否包含 `category_name`、`category_icon`、`account_name` 字段（App 需要直接展示，不想再查一次）。

### PUT /api/transactions/:id — 修改
### DELETE /api/transactions/:id — 软删除

这两个应该已有，无特殊要求。

---

## 三、AI 模块 ✅ 已有

### POST /api/ai/parse

```json
// Request
{ "input": "午饭32，打车15" }

// Response
{
  "code": 0,
  "data": {
    "items": [
      {
        "type": "expense",
        "amount": 3200,
        "category_id": 1,
        "category_name": "餐饮",
        "category_icon": "restaurant",
        "description": "午饭",
        "date": "2026-07-02",
        "account_id": null,
        "account_name": null,
        "target_account_id": null,
        "target_account_name": null
      }
    ],
    "raw_input": "午饭32，打车15"
  }
}

// 失败时
{ "code": 5001, "data": null, "message": "AI 解析失败" }
```

### POST /api/ai/chat

```json
// Request
{ "message": "这个月花了多少？", "session_id": "uuid-or-null" }

// Response
{
  "code": 0,
  "data": {
    "message": "本月总支出 ¥3,280.00，其中餐饮占比最高...",
    "session_id": "abc123"
  }
}
```

---

## 四、基础数据 ✅ 已有

### GET /api/categories

```json
// Response
{
  "code": 0,
  "data": [
    { "id": 1, "name": "餐饮", "type": "expense", "icon": "restaurant", "sort_order": 1 },
    { "id": 2, "name": "交通", "type": "expense", "icon": "directions_car", "sort_order": 2 }
  ]
}
```

### GET /api/accounts

```json
// Response
{
  "code": 0,
  "data": [
    { "id": 1, "name": "微信", "type": "payment", "icon": "wechat", "current_balance": 50000 }
  ]
}
```

---

## 五、统计模块 ✅ 已有

### GET /api/stats/summary?year=2026&month=7

```json
{
  "code": 0,
  "data": {
    "expense": 328000,       // 分
    "income": 1200000,       // 分
    "balance": 872000,       // 分
    "expense_change": 15.5   // 环比变化百分比（正数=上升）
  }
}
```

### GET /api/stats/by-category?year=2026&month=7&type=expense

```json
{
  "code": 0,
  "data": [
    { "category_id": 1, "category_name": "餐饮", "category_icon": "restaurant", "amount": 150000, "percent": 45.7 }
  ]
}
```

### GET /api/stats/trend?year=2026&month=7&period=daily&type=expense

```json
{
  "code": 0,
  "data": [
    { "date": "2026-07-01", "amount": 12000 },
    { "date": "2026-07-02", "amount": 8500 }
  ]
}
```

---

## 六、预算模块 ✅ 已有

### GET /api/budgets?year=2026&month=7

```json
{
  "code": 0,
  "data": [
    {
      "id": 1,
      "category_id": 0,         // 0 = 总预算
      "category_name": "总预算",
      "amount": 500000,          // 预算金额（分）
      "spent": 328000,           // 已花费（分）
      "year": 2026,
      "month": 7
    }
  ]
}
```

### POST /api/budgets
### PUT /api/budgets/:id
### DELETE /api/budgets/:id

---

## 七、导入导出 ✅ 已有

### POST /api/import/csv — CSV 账单导入
### GET /api/export/json — JSON 全量导出
### GET /api/export/csv — CSV 流水导出

---

## 八、需要后端确认/新增的点

| # | 问题 | 影响 | 建议 |
|---|------|------|------|
| 1 | **transactions 创建响应是否包含 `created` 和 `duplicates` 两个数组？** | App 依赖此结构做幂等判断 | 如果当前只返回 created 数组，需新增 duplicates |
| 2 | **transactions 查询响应是否内联 category_name/icon 和 account_name？** | App 不想再额外请求分类/账户详情 | 建议在查询时 JOIN 返回 |
| 3 | **`client_id` 幂等是否已实现？** | App 离线同步核心依赖 | UNIQUE(user_id, client_id)，重复返回已有记录 |
| 4 | **`client_type` 字段是否支持？** | App 标识自己来源为 `app_android` | 如果不支持可以忽略，但建议加上便于统计 |
| 5 | **`source` 字段是否支持 `app_notification` 值？** | 通知自动记账来源标识 | 原有 manual/ai，新增 app_notification |
| 6 | **401 响应格式是否为 `{ "code": 401, "message": "..." }`？** | App 统一错误处理 | 保持一致格式 |
| 7 | **stats/summary 的 `expense_change` 字段是否存在？** | 首页和统计页展示环比 | 如果不存在 App 可以自己算，但建议后端提供 |
| 8 | **budgets 查询响应的 `spent` 字段是否自动计算？** | App 展示预算使用进度 | 后端根据当月实际支出自动计算 |

---

## 九、错误码约定

| code | 含义 | App 行为 |
|------|------|----------|
| 0 | 成功 | 正常处理 |
| 401 | Token 无效/过期 | 清除 Token → 跳转登录 |
| 403 | 无权限 | Toast 提示 |
| 422 | 参数校验失败 | 显示 message |
| 5001 | AI 解析失败 | 提示切换手动记账 |
| 5002 | AI 服务不可用 | 提示稍后重试 |

---

## 十、非必须但建议的新增接口

这些不影响 App 当前功能，但后续版本会用到：

| 接口 | 用途 | 优先级 |
|------|------|--------|
| PUT /api/auth/password | 修改密码 | 低 |
| GET /api/settings | 用户设置（默认账户等） | 低 |
| PUT /api/settings | 修改用户设置 | 低 |
| DELETE /api/ai/sessions/:id | 删除 AI 对话会话 | 低 |
| GET /api/transactions/today | 今日流水快捷接口 | 低（可用筛选替代） |

---

*文档结束 — 请后端开发者逐一确认第八节的 8 个问题*
