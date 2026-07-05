# AIBILL 自动记账能力 v2 方案

> **作者**：AI 协助（基于多轮 review）
> **状态**：待 review
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

**对 AIBILL 的启发**：SMS Retriever 是 Google Play 政策最友好的银行短信获取方式（不需要 READ_SMS），覆盖 60+ 中国银行短信是最高 ROI 的扩展。

### 2.2 BeeCount（1.9k stars, 256 forks）

**关键设计**：
- 8 大输入渠道：NLS 通知 / SMS / OCR 截图 / 语音 / 无障碍 / WebDAV / AI 对话 / 智能规则
- 多账本、多账户、二级分类、预算管理
- BSL 协议（个人免费，商业付费）

**对 AIBILL 的启发**：6 大场景（NLS + SMS + 银行 App + 三方 + OCR + 规则）覆盖 95%+ 真实交易。

---

## 3. 实施阶段（8 阶段 / 34 commits / ~49h）

| 阶段 | 主题 | commits | 估计工时 |
|------|------|---------|---------|
| **0** | 移除反人类开关 | 3 | 4h |
| **1** | 默认开启 AI + 心跳 | 2 | 3h |
| **2** | NLS 通知扩展 | 4 | 6h |
| **3** | SMS Retriever | 6 | 10h |
| **4** | AI 智能解析 | 5 | 6h |
| **5** | OCR 截图记账 | 4 | 6h |
| **6** | 智能规则引擎 | 4 | 6h |
| **7** | fallback 机制 | 3 | 4h |
| **8** | 0 操作引导 | 3 | 4h |
| **合计** | | **~34** | **~49h** |

执行顺序：0 → 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8

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

## 5. 阶段 1（2 commits）— 默认开启 AI + 心跳

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

## 7. 阶段 3（6 commits）— SMS Retriever

### 背景
**关键洞察**（来自 PennyWise 调研）：SMS Retriever 是 Google Play 政策最友好的银行短信获取方式，**不需要 READ_SMS 权限**。覆盖 60+ 中国银行短信。

### Commit 3.1：Google Play SMS Retriever API 集成

**改动**：新增 `service/SmsReceiverService.kt`
- 注册 `SmsRetriever.SMS_RETRIEVED_ACTION` BroadcastReceiver
- 解析 SMS 文本（`SmsMessage.createFromPdu()` 或 `Telephony.TextSmsMessage`）
- 与 NotificationMonitorService 共用 parseSms

### Commit 3.2：Telephony SMS Receiver 兜底

**改动**：Android 14+ 之后 SMS Retriever 在某些设备上不稳，Telephony.SmsReceivedReceiver 作为兜底
- 申请 `RECEIVE_SMS` 权限（一次性）
- 解析 PDU

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

### Commit 3.6：SMS Retriever Manifest 配置

**改动**：`AndroidManifest.xml` 加 SMS Retriever 注册 + RECEIVE_SMS 权限

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

## 10. 阶段 6（4 commits）— 智能规则引擎

### Commit 6.1：规则数据模型

**改动**：`data/local/entity/AutoRuleEntity.kt` 扩展
- `conditions: String`（JSON 数组）
- `actions: String`（JSON 数组）
- Room Migration 7→8（加列）

### Commit 6.2：规则评估引擎

**改动**：`domain/usecase/RuleEvaluator.kt`（新文件）
- 6 字段：AMOUNT/TYPE/CATEGORY/MERCHANT/NARRATION/BANK_NAME
- 操作符：<, >, =, contains, equals, starts with, is
- AND/OR 组合
- 5 种动作：Set, Append, Prepend, Clear, Block

### Commit 6.3：规则 UI（设置页）

**改动**：`presentation/ui/settings/AutoRulesScreen.kt` 重写为规则编辑器
- 添加/编辑/删除规则
- 条件组合

### Commit 6.4：规则单元测试

**改动**：~15 个测试覆盖各种条件组合

---

## 11. 阶段 7（3 commits）— fallback 机制

### Commit 7.1：心跳 worker

**改动**：`service/SyncHealthCheckWorker.kt`（新）
- 每 6h 检查 NLS 连接状态
- 断了发前台通知
- 默认开启

### Commit 7.2：通知撤回检测

**改动**：`service/NotificationMonitorService.kt` 实现 `onNotificationRemoved(sbn)`
- 30 秒内未确认的 parsed 记录降级为 raw
- toast 提示

### Commit 7.3：AccessibilityService fallback

**改动**：`service/ExpenseAccessibilityService.kt`（新）
- 仅在 NLS 失灵 3 天后引导用户开启
- 读取微信支付完成页/支付宝付款成功页
- 启发式正则提取金额
- opt-in（不在 Settings 默认展示）

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
| 1 | ~4 | 63 |
| 2 | ~10 | 73 |
| 3 | ~15 | 88 |
| 4 | ~10 | 98 |
| 5 | ~8 | 106 |
| 6 | ~15 | 121 |
| 7 | ~6 | 127 |
| 8 | ~5 | 132 |

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

## 17. 请你 review 重点

| 维度 | 你的问题 |
|------|---------|
| 原则 | "核心功能零开关"是否符合你的预期？ |
| 阶段 0 | 删 `notificationEnabled` 开关、隐私默认开，能接受吗？ |
| 阶段 3 | SMS Retriever 作为新主路径，覆盖 60+ 银行短信，覆盖率够吗？ |
| 阶段 5 | OCR 截图记账需要 ML Kit 库（+5MB），值得吗？ |
| 阶段 6 | 智能规则引擎是否过度设计？ |
| 工时 | 49h / 6 工作日，可以接受吗？ |
| 顺序 | 是否按 0→1→2→3→4→5→6→7→8 顺序？ |
| 隐私 | "通知文本脱敏"具体怎么脱敏？ |

---

## 18. 等待你的确认

请逐项 review 后告诉我：

1. **原则"核心功能零开关"**是否接受？
2. **阶段 0 的 3 个 commits**是否同意优先执行？
3. **SMS Retriever 作为新主路径**是否接受？
4. **OCR 截图 + ML Kit**是否必要？
5. **智能规则引擎**是否需要？
6. **执行顺序**是否按 0→1→2→3→4→5→6→7→8？

确认后请让我退出 Plan Mode，进入 Build Mode 开始执行（从阶段 0 开始）。
