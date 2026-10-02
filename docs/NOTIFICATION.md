# 通知监听架构（v3 设计 + v4 现状）

> 项目核心差异化能力。**所有接入通知记账功能的开发者必读**。
>
> 三渠道（通知 + 无障碍 + SMS）统一入 `NotificationProcessor` → 评分窗口 → 跨渠道去重 → AI 解析 → 入库。

## 〇、v4 相对 v3 的变更（先读这段，否则下面 L1-L4 会误导你）

| # | 变更 | 原因 |
|---|---|---|
| 1 | **删除 `NotificationBuffer` 缓冲层**（原 L2），改为 `NotificationProcessor` 内的**评分窗口**（默认 10s，按 `amount` 做 key） | v3 把多渠道通知合并成一条长文本再喂 AI，A11Y 全支付页 + NLS 短确认能合出 800 字把后端打挂。v4 改为**每渠道独立调 AI**，再用评分窗口挑最优 |
| 2 | 去重从「入 AI **之前**」改为「AI 结果出来**之后**按 (amount, type) 判重」 | v3 用字符级文本去重，「¥ vs ￥」就漏判；v4 用语义级金额判重，60s 窗口 |
| 3 | 去重**同时**做内存级 + DB 级双层，DB 兜底跨渠道 | 三渠道可能并发到达，`insertMutex` 串行化 dedup-check + insert 防双写 |
| 4 | 同渠道同包名同金额在窗口内 = **两笔真实交易**，立即提交前一笔并开新窗口 | 不能把「10 秒内两杯 ¥15 咖啡」合并丢一笔 |
| 5 | 新增电商描述脱敏（拼多多/淘宝/京东… → "XX购物"） | 避免具体商品名进账单 |
| 6 | 分享入口只支持 `text/plain`，**没有 OCR** | 曾计划接 ML Kit，未落地 |
| 7 | 金额上限 = **10 万元**（10,000,000 分），与云控 `processor.max_amount_cents` 一致 | 旧文档写的「1 万元」是错的 |

> 下文 L1/L3/L4 的描述与现状基本一致；**L2（缓冲去重）已被 v4 的评分窗口取代**。

## 〇·2、2026-10-01 真机日志回归修复

在 v4 基础上补的 4 层防护，均为**行为级**修复，**未改动云控 `scripts/rules.json`**（放宽/收紧放行词表会全局影响所有在线用户的放行率，需单独走配置变更流程 + 真机验证）。

| # | 问题 | 修复 | 为什么不动配置 |
|---|---|---|---|
| 8 | **自身通知自激**：本 App 自己发的「已记账 · …」/「💰 检测到一笔支出 …」被自己的 NLS 当成新账务再解析。实测 16 次 AI 调用里 3 次是自激，第二个候选仅靠 60s 金额去重侥幸挡下，AI 稍慢就会真的记两笔 | `NotificationMonitorService` 最前面丢弃自身包名 | 与云控无关，纯代码缺陷。手动记账/小组件/外部 Intent 走 `AutoRecordActionReceiver` 直写 DB，不过 NLS，丢弃零副作用 |
| 9 | **资讯类推送反复烧 AI**：B 站同一标题 6 次调 AI / 5 次 `5001`（间隔 6s/12s/5min/25min，5s 短窗去重拦不住） | ① 新增 5min **长窗内容记忆**（只在过滤层之后，只挡重复调 AI）② 新增**单包 AI 熔断**：连续 3 次失败 → 该包静音 5min | `payment_signal_regex` 含裸「元」「消费」，无法把新闻标题与真实账务区分开；收紧它会误杀真实账单。改用行为级手段：重复调用从 6 次压到 1 次 |
| 10 | **A11Y 判定顺序倒置 + 隐私泄露**：原本先全树采集并把 20 条原文**落库**，再判断排除词；微信主界面/聊天列表反复刷屏，且聊天内容进了可导出的 `app_logs` | ① 命中成功词后才落库页面文本，普通页只记一行汇总 ② 排除词/历史日期/商家上下文复用同一份 `allTexts`，**全树遍历从最多 4 次降到 1 次** | 同上，与云控无关 |
| 11 | **A11Y STATE 路径零去重**：一次 Activity 切换连发 STATE + CONTENT，原 `contentChangeThrottle` 只管 CONTENT，导致同页扫描+落库两次 | 新增 `scanThrottle` 1.5s，STATE+CONTENT 统一节流；延迟重试绕过节流（否则渐进渲染的金额会被吃掉） | 同上 |

**熔断的作用域**：`isAiMuted` 只由「不在白名单」的包名查询（见 `NotificationMonitorService`），**银行/微信/支付宝永不被熔断**，避免网络抖动导致真实账单被误杀。

**内存去重的位置修正**：`recentNotificationKeys` 的 5s 去重原先排在「正在运行」/空文本等早退分支**之后**，对它们完全失效（MIUI 会把同一条分组摘要以完全相同文本 post 数十次）。现已前移到所有早退分支之前。

**未修复（有意保留）**：`scoringPool` 以 `amount` 为单键、`isRootForEvent` 要求 windowId 严格相等（`PaymentAccessibilityRecognitionTest` 有用例显式锁定）。二者在理论上会漏掉「同额两笔真实交易」和「支付结果页为独立 window」的场景，但本次日志中**没有观测到真实发生**，改动需先确定预期行为，故留待后续。

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
| 分享文本 | `ShareReceiverActivity`（`text/plain`） | 用户主动分享文本补录 | 无 |

## 四、分类学习（CategoryLearningEngine）

- 精确匹配 → 包含匹配（近似词边界）→ 无匹配返回 null
- AI 返回结果后自动 `learnFromCorrection(merchant, categoryId)`
- 用户在通知中心修改分类后反向学习
- 稳态后学习命中率 60-70%，大幅减少 AI 调用

## 五、AI 结果校验（AiResultValidator）

| 规则 | 校验内容 |
|---|---|
| 金额范围 | 0 < amount ≤ 10,000,000 分（**10 万元**） |
| 类型合法 | expense / income / transfer |
| 分类存在 | categoryId 在本地分类表中 |
| 描述长度 | ≤ 200 字符 |

校验通过 = 信息完整 → 直接入库；校验失败 = 缺关键信息 → 待审池。

## 六、权限引导

检测项与跳设置页逻辑见 [BatteryOptimizationHelper.kt](../app/src/main/java/com/aibill/android/util/BatteryOptimizationHelper.kt)，UI 渲染见 [PermissionGuideScreen.kt](../app/src/main/java/com/aibill/android/presentation/ui/settings/PermissionGuideScreen.kt)。

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
