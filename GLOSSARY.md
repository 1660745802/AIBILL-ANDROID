# GLOSSARY — 领域词汇表

> 架构词汇（module / interface / depth / seam / adapter / leverage / locality）不写在这里，
> 它们不属于领域语言。本表只给**领域概念**命名，让模块的名字来自业务而不是来自包结构。
>
> 关联：[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)、[docs/NOTIFICATION.md](docs/NOTIFICATION.md)

## 记账与队列

| 术语 | 含义 | 代码位置 |
|---|---|---|
| **记账（记一笔）** | 让一笔收支成为本地事实：手工、通知自动识别、通知确认、外部 Intent、小组件入口都算 | `RecordIntake`（入账 module，见 [架构文档 §2.3](docs/ARCHITECTURE.md#23-入账recordintake)） |
| **入账意图**（intent） | 调用方对入账 module 表达的「要记这一笔」，一个 subtype = 一条历史写入路径的全部差异 | `RecordIntent` |
| **入账行 / 队列行** | `pending_transactions` 表的一行。一次记账落库的产物，未必已上传 | `PendingTransactionEntity` |
| **离线队列** | `pending_transactions` 表中尚未确认上传到服务端的全部行。入队/快照/排调度/失败恢复是**一个** module | `SyncQueue`（离线队列 module，见 [架构文档 §2.5](docs/ARCHITECTURE.md#25-离线队列syncqueue)） |
| **排队等上传**（pending） | `sync_status='pending'`：可被 `SyncWorker` 自动捞起。**不含 failed** | `PendingTransactionDao.getAllPending()` / `SyncQueueStatus.pending` |
| **待人管**（failed） | `sync_status='failed'`：本地终态，需要用户介入，不会自动重试（401 除外） | `SyncWorker.markFailed` / `SyncQueueStatus.failed` |
| **已入队 ≠ 已上传** | 排上 `SyncWorker` 只代表 WorkManager 接受了请求。这个区别现在**是类型**不是注释 | `SyncQueue.requestSync()` → `SyncRequest.Enqueued` |
| **同步锁（SyncLock）** | 换号/清缓存前等在飞的同步循环跑完，避免 A 的账写进 B 的服务端 | `data/local/datastore/SyncLock.kt` |

## 通知入账

| 术语 | 含义 | 代码位置 |
|---|---|---|
| **来源渠道** | 通知入账的三个平台入口：通知监听（NLS）、无障碍（A11Y）、短信（SMS）。adapter 只做平台翻译，产出同一个 `Capture` | `SourceChannel` |
| **入队项** | 一个渠道交给入账流程的一条候选：`Item`（包名 / 标题 / 全文 / 渠道 / 到达时间） | `NotificationProcessor.Item` |
| **预筛** | 调 AI 之前「这条通知值不值得当账」的判定链（自身通知、日报汇总、促销句式、营销词、账务判据……）。**文本判据三渠道同一套；内容级去重/记忆/熔断只对 NLS 开启** | `NotificationPrefilter`（预筛 module，见 [架构文档 §2.4](docs/ARCHITECTURE.md#24-预筛notificationprefilter)） |
| **判据快照** | 一代规则的不可变投影（词表 + 已编译正则 + 代际），预筛只认它不认云控 | `NotificationRuleSnapshot` / `NotificationRuleSource` |
| **AI 解析结果校验** | AI 返回金额/分类后的佐证校验，防止金额幻觉 | `AiResultValidator` |
| **评分窗口** | 同金额的 AI 结果在 10s 内择优，跨渠道同一笔交易只保留最优视图 | `NotificationProcessor` scoringPool |
| **跨渠道去重** | 同金额 + 时间窗内，判为同一笔交易的第二个视图 | 内存级 + DB 兜底两级 |
| **待审池** | AI 有结果但信息不完整，进池等用户确认 | `status='parsed'` |
| **轻通知 / 确认通知 / 回执通知** | 自动入库后的「✓ 已记录」/ 信息不全时的确认弹窗 / 确认后的反馈 | `NotificationHelper` |
| **通知审计记录** | `notification_records` 表：捕获到但未必记账的通知与解析结果 | `NotificationRecordEntity` |

## 身份与本地数据

| 术语 | 含义 | 代码位置 |
|---|---|---|
| **会话（session）** | token + userId + 用户信息，生命周期随登录 | `TokenManager.saveSession / endSession` |
| **上次已知用户（last known user）** | 跨 session 存活的 userId，本地缓存的**归属判据** | `TokenManager.getLastKnownUserId()` |
| **同号重登** | 旧 userId == 新 userId：保留离线队列，只重置 401 卡住的记录 | `AuthRepositoryImpl.onSameAccountRelogin` |
| **换号** | 旧 userId != 新 userId：清空 pending/categories/accounts/notifications | `AuthRepositoryImpl.clearLocalCache()` |
| **换服务器** | 改 `server_url` 并清本地：与上面两类逐表一致 | `ServerConfigViewModel.onSave` |
| **服务端派生缓存** | categories / accounts：服务端真相的本地副本，登录后重新拉取 | `CategoryRepository` / `AccountRepository` |
