# 自动记账 v3 架构重构 PLAN

> 目标：用户支付后零操作，账自动记好。误报率→0，重复确认→0，确认弹窗→0。

---

## 一、当前问题

| # | 问题 | 根因 |
|---|------|------|
| 1 | 微信聊天消息误报刷屏 | 按包名白名单过滤，微信所有通知都进来 |
| 2 | 一笔支付弹 2-3 个确认 | 微信+银行+短信多条通知，本地去重窗口不够 |
| 3 | 每笔都要用户确认 | 中置信度全弹窗，交互成本高 |
| 4 | 本地正则不准 | 格式覆盖有限，分类能力为零 |

---

## 二、设计原则

1. **AI 是唯一裁判**：不在本地判断"是不是支付"，只排除"100%不是支付"的垃圾
2. **后端零改动**：沿用现有 `/api/ai/parse` 单条接口
3. **去重在本地**：缓冲 30s 收齐同一笔的多条通知，去重后逐条调 AI
4. **静默入库不弹窗**：AI 确认是支付就直接入库，用户事后批量审阅
5. **正则降级为兜底**：AI 失败/超时时才用正则提取金额

---

## 三、新架构流程

```
通知/短信到达
    │
    ▼
┌─────────────────────────────────────────┐
│ L1: 排除层（过滤 100% 不是账务的垃圾）   │
│                                         │
│ 微信(com.tencent.mm)：                   │
│   title == "微信支付"/"微信支付凭证" → 放行│
│   title 含 PAYMENT_SIGNAL 关键词 → 放行   │
│   text 含 PAYMENT_SIGNAL 关键词 → 放行    │
│   以上都不满足 → 丢弃（聊天/订阅/广告）   │
│                                         │
│ 支付宝(com.eg.android.AlipayGphone)：    │
│   title 含"支付/账单/花呗/余额/到账" → 放行│
│   text 含 PAYMENT_SIGNAL → 放行           │
│   都不含 → 丢弃（蚂蚁庄园/营销等）        │
│                                         │
│ 银行App/短信App：全部放行                 │
│   （银行App几乎只发账务通知）             │
└─────────────────────────────────────────┘
    │ 放行
    ▼
┌─────────────────────────────────────────┐
│ L2: 缓冲去重（30s 窗口）                 │
│                                         │
│ 通知进入内存缓冲池，不立即处理。          │
│ 触发条件（任一满足即处理）：              │
│   • 最早通知已等 30s（超时）              │
│   • 池中满 5 条（批量效率）               │
│                                         │
│ 去重规则：                               │
│   跨包名 + 同金额(本地正则快速提取) + 30s内│
│   → 合并，只保留信息最丰富的那条           │
│   （优先级：微信 > 银行App > 短信）       │
│                                         │
│ 不合并条件：                             │
│   • 同包名两条 → 不合并（真的两笔）       │
│   • 金额不同 → 不合并                    │
│   • 超过 30s → 不合并                    │
│                                         │
│ 注意：这里的"金额提取"只是用简单正则       │
│ 提取数字用于去重比较，不作为最终结果。     │
└─────────────────────────────────────────┘
    │ 去重后的通知列表
    ▼
┌─────────────────────────────────────────┐
│ L3: 逐条调 AI 解析                       │
│                                         │
│ 优先级：                                 │
│   ① 学习引擎命中 → 直接出结果，跳过 AI    │
│   ② 调 AI /api/ai/parse → 返回结果       │
│   ③ AI 失败 → 正则兜底提取金额           │
│                                         │
│ 学习引擎触发学习：                        │
│   AI 返回结果后，自动学习 merchant→category│
│   下次同商家直接本地命中，不再调 AI        │
└─────────────────────────────────────────┘
    │ 解析结果
    ▼
┌─────────────────────────────────────────┐
│ L4: 入库策略                             │
│                                         │
│ AI 有结果 + 校验通过：                    │
│   → 静默入库 PendingTransaction           │
│   → 触发同步                             │
│   → 发轻通知（纯展示，无按钮，5s自动消失）│
│     "已自动记录：瑞幸咖啡 -¥25.00"       │
│     点击 → 进通知中心页可修改/删除        │
│     不点 → 自动消失                       │
│   → 用户无需任何操作                      │
│                                         │
│ AI 返回空 + 文本含金额数字：              │
│   → 存 NotificationRecord(status="raw")   │
│   → 发轻通知："有 1 笔待确认"（合并通知） │
│     点击 → 进通知中心页编辑确认           │
│                                         │
│ AI 返回空 + 文本无金额：                  │
│   → 丢弃（营销/提醒类）                  │
│                                         │
│ AI 失败 + 正则提取到金额：                │
│   → 存 raw 待审                          │
│   → 合并到"待确认"通知                   │
│                                         │
│ AI 失败 + 正则也失败：                    │
│   → 丢弃                                │
└─────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────┐
│ 用户感知                                 │
│                                         │
│ 实时轻通知（每笔入库时）：               │
│   "已自动记录：瑞幸咖啡 -¥25.00"        │
│   无按钮，5s 自动消失                    │
│   点击 → 进通知中心页可修改              │
│   用户看一眼即可，无需操作               │
│                                         │
│ 待确认合并通知（有待审时）：             │
│   "有 N 笔待确认"                       │
│   点击 → 进通知中心页编辑确认            │
│                                         │
│ 首页卡片：                               │
│   "今日自动记录 6 笔 · 1 笔待确认"       │
│                                         │
│ 通知中心页（主动进入）：                  │
│   已入库的：可修改/删除                   │
│   待确认的：可编辑金额/分类后确认         │
└─────────────────────────────────────────┘
```

---

## 四、代码改动清单

### 新增文件

| 文件 | 职责 |
|------|------|
| `service/NotificationBuffer.kt` | 30s 缓冲队列 + 去重逻辑 + 超时/满批触发 |

### 重构文件

| 文件 | 改动 |
|------|------|
| `service/NotificationMonitorService.kt` | **主流程重写**：handleNotification 改为 排除层→入缓冲池；新增 processBatch() 处理去重后的通知；删除现有的 confidence 分级弹窗逻辑 |
| `util/NotificationCorrelator.kt` | **删除或简化**：去重逻辑移到 Buffer 里，Correlator 不再需要 |
| `util/NotificationHelper.kt` | showConfirmNotification 改为 showAutoRecordedNotification（无按钮、5s消失）；新增 showPendingNotification 合并展示待确认条数 |
| `domain/usecase/AutoConfirmSuggester.kt` | **删除**：不再有"免确认"概念，所有 AI 成功的都直接入库 |

### 小改文件

| 文件 | 改动 |
|------|------|
| `presentation/ui/home/HomeScreen.kt` | 新增"今日自动记录 X 笔"卡片展示 |
| `presentation/ui/home/HomeViewModel.kt` | 查询今日自动入库笔数 |
| `data/local/dao/PendingTransactionDao.kt` | 新增 getTodayAutoCount() 查询 |
| `domain/usecase/CategoryLearningEngine.kt` | AI 返回结果后自动调 learnFromCorrection |
| `util/NotificationParser.kt` | 角色降级：只在 AI 失败时调用作为兜底；额外提供 extractAmountOnly() 给去重层用 |

### 不改的文件

| 文件 | 原因 |
|------|------|
| 后端 API | 沿用 `/api/ai/parse` 单条接口 |
| `NotificationCenterScreen.kt` | 已有批量审阅能力 |
| `NotificationCenterViewModel.kt` | confirmWithEdit/confirmAll 逻辑不变 |
| `AiResultValidator.kt` | 继续用于 AI 结果校验 |
| `NotificationActionReceiver.kt` | 保留（每日汇总通知的点击仍需要） |

---

## 五、去重层详细设计

```kotlin
class NotificationBuffer @Inject constructor() {

    data class BufferedItem(
        val packageName: String,
        val title: String,
        val fullText: String,
        val roughAmount: Int?,  // 用简单正则快速提取的金额（仅用于去重比较）
        val receivedAt: Long = System.currentTimeMillis(),
    )

    private val pool = ConcurrentLinkedDeque<BufferedItem>()
    private var flushJob: Job? = null

    fun enqueue(item: BufferedItem, scope: CoroutineScope, onFlush: suspend (List<BufferedItem>) -> Unit) {
        pool.addLast(item)
        if (pool.size >= 5) { flush(scope, onFlush); return }
        if (flushJob?.isActive != true) {
            flushJob = scope.launch { delay(30_000L); flush(scope, onFlush) }
        }
    }

    private fun flush(scope: CoroutineScope, onFlush: suspend (List<BufferedItem>) -> Unit) {
        flushJob?.cancel()
        val batch = mutableListOf<BufferedItem>()
        while (pool.isNotEmpty()) { pool.pollFirst()?.let { batch.add(it) } }
        if (batch.isEmpty()) return
        scope.launch { onFlush(deduplicate(batch)) }
    }

    /**
     * 去重规则：
     * - 跨包名 + 同金额 + 30s 内 → 合并（保留信息最丰富的）
     * - 同包名不合并（即使同金额，视为两笔真实交易）
     * - 金额为 null 的不参与金额去重（无法判断，全部保留交给 AI）
     */
    internal fun deduplicate(batch: List<BufferedItem>): List<BufferedItem> {
        val result = mutableListOf<BufferedItem>()
        val consumed = mutableSetOf<Int>()

        for (i in batch.indices) {
            if (i in consumed) continue
            var best = batch[i]
            for (j in i + 1 until batch.size) {
                if (j in consumed) continue
                if (shouldMerge(best, batch[j])) {
                    consumed.add(j)
                    if (infoScore(batch[j]) > infoScore(best)) best = batch[j]
                }
            }
            result.add(best)
        }
        return result
    }

    private fun shouldMerge(a: BufferedItem, b: BufferedItem): Boolean {
        if (a.packageName == b.packageName) return false        // 同App不合并
        if (a.roughAmount == null || b.roughAmount == null) return false
        if (a.roughAmount != b.roughAmount) return false        // 金额不同不合并
        if (abs(a.receivedAt - b.receivedAt) > 30_000) return false
        return true
    }

    private fun infoScore(item: BufferedItem): Int {
        var score = item.fullText.length.coerceAtMost(100)
        if (item.packageName.contains("tencent")) score += 20   // 微信信息最丰富
        if (item.packageName.contains("Alipay")) score += 15
        return score
    }
}
```

---

## 六、排除层详细设计

```kotlin
/**
 * 判断通知是否"可能是账务"。
 * 返回 false = 100% 不是账务，直接丢弃。
 * 返回 true = 不确定，交给 AI 判断。
 *
 * 设计原则：极保守，宁可多放不漏。只排除"确定不是"的。
 */
fun isLikelyFinancial(packageName: String, title: String, fullText: String): Boolean {
    return when (packageName) {
        "com.tencent.mm" -> {
            // 微信：title 是"微信支付"直接放行
            if (title == "微信支付" || title == "微信支付凭证" || title.contains("零钱")) return true
            // title 不是微信支付，看内容有没有支付特征
            PAYMENT_SIGNAL.containsMatchIn(fullText)
        }
        "com.eg.android.AlipayGphone" -> {
            if (title.contains("支付") || title.contains("账单") || 
                title.contains("花呗") || title.contains("余额") ||
                title.contains("到账") || title.contains("收款")) return true
            PAYMENT_SIGNAL.containsMatchIn(fullText)
        }
        else -> {
            // 银行/短信等其他白名单 App → 全部放行
            packageName in WHITELIST_PACKAGES
        }
    }
}
```

---

## 七、实施阶段

### Phase 1（核心链路改造）

| 序号 | 任务 | 预计改动量 |
|------|------|-----------|
| 1.1 | 新增 NotificationBuffer.kt | 新文件 ~80 行 |
| 1.2 | 重构 NotificationMonitorService.handleNotification | 重写主方法 ~60 行 |
| 1.3 | 新增 processBatch() 方法（学习引擎→AI→正则降级→入库） | ~80 行 |
| 1.4 | 删除 showConfirmNotification 逐条弹窗调用 | 删除 |
| 1.5 | 删除 AutoConfirmSuggester / confidence 分级逻辑 | 删除 |
| 1.6 | 编译验证 + 安装测试 | — |

### Phase 2（用户感知优化 + 分享 OCR）

| 序号 | 任务 |
|------|------|
| 2.1 | 首页"今日自动记录 X 笔 · N 笔待确认"卡片 |
| 2.2 | 通知中心页增加"已自动入库"分区 |
| 2.3 | ShareReceiverActivity 支持图片分享 → ML Kit 本地 OCR → AI parse → 入库 |
| 2.4 | 添加 Google ML Kit Text Recognition 依赖（本地离线，无后端成本） |

### Phase 3（无障碍服务 — 覆盖无通知场景）

| 序号 | 任务 |
|------|------|
| 3.1 | 新增 PaymentAccessibilityService：监听微信/支付宝支付结果页 |
| 3.2 | 从 View 节点树提取金额、商家名，走统一入库流程（Buffer → AI → 入库） |
| 3.3 | 跨渠道去重：无障碍检测到的和通知检测到的同一笔，通过 Buffer 金额+时间去重 |
| 3.4 | 权限引导页新增"无障碍服务"项（无法检测状态，不打勾，常驻"去设置"按钮） |
| 3.5 | 持续适配：微信/支付宝版本更新后 View 结构可能变化，需维护提取规则 |

---

## 八、效果预估

| 指标 | 当前 | 改后 |
|------|------|------|
| 误报率 | ~30%（微信聊天） | <1%（排除层过滤） |
| 重复确认 | 2-3次/笔 | 0（缓冲去重） |
| 用户每日操作次数 | 8+（逐条确认） | 0（看通知即可，无需点击） |
| AI 调用量 | 不可控（聊天也调） | = 真实支付笔数 × (1-学习命中率) |
| 漏报率 | ~5% | <5%（AI+正则双兜底+待审池） |
| 用户感知延迟 | 即时弹窗 | 30s（缓冲去重窗口） |

---

## 九、风险与回退

| 风险 | 应对 |
|------|------|
| AI 服务宕机 | 正则兜底提取金额，存待审 |
| 30s 缓冲导致用户感知延迟 | 可接受：用户不会刚付完就打开 App 看 |
| 缓冲池进程被杀丢数据 | Buffer 用 Room 持久化（Phase 2 优化） |
| 学习引擎误匹配 | 用户在通知中心修改后自动纠正 |
| 排除层误杀真实支付 | 极低概率：只有 title 和 text 都不含任何金额/支付关键词才丢弃 |

---

## 十、数据来源全景

| 渠道 | 触发时机 | 覆盖场景 | 技术方案 | 权限 | 阶段 |
|------|----------|----------|----------|------|------|
| 通知监听 | 支付App/银行推送通知 | 微信/支付宝/银行付款 | NotificationListenerService | 通知使用权 | Phase 1 |
| 短信读取 | 银行发消费短信 | 银行卡消费 | BroadcastReceiver | 短信权限 | 已有 |
| 无障碍服务 | 用户停留在支付结果页 | App内静默支付/无通知场景 | AccessibilityService | 无障碍权限 | Phase 3 |
| 分享OCR | 用户分享截图给App | 补录/境外/漏记 | ShareReceiver + ML Kit | 无 | Phase 2 |

### 各渠道互补关系

```
用户一笔支付可能被多个渠道捕获：
  微信付款 ¥25
    → 通知监听捕获微信支付通知（70%概率有通知）
    → 短信捕获银行消费短信（银行卡支付才有）
    → 无障碍捕获支付成功页（100%会经过这个页面）

所有渠道统一进入 NotificationBuffer：
    → 跨渠道去重（同金额+60s内）
    → 只保留信息最丰富的一条
    → 调 AI 入库
```

### 权限引导页设计

```
权限引导页展示规则：
  • 可程序化检测的权限 → 显示勾/叉 + "去开启"按钮
  • 无法检测的权限 → 不显示勾/叉，常驻"去设置"按钮 + 说明文字

┌────────────────────────────────────────────────┐
│  权限设置                                       │
│                                                │
│  ✓ 通知监听权限                    [已开启]     │
│    读取支付通知以自动记账                       │
│                                                │
│  ✓ 通知弹窗权限                    [已开启]     │
│    记账成功后轻通知提醒                         │
│                                                │
│  ✓ 电池优化白名单                  [已开启]     │
│    避免系统杀死后台服务                         │
│                                                │
│  • 无障碍服务(支付页识别)           [去设置 →]   │
│    识别微信/支付宝支付结果页，覆盖无通知场景    │
│    ⚠️ 开启后支付时无需通知也能自动记账          │
│                                                │
│  • 后台自启动                       [去设置 →]   │
│    确保 App 被系统杀死后能自动恢复              │
│                                                │
└────────────────────────────────────────────────┘
```

---

## 十一、验收标准

- [ ] 微信聊天消息不再出现在通知中心
- [ ] 一笔支付只产生一条入库记录（不管收到几条通知）
- [ ] 正常支付（微信/支付宝/银行）30s 内自动入库，无需任何用户操作
- [ ] AI 不可用时，含金额的通知仍会存入待审池
- [ ] 首页能看到今日自动记录笔数
- [ ] 编译通过 + 安装到设备验证
