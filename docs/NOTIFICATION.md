# 通知监听 v3 架构

> 项目核心差异化能力。**所有接入通知记账功能的开发者必读**。
>
> 三渠道（通知 + 无障碍 + SMS）统一入 `NotificationBuffer` → 跨渠道去重 → AI 解析 → 入库。

## 一、数据流总览（L1-L4 分层）

```
┌───────────────────────────────────────────────────────────────┐
│  信号源                                                       │
│  NotificationListenerService / SmsReceiverService / AccessibilityService │
└───────────────────────────┬───────────────────────────────────┘
                            ▼
┌───────────────────────────────────────────────────────────────┐
│ L1: 排除层 (isLikelyFinancial)                                │
│   微信：title=="微信支付" 或 全文含 PAYMENT_SIGNAL → 放行        │
│   支付宝：title 含支付/账单/花呗/余额/到账 → 放行               │
│   银行/短信 App：全部放行                                      │
│   效果：微信聊天/订阅号/广告 100% 过滤                          │
└───────────────────────────┬───────────────────────────────────┘
                            ▼
┌───────────────────────────────────────────────────────────────┐
│ L2: 缓冲去重 (NotificationBuffer, 60s 窗口)                   │
│   60s 或满 5 条时 flush                                        │
│   跨包名 + 同金额 + 60s 内 → 合并（保留信息最丰富的）           │
│   效果：微信+银行+短信同一笔 → 只保留 1 条给 AI                │
└───────────────────────────┬───────────────────────────────────┘
                            ▼
┌───────────────────────────────────────────────────────────────┐
│ L3: 解析（学习引擎 > AI > 正则降级）                           │
│   ① CategoryLearningEngine 命中 → 秒出结果，跳过 AI（0 成本）  │
│   ② AI /api/ai/parse → 返回金额/类型/分类/商家                 │
│   ③ AI 失败/超时 → 丢弃（正则不面对用户）                       │
│   AI 成功后自动触发 learnFromCorrection(merchant→category)    │
└───────────────────────────┬───────────────────────────────────┘
                            ▼
┌───────────────────────────────────────────────────────────────┐
│ L4: 入库策略                                                   │
│   AI 结果 + 信息完整 → 入库 + 轻通知（无按钮 5s 消失）         │
│   AI 结果 + 缺关键信息 → 待审池 + 发确认通知                   │
│   AI 返回空 / 失败 → 丢弃                                      │
│   ★ 正则永远不面对用户，仅用于去重层提取金额比较                │
└───────────────────────────────────────────────────────────────┘
```

## 二、核心设计原则

| 原则 | 说明 |
|---|---|
| AI 是唯一裁判 | 不在本地判断"是不是支付"，只排除"100%不是"的垃圾 |
| 正则永远不面对用户 | 正则仅用于去重金额提取，不生成记录、不发通知 |
| 用户看到的 = AI 确认的 | 待审池只接受"AI 有结果但缺信息"的记录 |
| 入库即通知 | AI 确认入库后发轻通知（无按钮，5s 消失），用户知道但无需操作 |
| 去重在本地 | 60s 缓冲覆盖银行短信延迟，后端零改动 |

## 三、数据来源（多渠道互补）

| 渠道 | 技术方案 | 覆盖场景 | 权限 |
|---|---|---|---|
| 通知监听 | `NotificationListenerService` | 微信/支付宝/银行付款通知 | 通知使用权 |
| 短信读取 | `BroadcastReceiver(RECEIVE_SMS)` | 银行卡消费短信 | 短信权限 |
| 无障碍服务 | `PaymentAccessibilityService` | 支付结果页（覆盖无通知场景） | 无障碍权限 |
| 分享 OCR | `ShareReceiverActivity` + ML Kit | 用户主动截图/分享补录 | 无 |

## 四、分类学习（CategoryLearningEngine）

- 精确匹配 → 包含匹配（近似词边界）→ 无匹配返回 null
- AI 返回结果后自动 `learnFromCorrection(merchant, categoryId)`
- 用户在通知中心修改分类后反向学习
- 稳态后学习命中率 60-70%，大幅减少 AI 调用

## 五、AI 结果校验（AiResultValidator）

| 规则 | 校验内容 |
|---|---|
| 金额范围 | 0 < amount ≤ 1,000,000 分（1 万元） |
| 类型合法 | expense / income / transfer |
| 分类存在 | categoryId 在本地分类表中 |
| 描述长度 | ≤ 200 字符 |

校验通过 = 信息完整 → 直接入库；校验失败 = 缺关键信息 → 待审池。

## 六、权限引导

| 权限 | 可检测 | 展示方式 |
|---|---|---|
| 通知监听 | ✓ | ✓/✗ + "去开启" |
| 通知弹窗 | ✓ | ✓/✗ + "去开启" |
| 电池优化白名单 | ✓ | ✓/✗ + "去设置" |
| 无障碍服务 | ✗ | 常驻"去设置"按钮 |
| 后台自启动 | ✗ | 常驻"去设置"按钮 |

---

## 七、实战经验（踩坑清单）

> 这些是开发过程中实际踩过的坑。新成员改这块代码前务必读完。

### 7.1 通知文本解析：金额分散在任意字段

`EXTRA_TITLE` / `EXTRA_TEXT` / `EXTRA_BIG_TEXT` / `EXTRA_SUB_TEXT` / `EXTRA_INFO_TEXT` 都可能含金额，**必须合并全文**再解析。只取 `EXTRA_TEXT` 会导致金额解析为 0。

```kotlin
val fullText = listOf(title, text, bigText, subText, infoText)
    .filter { it.isNotBlank() }.distinct().joinToString(" ")
// 后续所有引用都用 fullText，不要残留旧的 text/title 变量
```

### 7.2 AI 兜底调用要"双重拦截"

正则命中优先，正则失败才走 AI。但 AI 调用有成本 + 隐私风险（会把通知全文发给后端），微信/短信白名单会捕获大量普通聊天，必须预筛：

```kotlin
val hasDigit = text.any { it.isDigit() }
val hasPaymentSignal = text.contains(Regex(
    "[¥￥$]|元|支付|付款|收款|到账|消费|交易|转账|红包|退款|扣款|余额|账单|还款"
))
if (!hasDigit || !hasPaymentSignal) return  // 直接丢弃，不存 raw 不调 AI
```

否则微信聊天会以 `raw` 状态刷屏通知中心，且含数字的聊天会白白触发后端 AI。

### 7.3 隐私：`sourceDetail` 不要存全文

`PendingTransactionEntity.sourceDetail` 会同步到后端。存来源名（"微信支付"）而非 `fullText`（可能含私聊内容）。三处构造入口（Service 静默入库、ViewModel confirmItem、confirmAll）字段应保持一致。

### 7.4 批量确认要跳过无金额记录

`confirmAll` 必须跳过 `parsedAmount <= 0` 的记录，否则会生成 0 元账单同步到后端（资损/脏数据）。单条确认走编辑对话框（有金额校验），批量确认无对话框保护，需代码兜底。

### 7.5 收支类型不要硬编码

确认通知标题、图标等不要写死"支出"。parser/AI 会产出 income（收款到账、退款），需按 `type` 动态显示，否则收入被标成支出。

### 7.6 通知点击跳转

- `MainActivity` 必须声明 `android:launchMode="singleTop"`，否则点击通知会重建 Activity 而非走 `onNewIntent`，丢失返回栈
- `navigate_to` extra 要"一次性消费"：NavHost 的 `LaunchedEffect(navigateTo)` 处理后回调清空，否则解锁/重建时会误跳

### 7.7 MIUI 测试限制

- MIUI 对 `adb shell cmd notification post` 发的通知**不分发**给 `NotificationListenerService`，无法用 adb 造测通知。用 App 内测试按钮发本地通知，或用真实支付通知验证
- `MainActivity` 用 `BiometricPrompt`（应用锁）时必须继承 `FragmentActivity`，`ComponentActivity` 会崩溃
