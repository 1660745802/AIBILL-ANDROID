# AIBILL 自动记账能力 v2 方案

> **作者**：AI 协助（基于多轮 review）
> **状态**：已 review，调整后执行
> **创建日期**：2026-07-05
> **目标**：让 AIBILL 真正实现"0 操作原则：到手即用，什么都能记"

---

## 1. 核心设计原则

### 1.1 原则声明

```
AIBILL 一切"记账核心功能"零开关：
  通知监听、AI 解析、自动确认、健康度心跳、通知隐私
  → 默认全部开启，由系统权限控制，不由 App 二次开关

只有"辅助/隐私/敏感"功能 opt-in 默认关：
  App 锁、快捷记账、AccessibilityService 备用、免打扰时段
```

### 1.2 决策依据

**反人类开关案例**：`notificationEnabled: ?: false`（当前实现，默认关闭）

```
即使系统在"通知使用权"里给 App 授权了，
App 内部的 notificationEnabled = false 仍然会让监听不工作。
用户被两层开关搞糊涂。
正确做法：通知监听跟随系统"通知使用权"权限，无 App 内部开关。
```

### 1.3 核心 vs 辅助 对照表

| 功能 | 是否要开关 | 决策依据 |
|------|----------|---------|
| 通知监听 | **不要开关** | 系统"通知使用权"权限即足够 |
| AI 智能解析 | **不要开关（默认开）** | "0 操作"原则 |
| 通知隐私遮蔽 | **不要开关（默认开）** | 用户期望隐私保护默认开 |
| 自动确认（小额免确认）| **不要开关（默认开）** | 同上 |
| OCR 截图 | **不要开关（默认开）** | 用户显式分享即同意 |
| SMS Retriever | **不要开关（默认开）** | 同 NLS 通知 |
| 心跳自检 | **不要开关（默认开）** | 保障系统健康 |
| App 锁（生物识别）| **要开关**（默认关）| 隐私/便利性 opt-in |
| 快捷记账 | **要开关**（默认关）| 辅助功能 opt-in |
| AccessibilityService 备用 | **要开关**（默认关）| 敏感权限 opt-in |
| 免打扰时段 | **要开关**（默认关）| 用户偏好 opt-in |

### 1.4 视觉化对照

**当前 Settings 页（反人类）：**
```
┌──────────────────────────┐
│ 通知监听 [●—]  ← 反人类！  │
│ 通知隐私 [——]  ← 应默认开  │
│ AI 智能解析 [——] ← 应默认开  │
│ App 锁    [——]              │
│ 快捷记账  [——]              │
└──────────────────────────┘
```

**改后 Settings 页（0 操作）：**
```
┌──────────────────────────┐
│ 🔔 通知监听 [已开启] [前往设置] │  ← 跟随系统权限
│ 🤖 AI 智能解析 [已开启]    │  ← 默认开
│ 🔒 通知隐私 [已开启]        │  ← 默认开
├──────────────────────────┤
│ 🔐 App 锁      [——]          │  ← 隐私锁 opt-in
│ ⚡ 快捷记账  [——]          │  ← 辅助 opt-in
│ 🤖 屏幕识别  [——]          │  ← 敏感 opt-in
│ 🌙 免打扰时段 [——]         │  ← 偏好 opt-in
└──────────────────────────┘
```

---

## 2. 业界调研要点

### 2.1 PennyWise AI（GitHub 510 stars, 107 forks）

**关键设计**：
- 唯一需要的是 `READ_SMS` 权限（一次性）
- App 启动后自动监听，无开关
- 新 SMS 到达 → 实时 AI 解析 → 静默入库
- 136 家银行覆盖 22 国
- **端侧 AI**（MediaPipe Qwen 2.5）— 隐私优先

**对 AIBILL 的启发**：SMS 监听是覆盖银行短信的最有效方式（60+ 中国银行短信格式）。

> **Review 修正（2026-07-05）**：SMS Retriever API 是为一次性验证码设计的，
> 要求短信末尾包含 App-specific hash。银行短信不会包含 App hash，
> 所以 SMS Retriever 实际上读不到银行短信。
>
> 正确方案：
> - 国内分发（非 Google Play）：直接用 `RECEIVE_SMS` 权限
> - Google Play 上架：只能用 Notification Listener 监听短信 App 的通知（已有能力）

### 2.2 BeeCount（1.9k stars, 256 forks）

**关键设计**：
- 8 大输入渠道：NLS 通知 / SMS / OCR 截图 / 语音 / 无障碍 / WebDAV / AI 对话 / 智能规则
- 多账本、多账户、二级分类、预算管理
- BSL 协议（个人免费，商业付费）

**对 AIBILL 的启发**：6 大场景（NLS + SMS + 银行 App + 三方 + OCR + 规则）覆盖 95%+ 真实交易。

### 2.3 数据流图

```
┌─────────────────────────────────────────────────────────────┐
│                      数据来源                                 │
├─────────────────────────────────────────────────────────────┤
│  NLS 通知      SMS 短信      OCR 截图      手动输入           │
│  (已有)        (新增)        (新增)        (已有)             │
└──────┬──────────────┬──────────────┬──────────────┬────────┘
       │              │              │              │
       ▼              ▼              ▼              ▼
┌──────────────────────────────────────────────────────────────┐
│                    预筛层                                     │
│  hasDigit && hasPaymentSignal → 通过才继续                      │
└──────────────────────────────────────────────────────────────┘
       │
       ▼
┌──────────────────────────────────────────────────────────────┐
│                    AI 解析层（云端）                            │
│  POST /ai/parse → {amount, type, category, merchant, orderId} │
└──────────────────────────────────────────────────────────────┘
       │
       ▼
┌──────────────────────────────────────────────────────────────┐
│                 正则交叉验证层                                  │
│  AI 与正则一致 → trust (95%)                                  │
│  AI 不一致 → 降级到正则 (75%)                                  │
│  正则未识别 → 用 AI (80%)                                     │
│  任一异常 → 标 needs_confirm (60%)                            │
└──────────────────────────────────────────────────────────────┘
       │
       ▼
┌──────────────────────────────────────────────────────────────┐
│                    去重层                                      │
│  第 1 优先级：订单号/商户单号相同                               │
│  第 2 优先级：跨包名同金额+10 秒                                │
│  第 3 优先级：5min+同金额（兜底）                               │
└──────────────────────────────────────────────────────────────┘
       │
       ▼
┌──────────────────────────────────────────────────────────────┐
│                    决策层                                      │
│  ≥90 → 静默入库                                               │
│  60-89 → Heads-up 弹窗确认                                    │
│  <60 → 标 raw，通知中心待确认                                  │
└──────────────────────────────────────────────────────────────┘
```

### 2.4 隐私策略

| 维度 | 策略 |
|------|------|
| **本地存储** | 保留通知原文（用于 AI 学习和调试） |
| **通知展示** | privacyMode 遮蔽金额/来源（已有） |
| **外传到后端** | 只传 AI 解析所需的最小文本（脱敏后），不传聊天内容 |
| **脱敏规则** | 剥离用户名、聊天片段、敏感个人信息 |
| **AI 开关** | 用户可在隐私设置里关闭 AI（高级选项，默认开） |

---

## 3. 实施阶段（8 阶段 / 29 commits / ~55h）

> **Review 修正（2026-07-05）**：
> - 阶段 3 SMS 方案从 SMS Retriever 改为 RECEIVE_SMS
> - 阶段 5 OCR 截图降级为后续迭代
> - 阶段 6 智能规则引擎砍掉，改为增强 CategoryLearningEngine
> - 阶段 7 AccessibilityService 改为实验性/可选
> - 新增阶段 0.5 Interceptor 单元测试
> - 工时按 review 意见 ×1.5

| 阶段 | 主题 | commits | 估计工时 |
|------|------|---------|---------|
| **0** | 移除反人类开关 | 3 | 6h |
| **0.5** | Interceptor 单元测试（新增）| 2 | 3h |
| **1** | 默认开启 AI + 心跳 | 2 | 4h |
| **2** | NLS 通知扩展 | 4 | 8h |
| **3** | SMS 短信监听（RECEIVE_SMS）| 5 | 15h |
| **4** | AI 智能解析 | 5 | 9h |
| **7** | fallback 机制（去掉 AccessibilityService）| 2 | 6h |
| **8** | 0 操作引导 | 3 | 5h |
| **合计（核心路径）** | | **~26** | **~56h** |
| --- | --- | --- | --- |
| **5** | OCR 截图记账（后续迭代）| 4 | 10h |
| **6** | 增强 CategoryLearningEngine（后续迭代）| 3 | 5h |

执行顺序（核心路径）：0 → 0.5 → 1 → 2 → 3 → 4 → 7 → 8

> **OCR 和规则引擎降级为后续迭代**：OCR 截图使用场景极低频，
> +5MB 体积换极低 ROI；智能规则引擎对个人项目过重，
> 增强现有 CategoryLearningEngine 即可覆盖 90% 需求。

---

## 4. 阶段 0（3 commits）— 移除反人类开关

### Commit 0.1：删除 `notificationEnabled` 字段

**改动**：
- `data/local/datastore/UserPreferences.kt`：删除 `Keys.NOTIFICATION_ENABLED`、删除 `notificationEnabled` Flow
- `service/NotificationMonitorService.kt`：入口检查改读"系统通知监听是否被授权"（`Settings.Secure.getString("enabled_notification_listeners")` 含本包名 → 已授权）
- `presentation/ui/settings/SettingsScreen.kt`：删除通知开关 UI 行，改为展示"通知监听权限状态"（"已开启" + "前往设置"按钮）
- `presentation/ui/settings/PermissionGuideScreen.kt`：调整文案强调"通知监听权限 = 自动记账"
- 所有引用 `notificationEnabled` 的代码改用 `Settings.Secure.getString("enabled_notification_listeners", "").contains(packageName)`

**测试**：
- 单元测试：NotificationMonitorService 入口检查逻辑（带 mock Settings.Secure）
- UI 测试：SettingsScreen 展示系统权限状态

### Commit 0.2：通知隐私默认开启

**改动**：
- `data/local/datastore/UserPreferences.kt:131`：`notificationPrivacy: ?: true`（之前 `?: false`）
- 说明：用户期望隐私保护默认开

**测试**：单元测试 UserPreferences 默认值

### Commit 0.3：App 锁 + 快捷记账保持 opt-in 默认关（不变）

**说明**：合理的 opt-in 功能，不动。

---

## 5. 阶段 0.5（2 commits）— Interceptor 单元测试（新增）

> **Review 补充（2026-07-05）**：当前 ServerUrlInterceptor 的 URL 拼接逻辑
> 缺少 HTTP 层面的集成测试。建议在阶段 0 后加一组 Interceptor 的单元测试，
> 验证各种 serverUrl 格式的拼接结果。

### Commit 0.5.1：ServerUrlInterceptor 拼接逻辑测试

**改动**：`test/java/com/aibill/android/data/remote/interceptor/ServerUrlInterceptorTest.kt`（新）
- 测试 URL 拼接逻辑（各种 serverUrl 格式）
- 测试 401 处理逻辑
- 测试 token 注入逻辑

### Commit 0.5.2：AuthInterceptor 401 处理测试

**改动**：`test/java/com/aibill/android/data/remote/interceptor/AuthInterceptorTest.kt`（已有，补充）
- 测试 401 时 TokenManager.clearToken 调用
- 测试 401 时 AuthEventBus 发出 TokenExpired 事件
- 测试 token 注入逻辑

---

## 6. 阶段 1（2 commits）— 默认开启 AI + 心跳

### Commit 1.1：AI 智能解析默认开启

**改动**：
- `data/local/datastore/UserPreferences.kt`：新增 `Keys.AI_PARSE_ENABLED`，`aiParseEnabled: ?: true`（默认开）
- `service/NotificationMonitorService.kt`：移除 `userPreferences.notificationEnabled.first()` 检查，改读 `aiParseEnabled`（如果关闭就只走正则不调 AI）
- `presentation/ui/settings/SettingsScreen.kt`：删除 AI 智能解析开关（默认开，用户不需要看到）

**说明**：AI 是核心功能，默认开。但保留"AI 关闭"开关作为高级用户选项（隐藏在隐私设置里）。

### Commit 1.2：自动确认 + 健康度心跳默认开启

**改动**：
- `data/local/dao/AutoRuleDao.kt`：默认 `small_amount` 规则 `isEnabled = true`（之前 false）
- `data/local/datastore/UserPreferences.kt`：新增 `HEARTBEAT_ENABLED` 默认 true
- `service/SyncHealthCheckWorker.kt`：注册时按 `heartbeatEnabled` 决定（默认 true 立即注册）
- `presentation/ui/settings/SettingsScreen.kt`：删除"自动确认"、"心跳"开关 UI（默认开，不需要用户操作）

---

## 6. 阶段 2（4 commits）— NLS 通知扩展

### 背景
白名单 15 个包 → 扩到 45+，AI 优先 + 正则交叉验证，订单号优先去重。

### Commit 2.1：白名单扩展到 30+ 家银行

**改动**：`service/NotificationMonitorService.kt:48-67` 加包名：
- 主流银行：工行/中行/建行/农行/招商（已有）/交行（已有）/邮储/民生/广发/中信/光大/华夏/平安/浦发/江苏/南京/宁波/北京/上海
- 数字人民币：dcep
- 三方支付：京东/美团/拼多多/抖音/小红书/QQ 钱包/快手

### Commit 2.2：订单号/商户单号提取正则

**改动**：`util/NotificationParser.kt` 加 5 条正则：
- 尾号 XXXX
- 商户单号：\w+
- 订单号：\w+
- 流水号：\w+
- 摘要中的交易号

返回 `ParseResult.orderId: String?`

### Commit 2.3：NotificationCorrelator 订单号优先去重

**改动**：`util/NotificationCorrelator.kt`
- 第一优先级：订单号/商户单号相同
- 第二优先级：跨包名同金额+10 秒（覆盖"先微信后银行"）
- 第三优先级：现有 5min+同金额（兜底）
- 新增 `CorrelationKey` 枚举

### Commit 2.4：轻量正则交叉验证（5 条核心正则）

**改动**：`util/NotificationParser.kt` 精简到 5 条核心正则：
- 微信/支付宝：支出/收入
- 银行短信：消费/扣款
- 通用：包含支付关键词 + 数字

---

## 7. 阶段 3（5 commits）— SMS 短信监听（RECEIVE_SMS）

### 背景
> **Review 修正（2026-07-05）**：SMS Retriever API 是为一次性验证码设计的，
> 要求短信末尾包含 App-specific hash。银行短信不会包含 App hash，
> 所以 SMS Retriever 实际上读不到银行短信。
>
> 正确方案：
> - 国内分发（非 Google Play）：直接用 `RECEIVE_SMS` 权限
> - Google Play 上架：只能用 Notification Listener 监听短信 App 的通知（已有能力）

### Commit 3.1：RECEIVE_SMS 权限 + BroadcastReceiver

**改动**：
- `AndroidManifest.xml`：注册 `SmsReceivedReceiver` + `RECEIVE_SMS` 权限
- 一次性申请（安装时）
- 申请前检查 `Settings.Secure.SMS_DEFAULT_APPLICATION`

### Commit 3.2：SMS 解析 + 入库

**改动**：
- `service/SmsReceiverService.kt`（新文件）
- 解析 PDU（`SmsMessage.createFromPdu()`）
- 与 NotificationMonitorService 共用 `parseSms()` 方法
- 短信解析后走 AI 优先 + 正则交叉验证流程

### Commit 3.3：银行短信格式库（60+ 家）

**改动**：`util/BankSmsPatterns.kt`（新文件）
- 中国建设银行："您尾号1234的储蓄卡...消费RMB...元"
- 招商银行："您尾号5678...支出...元"
- 工商银行："尾号...消费...元"
- 60+ 银行格式

### Commit 3.4：短信去重 + 与通知关联

**改动**：
- 同一笔交易，短信 + 通知都到时用订单号/商户号去重
- 短信先到，通知后到：保留一条
- 时间窗口：30 秒

### Commit 3.5：短信解析单元测试

**改动**：`test/java/com/aibill/android/util/BankSmsPatternsTest.kt`
- 60+ 银行格式测试
- 关联去重测试

---

## 8. 阶段 4（5 commits）— AI 智能解析（云端）

### Commit 4.1：AI 解析端点扩展

**改动**：`data/remote/api/AiApi.kt` 加 `parseWithMultiSms(text)` 端点，POST 多条 SMS 让 AI 关联（"午饭32 + 银行扣款32 = 同一笔微信→银行卡"）

### Commit 4.2：AI 优先 + 正则交叉验证

**改动**：`service/NotificationMonitorService.kt` 主路径改为：
```
NotificationListenerService → 预筛(hasDigit && hasPaymentSignal) → AI 解析 → 正则结果对比
- AI 与正则金额一致 → trust
- AI 与正则金额不一致 → 降级到正则
- 正则未识别 → 用 AI 结果
- 任一异常 → 标 needs_confirm
```

### Commit 4.3：AI 输出二次校验

**改动**：`util/AiResultValidator.kt`（新文件）
- 金额 `0 < x < 1,000,000`
- category 必须在本地类别表里
- type ∈ {expense, income, transfer}
- 失败时标 status="needs_confirm"

### Commit 4.4：学习曲线优化

**改动**：`domain/usecase/CategoryLearningEngine.kt`
- `hitCount >= 3` 改为 `hitCount >= 2 && 与最近修正 < 30 天`
- 新规则插入时 hitCount=1（不立即应用）

### Commit 4.5：AI 多笔拆分

**改动**：`data/repository/AiRepositoryImpl.kt` 处理 `items: List<...>` 数组
- 按 description 拆分类（命中学习规则 → 用本地）
- 否则全部标 "AI 推断" 让用户确认

---

## 9. 阶段 5（4 commits）— OCR 截图记账

### Commit 5.1：ML Kit 本地 OCR

**改动**：依赖 `com.google.mlkit:text-recognition:chinese`
- `service/OcrService.kt`（新文件）用 ML Kit 识别图片
- 仅本地处理，无云端

### Commit 5.2：启发式解析

**改动**：`util/OcrParser.kt`（新文件）
- 识别图片中的金额、商家、时间
- 启发式：找最大数字=金额，找"¥"或"￥"前后的数字，找最近的汉字=商家
- 置信度低时标 needs_confirm

### Commit 5.3：ShareReceiverActivity 接入

**改动**：`presentation/ShareReceiverActivity.kt` 已存在（接收 SEND 分享 intent）
- 加图片 URI 解析 → OcrService → OcrParser → 落库
- 启发式仅识别"支付截图"特征（金额 + 商家）

### Commit 5.4：OCR 单元测试

**改动**：`test/java/com/aibill/android/util/OcrParserTest.kt`
- 各种截图类型测试

---

## 10. 阶段 6（3 commits）— 增强 CategoryLearningEngine（后续迭代）

> **Review 修正（2026-07-05）**：通用规则引擎（JSON 条件 + 动作）对个人项目过重。
> 用户不会配置 `AMOUNT > 100 AND MERCHANT contains "星巴克"` 这种规则。
> 现有 CategoryLearningEngine 已覆盖 90% 自动分类需求。
>
> 改为增强现有能力，而非重写新引擎。

### Commit 6.1：白名单商家自动确认

**改动**：
- `domain/usecase/AutoConfirmSuggester.kt`：新增 `merchantWhitelist: List<String>`
- 用户可添加"星巴克"、"美团"等常用商家到白名单
- 白名单内商家的交易直接自动确认（不弹窗）

### Commit 6.2：固定金额自动归类

**改动**：
- `domain/usecase/AutoConfirmSuggester.kt`：新增 `fixedAmountRules: Map<Int, Int>`（金额→分类ID）
- 用户可添加"3000分=餐饮"等规则
- 同一金额多次出现自动应用上次分类

### Commit 6.3：增强单元测试

**改动**：
- ~10 个测试覆盖新功能

---

## 11. 阶段 7（2 commits）— fallback 机制

### Commit 7.1：心跳 worker

**改动**：`service/SyncHealthCheckWorker.kt`（新）
- 每 6h 检查 NLS 连接状态
- 断了发前台通知
- 默认开启

### Commit 7.2：通知撤回检测

**改动**：`service/NotificationMonitorService.kt` 实现 `onNotificationRemoved(sbn)`
- 30 秒内未确认的 parsed 记录降级为 raw
- toast 提示

> **AccessibilityService fallback（Review 后降级为实验性）**：
> - Google Play 对 AccessibilityService 审核极严，非无障碍用途会被下架
> - 国内分发可以，但维护成本高（微信/支付宝 UI 经常改版）
> - 标记为"可选/实验性"，不作为核心 fallback
> - 暂不实现，留到后续迭代

---

## 12. 阶段 8（3 commits）— 0 操作引导

### Commit 8.1：3 步安装引导

**改动**：`presentation/ui/auth/OnboardingScreen.kt`（新）
1. 欢迎页 → 2. 一键申请 SMS + 通知权限 → 3. 完成一次测试支付 → 进入主页
- 每步 ≤ 30 秒
- 测试支付通过才进主页

### Commit 8.2：健康度面板

**改动**：`presentation/ui/notification/NotificationCenterScreen.kt`
- 显示 NLS 连接状态、最近 24h 识别率、未确认数
- "健康诊断"按钮（一键跳系统设置）

### Commit 8.3：诊断流程

**改动**：`presentation/ui/settings/HealthCheckScreen.kt`（新）
- 自动检测 + 引导用户修复
- "上次识别时间"、"监听状态"、"电池优化状态"
- 给出具体修复指引

---

## 13. 关键设计模式

### 13.1 "硬约束"原则

```
核心记账功能开关 = 0（系统权限即开关）
辅助功能开关 = opt-in（用户主动启用）
```

### 13.2 六大新场景覆盖

1. **NLS 通知**（阶段 2）— 微信/支付宝/银行 App
2. **SMS Retriever**（阶段 3）— 银行短信（最重要的扩展）
3. **银行 App 通知**（阶段 2 包含）— 通知 App 通知
4. **OCR 截图**（阶段 5）— 分享图片自动识别
5. **智能规则**（阶段 6）— 用户配置规则
6. **NLS 通知 + 短信 + 截图** 三大主场景 = 微信 + 银行 + 截图，**cover 95%+ 真实交易**

---

## 14. 风险与缓解

| 风险 | 缓解 |
|------|------|
| Google Play SMS 权限政策收紧 | SMS Retriever（无 READ_SMS）+ READ_SMS 仅作 fallback |
| OCR 误识别 | ML Kit 本地端侧 + 二次校验 + 用户可编辑 |
| AI 幻觉 | 正则交叉验证 + 二次校验 + 异常时强制确认（不静默入库）|
| 国内 ROM 后台杀进程 | 心跳 worker + 自启动引导 + AccessibilityService fallback |
| 隐私顾虑 | 端侧 AI 选项 + 通知文本脱敏 + 用户可控 AI 开关 |

---

## 15. 测试覆盖规划

| 阶段 | 新增测试 | 累积测试 |
|------|---------|---------|
| 0 | ~6 | 59 |
| 0.5 | ~10 | 69 |
| 1 | ~4 | 73 |
| 2 | ~10 | 83 |
| 3 | ~15 | 98 |
| 4 | ~10 | 108 |
| 7 | ~6 | 114 |
| 8 | ~5 | 119 |
| **合计（核心路径）** | **~66** | **~119** |
| --- | --- | --- |
| 5（后续） | ~8 | ~127 |
| 6（后续） | ~10 | ~137 |

---

## 16. 关键设计文档

### 16.1 新增文档

- `docs/PLANS/auto-capture-v2.md`（**本文档**）

### 16.2 待新增文档（实施时创建）

- `docs/PRINCIPLES.md`（新）— 写原则 1"核心功能零开关"+ 决策矩阵

### 16.3 不修改的文档

- `docs/CONTRIBUTING.md`（**不动**）
- `docs/DEVELOPMENT.md`（**不动**）
- `docs/TESTING.md`（**不动**）
- `docs/PRD.md`（**不动**）
- `docs/README.md`（**不动**）

---

## 17. Review 意见采纳总结

| Review 意见 | 采纳情况 |
|-------------|---------|
| SMS Retriever 读不到银行短信 | ✅ 改用 RECEIVE_SMS 方案 |
| OCR 截图降优先级 | ✅ 降级为后续迭代 |
| 智能规则引擎过重 | ✅ 砍掉，改为增强 CategoryLearningEngine |
| AccessibilityService 慎用 | ✅ 标记为实验性，暂不实现 |
| 工时 ×1.5 | ✅ 已调整 |
| 加 Interceptor 测试 | ✅ 新增阶段 0.5 |
| 加数据流图 | ✅ 已加入 §2.3 |
| 隐私脱敏策略 | ✅ 已加入 §2.4 |

---

## 18. 最终确认

方案已根据 review 意见调整完毕，核心路径 26 commits / ~56h。

**执行顺序**：0 → 0.5 → 1 → 2 → 3 → 4 → 7 → 8

**后续迭代**：OCR 截图（阶段 5）+ 增强 CategoryLearningEngine（阶段 6）

从阶段 0 开始执行。
