# AIBILL Android 架构文档

> 本文档只讲**架构设计**：分层、模块、API 契约、技术决策、网络、同步、数据模型。
> 通知监听 v3 → [NOTIFICATION.md](./NOTIFICATION.md)
> 开发规范、测试、AI 协作 → [CONTRIBUTING.md](./CONTRIBUTING.md)
> 发布流程 → [RELEASE.md](./RELEASE.md)
>
> 对应代码：`app/src/main/java/com/aibill/android/`
> 适用版本：versionCode 19 / versionName 1.5.12 / minSdk 26 / targetSdk 35

---

## 一、架构总览

### 1.1 分层架构

```
┌─────────────────────────────────────────────────────┐
│              Presentation Layer                      │
│   Compose UI ← ViewModel ← UiState (StateFlow)      │
└────────────────────────┬────────────────────────────┘
                         │ UseCase 调用
┌────────────────────────▼────────────────────────────┐
│                Domain Layer                          │
│   UseCase ← Repository Interface ← Domain Model     │
└────────────────────────┬────────────────────────────┘
                         │ Repository 实现
┌────────────────────────▼────────────────────────────┐
│                  Data Layer                          │
│   Remote (Retrofit)  ←→  Local (Room + DataStore)   │
└─────────────────────────────────────────────────────┘
```

### 1.2 单向数据流

```
UI Event → ViewModel → UseCase → Repository → [Remote API / Local DB]
                                                      ↓
UI ← Compose State ← StateFlow ← Flow ← Repository ← Result
```

ViewModel 标准模式：
- **持续状态**：`StateFlow<UiState>`（暴露 `asStateFlow()`，Compose 用 `collectAsStateWithLifecycle`）
- **一次性事件**：`Channel<UiEvent>` + `receiveAsFlow()`（Toast/Snackbar/导航不被重组重复消费）
- **进程恢复**：`SavedStateHandle.getStateFlow("key", default)`（筛选条件、滚动位置）

### 1.3 三层模型规范

| 模型 | 位置 | 职责 | 注解 |
|---|---|---|---|
| DTO | `data/remote/dto/` | 网络序列化 | `@JsonClass(generateAdapter=true)`, `@Json(name=)` |
| Domain | `domain/model/` | 业务实体（纯 Kotlin） | 无 Android 依赖 |
| Entity | `data/local/entity/` | Room 映射 | `@Entity`, `@PrimaryKey` |

转换通过 Mapper 函数，禁止跨层直接使用模型。

---

## 二、模块设计

### 2.1 当前目录

```
app/src/main/java/com/aibill/android/
├── AiBillApp.kt                 # Application：Timber + 定时任务调度
├── di/                          # Hilt 模块（10 个：Network/Database/Repository/Work/RecordIntake/Queue/NotificationPrefilter/Ownership/Undo/CoroutineScope）
├── domain/
│   ├── model/                   # Transaction / User / Result / CategoryAndAccount / SyncQueueStatus
│   ├── record/                  # RecordIntake：入账 interface + 意图（见 §2.3）
│   ├── queue/                   # SyncQueue：离线队列 interface + SyncRequest（见 §2.5）
│   ├── ownership/               # LocalDataOwnership：归属与处置 interface + 三种意图（见 §2.6）
│   ├── undo/                    # RecordUndo：撤销 interface + UndoOutcome（见 §2.7）
│   ├── notification/            # NotificationPrefilter：预筛 interface + 判据词汇（见 §2.4）
│   ├── repository/              # 9 个接口（Auth/Transaction/Category/Account/Ai/...；离线队列已不在此，见 §2.5）
│   └── usecase/                 # CategoryLearningEngine / StreakTracker
├── data/
│   ├── local/
│   │   ├── db/AppDatabase.kt    # Room v7，9 张表
│   │   ├── entity/              # 9 个 Entity
│   │   ├── dao/                 # 9 个 DAO
│   │   └── datastore/           # UserPreferences + SyncLock
│   ├── record/                  # 入账实现 + RecordClock
│   ├── queue/                   # 离线队列：SyncQueueImpl + RecordWriter（两个 adapter）+ SyncScheduler（见 §2.5）
│   ├── ownership/               # 本地数据归属：LocalDataOwnershipImpl（4 张表的唯一清单，见 §2.6）
│   ├── undo/                    # 撤销：RecordUndoImpl（按 sync_status 的撤销真值表，见 §2.7）
│   ├── notification/            # 预筛实现 + NotificationRuleSource（两个 adapter）
│   └── remote/
│       ├── api/                 # 10 个 Retrofit API（含 AppUpdateApi + GithubReleaseApi）
│       ├── dto/request|response/
│       ├── interceptor/         # Auth/Retry/ServerUrl + TokenManager + AuthEventBus
│       └── SafeApiCall.kt
├── presentation/
│   ├── MainActivity.kt + MainViewModel.kt
│   ├── navigation/              # 14 条类型安全路由 + NavHost + BottomNavBar
│   ├── theme/                   # 设计令牌：Tokens/Colors/Type/Shape/Motion/AppButtons
│   ├── components/              # 共享 UI 组件：卡片/分组列表/Chip/空态/金额/列表行
│   ├── widget/                  # Glance 桌面小组件（QuickRecord/MonthlySummary）
│   └── ui/                      # 10 个模块（home/transactions/statistics/...）
├── service/                     # 后台服务（20 个：通知/同步/无障碍/小组件/...）
└── util/                        # NetworkMonitor / AppLogger / AmountUtils / 通知解析 / AI 校验
```

### 2.2 14 条类型安全路由

| 分类 | 路由 |
|---|---|
| 认证 | `ServerConfig` / `Login` / `Register` |
| 主框架（BottomBar） | `Home` / `Transactions` / `Statistics` / `Profile` |
| 独立页面 | `ManualRecord` / `TransactionDetail` / `NotificationCenter` / `Settings` / `PermissionGuide` / `CategoryManage` / `AccountManage` / `Trash` |

FAB 快速记账在 4 个主 Tab 都可见。

### 2.3 入账（RecordIntake）

「让一笔账成为本地事实」是全 App 唯一一件**到处都在发生**的事：通知自动识别、通知弹窗确认、
通知中心单条/批量确认、外部 Intent 静默入账、手工记账落离线。历史上这 6 条路径各写一份
`PendingTransactionEntity`，各排一次同步、各刷一次 widget、各发一次回执——
任何一条不变量的改动都要改 6 处，于是漏过一次（见下方「日期口径」）。

现在它们共用一个入口。**interface 与实现归属**如下：

| 归属 | 文件 | 职责 |
|---|---|---|
| interface + 意图 | `domain/record/RecordIntake.kt` | 外部只表达**意图**，不拼字段清单 |
| 实现 | `data/record/RecordIntakeImpl.kt`（`@Singleton`） | clientId 生成、时钟、entity 装配、分类预测/学习、排同步调度、widget 累加、回执/轻通知、隐私档读取 |
| 跨表写入 | `data/queue/RecordWriter.kt`（**属离线队列 module**，见 §2.5） | `RoomRecordWriter`（生产，Room 真事务）/ `DirectRecordWriter`（单测，直写） |
| 时钟 | `data/record/RecordClock.kt` | `RecordMoment` 一次取 `date`/`time`/`client_created_at`，三者同源 |
| 装配 | `di/QueueModule.kt` / `di/RecordIntakeModule.kt` | 前者把写入器绑到 `RoomRecordWriter`，后者只提供时钟与 `RecordIntake` |

**意图 = 一条历史写入路径的全部差异**（拆 subtype 而非布尔开关，
就是为了让「统一这些差异」变成一个显眼的动作）：

| 意图 | 语义 | 回执 | 预测分类 | 学习 | 排调度 |
|---|---|---|---|---|---|
| `NotificationAuto` | AI 结果完整，直接入账 | auto（带撤销/查看，读隐私档） | 否（上游已算） | 是 | 每笔 |
| `NotificationConfirm` | 通知弹窗确认 / 带备注确认 | confirm-receipt（隐私写死 false） | 是 | `learn` 传入 | 每笔 |
| `NotificationCenterConfirm` | 通知中心单条 / 批量确认 | 无 | 否 | 否 | `SyncScheduling` |
| `ExternalQuickRecord` | 外部 Intent 静默入账 | 无 | 否 | 否 | 每笔 |
| `ManualOffline` | 手工记账网络失败落离线 | 无 | 否 | 否 | 每笔 |

**顺序不变量：先写库、后排调度**（反过来可能让 SyncWorker 早于本行提交就跑到空队列）。
**排调度失败只记日志、绝不回滚、绝不抛出**——账已落库，回滚等于丢用户数据；
抛出去则会让异常逃出调用方协程（本工程无全局 `CoroutineExceptionHandler`）= 崩进程。

#### 跨表原子写入为什么要两个 adapter

一笔通知入账要写两张表（`pending_transactions` + 回填 `notification_records`），
必须原子，否则会出现「队列里有账、审计记录还停在 `parsed`」→ 用户再点一次确认就变成重复账。
生产走 `RoomRecordWriter`（`RoomDatabase.withTransaction`），
单测走 `DirectRecordWriter`——因为 **mockk 1.13.13 跑不动 `withTransaction` 的 suspend lambda**
（没有接受 suspend lambda 的自定义 answer），这正是历史实现把事务注释掉并留下 `// NOTE: P1` 的原因。
用两个 adapter 换回可测性，比继续注释「等 mockk 升级」靠谱。

⚠️ 这两个 adapter 自候选 3 起住在**离线队列 module**（`data/queue/`，见 §2.5），
因为它们是队列的入队口。三步写入的**顺序与两表关联键**（`updateStatus` 收到的
clientId 必须就是写进 `pending_transactions` 的那一个）由 `DirectRecordWriterTest` 钉死；
候选 1 刚落地时这条全仓无断言（`RecordIntakeTest` 注入的是匿名 `object : RecordWriter`），
删掉第三步测试照样全绿。

#### 日期口径（本次修的缺陷）

落库 `date` 必须是 ISO `YYYY-MM-DD`（服务端契约，见 §3.4），一律用 `java.time`。
此前 4 条路径用 `SimpleDateFormat(pattern, Locale.getDefault())` 落 `date`/`time`：
泰语 locale 下写进库的是佛历年 `2569-07-10`、阿拉伯语 locale 下是 `٢٠٢٦-٠٧-١٠`。
`RecordIntakeTest` 把 `Locale.setDefault()` 切到 `th-TH` / `ar-EG` 后断言仍是 ISO 年份，
这条不变由测试钉死（把实现改回 `SimpleDateFormat` 会立刻变红）。

#### 刻意保留的历史差异（不要"顺手统一"）

收口只搬运，不统一。以下差异都是既有事实，不是 bug：

| 差异 | 现状 |
|---|---|
| 通知中心批量确认不预测分类、不学习 | 保持 |
| 弹窗直接确认不学习，带备注确认才学习 | 保持（意图参数 `learn`） |
| 弹窗确认隐私档写死 `false`，AI 自动入账读偏好 | 保持 |
| AI 自动入账的 `source_detail` 走**云控**来源名映射，其余走**硬编码** | 保持 |
| 弹窗确认只写 `category_id`；落离线冗余写 `category_name`/`category_icon` | 保持 |
| 批量确认**循环结束后才排一次同步** | 保持（意图参数 `SyncScheduling.DEFERRED` + `RecordOutcome.syncScheduled`；每笔一次会把 1 次 WorkManager 入队变成 N 次） |
| 静默入账与手工落离线**不刷 widget** | 保持 |
| 静默入账的 `client_created_at` 是**无时区**本地串，其余路径是 `Instant`（UTC） | 保持 |
| 排调度失败只记日志、不回滚、不抛出 | 原先只有落离线这样做，现由入账 module 统一承担 |

### 2.4 预筛（NotificationPrefilter）

「这条通知值不值得当账」曾经散在三个平台 adapter 里，而且**三套口径**：NLS 跑 7 步预筛、
SMS 只跑 2 步（日报汇总与促销句式两道代码层判据全绕过）、A11Y 自带一套防抖且一条判据都不查。
更糟的是两个回归测试把判据**抄进了测试源码**（注释里明写着「与生产判据保持同步」）——
生产判据改了测试不会红。现在判据链与每源状态收在一个 module 后面：

| 归属 | 文件 | 职责 |
|---|---|---|
| interface + 词汇 | `domain/notification/NotificationPrefilter.kt` | `Capture`（adapter 的翻译产物）/ `Verdict` / `BlockReason` |
| 判据快照 | `domain/notification/NotificationRuleSource.kt` | `NotificationRuleSnapshot`、`PerPackageCriteria`、旧硬编码兜底词表 |
| 纯判据 | `NotificationTextClassifier.kt` / `MarketingCriteria.kt` | 日报汇总、促销句式；营销词 + 强交易特征豁免 |
| 实现 | `data/notification/NotificationPrefilterImpl.kt` | 判定链顺序、内容记忆（5s 去重 / 5min AI 记忆 / DB 1s）、熔断作用域、全部日志 |
| 规则数据源 | `data/notification/NotificationRuleSourceAdapters.kt` | `CloudNotificationRuleSource`（生产：云控 + ETag + 代际缓存）/ `InMemoryNotificationRuleSource`（单测：固定规则，零 IO） |
| 装配 | `di/NotificationPrefilterModule.kt` | 生产绑定指向 `CloudNotificationRuleSource` |

**判定顺序本身是行为**（不要随手调）：自身通知 → 内存内容去重(5s) → 空文本 → 系统分组摘要
→ 日报汇总 → 促销句式 → 营销词 → 账务判据 → 长窗内容记忆(5min) → AI 熔断 → DB 1s 去重。
日报/促销两道必须早于营销词：它们含「支出」会触发强交易特征豁免，靠词表拦不住。
其中**后四道状态闸只在 NLS 开启**（见本节末的「刻意保留的渠道差异」）。

**adapter 只做平台翻译**：NLS 取 extras + 清洗拼全文；SMS 拼多段报文；A11Y 保留树上的三重条件与
8s 防抖 + 金额|商家|页面 冷却（那是 `AccessibilityNodeInfo` 的平台事实），产出的摘要走同一条判据链。

**判据的数据源必须可替换**：这是「interface + 两个 adapter 换回可测性」的第二次应用（第一次是
`RecordWriter`）。测试因此能在**不碰 SharedPreferences / 网络 / assets** 的前提下直接调用生产判据，
`NotificationFilterRegressionTest` 也得以把 `scripts/rules.json` 喂进生产映射 + 生产判据——
回归护栏护的是实现，不再是拷贝。

**刻意保留的渠道差异**：**文本判据三渠道一致，四道状态闸（5s 内容去重 / 5min 内容记忆 /
AI 熔断 / DB 1s 去重）只对 NLS 生效**。这四道闸是为 NLS 的噪音（资讯 App 反复 post、系统分组
摘要单秒 10 条）发明的；而下游明写「同渠道同包名同金额 = 两笔真实交易」——对 SMS/A11Y 闸住
同源同文本的第二条就是丢真交易（两杯咖啡、分钟相同、短信逐字一样）。旧口径还原：SMS 收口前
没有任何内容级去重，A11Y 的 8s 防抖 + 金额|商家|页面 冷却是平台事实，不叠层。
取舍：误杀 > 多调一次 AI。详见 `docs/NOTIFICATION.md` 的「〇·4」。

### 2.5 离线队列（SyncQueue）

「这笔账什么时候真的到了服务端」的四个动作——**入队 / 快照 / 排调度 / 失败恢复**——曾散在四处，
每一处都只能读到队列的一小块，各自携带一份「已入队 ≠ 已上传」的告诫：

| 收口前 | 形状 | 为什么 shallow |
|---|---|---|
| `domain/repository/PendingTransactionRepository` + impl | 4 个方法一对一转发；`insert` 与 `findByClientId` **零调用** | 删掉它复杂度只是搬回 DAO |
| `TransactionRepository.syncPending()` | 一行：排调度 + 包一层 `Result.Success` | 「Success 只代表已入队」要在 KDoc 重复 4 处 |
| `service/SyncScheduler` | static object + `Context` 参数，**6 个**调用点 | 每个调用点各自 try/catch、各自理解「排上了没」 |
| `AuthRepositoryImpl` 直连 DAO | 401 恢复 + 清空队列直接打 `PendingTransactionDao` | 队列口径散在三个 module |

现在收在一个 interface 后面：

| 归属 | 文件 | 职责 |
|---|---|---|
| interface + 排调度结果类型 | `domain/queue/SyncQueue.kt` | `observeStatus` / `unsyncedCount` / `clear` / `requestSync` / `recoverTokenExpiredFailures`；`SyncRequest` = `Enqueued` / `Rejected` |
| 实现 | `data/queue/SyncQueueImpl.kt`（`@Singleton`） | 唯一 `PendingTransactionDao` 的业务调用方，唯一 `SyncScheduler` 的生产调用方 |
| 入队（跨表写入） | `data/queue/RecordWriter.kt` | `RoomRecordWriter`（生产）/ `DirectRecordWriter`（单测） |
| WorkManager 管道 | `data/queue/SyncScheduler.kt` | OneTime + `APPEND_OR_REPLACE`；只被 `SyncQueueImpl` 调 |
| 上传本体 | `service/SyncWorker.kt`（**未动**） | 逐条 POST、401 中断、撤销补偿、锁配对 |
| 装配 | `di/QueueModule.kt` | `SyncQueue` 绑定；写入器绑到 `RoomRecordWriter` |

**「已入队 ≠ 已上传」从 KDoc 变成类型**：排调度返回 `SyncRequest.Enqueued`，
它只代表 WorkManager 接受了入队请求；想写「已同步 / 已上传」的人得先解释清楚
为什么能把 `Enqueued` 当同步结果——那一刻谎报就现形了。首页手动同步的 toast
也据此从「同步完成」改成「已加入同步队列」（`HomeViewModelTest` 钉住）。
排调度失败不抛异常，而是 `Rejected(cause)`：入账侧只记日志（账已落库，回滚等于丢数据），
首页侧据此报错。

**口径一处**（`SyncQueueStatus` 的 KDoc 详述）：快照的 `pending` / `failed` 两个分区
从不相加；只有 `unsyncedCount()`（= pending + failed）用于「清空前会带走多少数据」的警告。
收口前 `PendingTransactionDao.getPendingCount()`（pending-only，生产零调用）已删除，
pending-only 的唯一读法现在是快照的 pending 列。

**入队为什么不在 interface 上**：入队要写 `pending_transactions` 并联动 `notification_records`，
签名里必然出现 Room entity——那正是收口前 `PendingTransactionRepository.insert(entity)`
的老毛病（domain 接口收发 entity 就只能是一层转发）。因此它是本 module 的 data 层
`RecordWriter`，只由入账 module 使用，对外接口不带 entity。

### 2.6 本地数据归属（LocalDataOwnership）

「本地那些表归谁、什么时候该扔」曾经写了两遍：`AuthRepositoryImpl.clearLocalCache()`
（登录路径，含 cancel 在飞 SyncWorker + 等 SyncLock）与 `ServerConfigViewModel.onSave(true)`
（换服务器，**没有**握手）。同一个 4 张表的清单、同一份归属判据，分属两个 module，
两边 KDoc 互相声明「改一处必须改另一处」。现在归一个 module：

| 归属 | 文件 | 职责 |
|---|---|---|
| interface + 意图 + 归属键 | `domain/ownership/LocalDataOwnership.kt` | `currentOwnership` / `dispose` / `awaitSyncQuiescence`；`DisposalIntent`（三种意图）/ `DisposalOutcome`（实际结果）/ `LocalOwnership`（server × account） |
| 实现 | `data/ownership/LocalDataOwnershipImpl.kt`（`@Singleton`） | **4 张表的唯一清单**、401 复活 + 排同步、SyncLock 握手 |
| 装配 | `di/OwnershipModule.kt` | `LocalDataOwnership` 绑定 |
| 队列那一张表 | 仍归 `SyncQueue`（见 §2.5） | 清空走 `SyncQueue.clear()`；本 module 只决定「该不该清」 |

**归属键写成 `LocalOwnership` 是有意的**：它显式声明归属有两个维度，而**现在只有 account
维度有判据**（`last_known_user_id`），server 维度是空的——那正是 §11.1 #3 未修的根因。
本切片只收口不修根因；后续加 server scope 时只有这一个类型需要改。

**三种意图与两个调用点**（各自只剩一句意图）：

| 调用点 | 意图 | 兑现 |
|---|---|---|
| `AuthRepositoryImpl.login` | `SameAccountRelogin`（归属相同）/ `SwitchingAccount`（换号/首次/未知 userId） | 保留 vs 清 4 张表 |
| `AuthRepositoryImpl.register` | `SwitchingAccount`（注册必是新账号） | 清 4 张表 |
| `ServerConfigViewModel.onSave` | `SwitchingServer` | 清**同样** 4 张表 |

**判定与处置刻意分成两步**：`currentOwnership()` 必须在 `TokenManager.saveSession()` **之前**问
（它会覆盖 `last_known_user_id`），`dispose()` 也必须排在 `saveSession()` 之前（否则 user B 的
session 盖在还没清掉的 user A 数据上）。重放 401 待补偿通知的判据用**处置结果**
（`Retained`）而不是调用方重算一遍归属——「数据是否留下来了」由真正做处置的一方回答。

**4 张表的清单只有这一处**，且由 `LocalDataOwnershipImplTest` 用**真实 Room 按表名**钉住
（收口前两份清单各被自己的测试钉住，「清单本身」反而没人钉）。新增第 5 张「跟着账号走」的表时
只改 `clearOwnedTables` 一处。

**刻意不改**：换服务器路径**不做** SyncLock 握手（与收口前逐字一致；`ServerConfig` 只在未登录态
可达，此时无在飞同步）；§11.1 #4 的 `unsyncedCount` init 快照竞态；§11.1 #3 的根因。

### 2.7 撤销（RecordUndo）

「刚才那笔还撤不撤得掉」的全部判据曾经住在 `AutoRecordActionReceiver` 这个 `BroadcastReceiver` 里，
而且**一行都测不到**：`Hilt_AutoRecordActionReceiver` 在 `onReceive` 与 `goAsync()` 两处都会 `inject()`，
要求 Application 是 `@HiltAndroidApp`；而本工程的 Robolectric 给不出这种 Application
（`Application::class` 抛 Hilt 前置条件，`AiBillApp::class` 抛 `KeyStoreException: AndroidKeyStore not found`，
引入 `hilt-android-testing` 不在本轮范围）。于是接收器把 `handleUndo` 提成 `internal suspend fun` 让测试直接调——
**逻辑住在平台壳里、可见性只为测试放宽**。现在判据在可注入的 module 里：

| 归属 | 文件 | 职责 |
|---|---|---|
| interface + 结果 | `domain/undo/RecordUndo.kt` | `undo(clientId)`；`UndoOutcome`（`Undone` / `LocalMissing` / `ServerStillHasIt` / `ServerStateUnknown`）与真值表 KDoc |
| 实现 | `data/undo/RecordUndoImpl.kt`（`@Singleton`） | 读 `sync_status` → 决定删本地 / 删服务端 / 两侧都不删 → 日志（日志 tag 沿用 `AutoRecordActionReceiver`，排障检索姿势不变） |
| 装配 | `di/UndoModule.kt` | `RecordUndo` 绑定 |
| 调用点 | `service/AutoRecordActionReceiver.kt` | 只剩平台管道：取 extras → 撤通知 → `goAsync()` → 播 `UndoOutcome.userMessage` |

**为什么文案跟着判据走**：文案本身是一条不变量（不得指引用户无法执行的动作、不得主张不确定的
上传状态，与 Home 横幅同一约束）。文案留在 receiver 里 = 「判据被测了、文案靠 review」，
那正是收口前的状态。因此文案是 `UndoOutcome` 的一等成员，由 `RecordUndoImplTest` 用
**字面量双写 + 禁词断言**钉死；且该用例现在**不再需要 Robolectric**（判据类不碰 Context）。

**fail-closed 是判据本身**：未知 `syncStatus` 与 `failed` 非 401 一律两侧都不删——
`!= "synced"` 这种判据会把将来新增/拼错的状态当成「未上传」而删本地，静默退化成 fail-open。
401 是唯一能证明「服务端明确拒绝、未落库」的 reason（`SyncWorker.ERROR_TOKEN_EXPIRED`）。

**刻意不改**：`pending` 与在途 `SyncWorker` 的 TOCTOU 竞态（由 worker 的补偿分支处理）、
服务端删除失败后的重试队列（§11.1 #2）。

---

## 三、API 契约

## 三、API 契约

> **后端对接的唯一权威**。开发者可直接据此编写 Retrofit 接口。

### 3.1 基础协议

| 项 | 值 |
|---|---|
| Base URL | `http(s)://<server-ip>:3000/api` |
| 认证方式 | `Authorization: Bearer <jwt>` |
| Content-Type | `application/json` |
| 响应格式 | `{ code: number, data: any, message: string }` |
| 成功 code | `0` |
| Token 有效期 | 30 天（无 refresh token，过期强制重登） |
| JSON 命名 | snake_case（客户端 `@Json(name=)` 映射） |
| 分页协议 | `page` + `page_size`，响应含 `total/page/page_size` |

### 3.2 接口清单

```
# 认证
POST   /api/auth/register          { username, password, invite_code, nickname? }
POST   /api/auth/login             { username, password }
GET    /api/auth/me
PUT    /api/auth/password          { old_password, new_password }

# 交易（核心，含幂等）
POST   /api/transactions
  Request:  { items: [{ client_id, client_type="app_android", source,
              source_detail?, type, amount(分), category_id?,
              account_id?, target_account_id?, description?,
              date, time?, tags?, client_created_at?, ai_raw_input? }] }
  Response: { created: [...], duplicates: [...] }
  幂等：同一 user_id + client_id 重复提交返回 duplicates
GET    /api/transactions?page=&page_size=&start_date=&end_date=&type=&category_id=&account_id=&keyword=
GET    /api/transactions/:id
PUT    /api/transactions/:id
DELETE /api/transactions/:id              # 软删除

# AI
POST   /api/ai/parse    { input }         # 自然语言解析
POST   /api/ai/chat     { message, session_id? }   # 当前无 UI 消费，API 保留

# 基础数据
GET    /api/categories
GET    /api/accounts

# 统计
GET    /api/stats/summary?year=&month=
GET    /api/stats/by-category?year=&month=&type=expense
GET    /api/stats/trend?year=&month=&period=daily&type=expense

# 预算（当前无 UI 消费，API 保留）
GET    /api/budgets?year=&month=
POST   /api/budgets
PUT    /api/budgets/:id
DELETE /api/budgets/:id

# 设置
GET    /api/settings
PUT    /api/settings       { default_account_id, theme, ai_model }

# 配置云控（ETag/304 缓存）
GET    /api/config/notification-rules

# 自更新
GET    https://api.github.com/repos/<owner>/<repo>/releases/latest   # GithubReleaseApi
```

### 3.3 错误码

| code | 含义 | App 处理 |
|---|---|---|
| 0 | 成功 | 正常流程 |
| 401 | Token 无效/过期 | 清 Token + 跳登录页 |
| 403 | 无权限 | Toast 提示 |
| 422 | 参数校验失败 | 显示 message |
| 5001 | AI 解析失败 | 提示切换手动记账 |
| 5002 | AI 服务不可用 | 提示稍后重试 |
| 500x | 服务端错误 | 通用错误 + 重试 |
| -1 | 网络异常（本地） | "网络连接失败" |
| -2 | 未知错误（本地） | "发生未知错误，请重试" |

### 3.4 关键业务规则

| 规则 | 说明 |
|---|---|
| 金额单位 | 所有金额以**分**为单位整数，¥32.50 = 3250 |
| 幂等 | 同一 user_id + client_id 不重复入库（UUID v4） |
| client_type | App 端必须传 `app_android` |
| transfer | category_id 为 null，不计入收支统计 |
| 时区 | 日期为客户端本地日期字符串 |
| 批量上限 | items 单次 ≤ 50 条 |

---

## 四、关键技术决策

| # | 决策 | 方案 | 原因 |
|---|---|---|---|
| 1 | 金额存储 | 整数（分） | 避免浮点精度问题 |
| 2 | 序列化 | Moshi + codegen | 编译期生成适配器，非反射 |
| 3 | JSON 映射 | `@Json(name="snake_case")` | 后端 snake → 客户端 camel |
| 4 | 模型分层 | DTO ↔ Domain ↔ Entity + Mapper | 各层职责清晰 |
| 5 | 数据隔离 | 所有 API 自动附 JWT | 服务端强制 user_id 过滤 |
| 6 | 幂等保障 | UUID v4 作 client_id | 防重复提交/重试 |
| 7 | Token 存储 | EncryptedSharedPreferences | AES256_GCM 加密 |
| 8 | 无 Refresh Token | 过期强制重登 | 自部署场景 30 天够用，简化 |
| 9 | 401 处理 | OkHttp Interceptor + AuthEventBus | UI 层统一跳转登录 |
| 10 | 图标方案 | Material Icons 名称映射 | 服务端返回 icon 字符串 |
| 11 | 分页策略 | Paging 3 + page/page_size | 与 LazyColumn 集成 |
| 12 | Room Migration | AutoMigration + schema export | 简单变更自动，复杂手写 |
| 13 | 网络状态 | NetworkCallback（`NetworkMonitor`） | 网络「离线→在线」跳变时排 SyncWorker |
| 14 | 状态恢复 | SavedStateHandle | 进程死亡恢复筛选/滚动 |
| 15 | 一次性事件 | Channel → receiveAsFlow | 不被 Compose 重组重复消费 |
| 16 | 图表库 | 原生 Canvas 手绘 | 避免引入 Vico 等重型图表库（见 StatsTrendChart.kt）；APK 体积敏感 |
| 17 | 日期处理 | java.time | API 26+，无额外依赖 |
| 18 | 构建类型 | debug / release（R8 + ProGuard） | Release 体积优化 |
| 19 | ProGuard | 保留 DTO/Entity/Retrofit 接口 | Moshi codegen 需保留 @Json |
| 20 | 最低 API | 26（Android 8.0） | 覆盖 95%+ 设备 |
| 21 | 目标 API | 35（Android 15） | 适配最新系统 |
| 22 | 通知权限 | POST_NOTIFICATIONS（API 33+） | Android 13+ 运行时申请 |
| 23 | 模块化 | 初期单 app 模块 | 编译 > 60s 时启动拆分 |
| 24 | 通知监听架构 | L1-L4 分层（v3） | 详见 [NOTIFICATION.md](./NOTIFICATION.md) |
| 25 | 通知权限引导 | 可检测项 ✓/✗ + 按钮 | 不可检测项常驻按钮 |

---

## 五、网络层

### 5.1 拦截器链

```
请求 → ServerUrlInterceptor → AuthInterceptor → RetryInterceptor → LoggingInterceptor → Server
                                                                                            ↓
响应 ←            ←                ←                ←                 ←                  ←
```

| 拦截器 | 职责 |
|---|---|
| ServerUrlInterceptor | 从 DataStore 读用户配置地址，动态改 BaseUrl |
| AuthInterceptor | 自动附加 JWT；401 时清 Token + 触发 AuthEventBus |
| RetryInterceptor | 网络错误指数退避，最多 3 次（仅 IOException） |
| LoggingInterceptor | Debug 构建输出请求/响应日志 |

### 5.2 统一响应处理（SafeApiCall）

```kotlin
suspend fun <T : Any> safeApiCall(apiCall: suspend () -> ApiResponse<T>): Result<T> = try {
    val response = apiCall()
    when (response.code) {
        0 -> Result.Success(response.data ?: safeUnitCast())
        else -> Result.Error(response.code, response.message)
    }
} catch (e: IOException) {
    Result.Error(-1, "网络连接失败")
} catch (e: HttpException) {
    Result.Error(e.code(), "服务器错误: ${e.code()}")
} catch (e: Exception) {
    Result.Error(-2, "未知错误: ${e.message}")
}
```

### 5.3 Token 管理 + 401 全局处理

```kotlin
@Singleton
class TokenManager @Inject constructor(@ApplicationContext context: Context) {
    // EncryptedSharedPreferences + AES256_GCM + AES256_SIV
    fun getToken(): String? = ...
    fun saveToken(token: String) = prefs.edit().putString("jwt_token", token).apply()
    fun clearToken() = prefs.edit().remove("jwt_token").apply()
}

// 全局认证事件总线
@Singleton
class AuthEventBus @Inject constructor() {
    private val _events = MutableSharedFlow<AuthEvent>(extraBufferCapacity = 1)
    fun emit(event: AuthEvent) { _events.tryEmit(event) }
}

sealed class AuthEvent {
    data object TokenExpired : AuthEvent  // UI 收到后 navigate "login_force"
}
```

401 处理：AuthInterceptor 清 Token + emit TokenExpired → MainActivity 订阅 → navigate Login（清栈）。

### 5.4 超时与重试配置

- AI 接口超时 30s，其他接口 15s
- 连接超时 10s，读超时 15s
- RetryInterceptor：仅重试 IOException，指数退避最多 3 次

---

## 六、离线同步

### 6.1 同步流程

```
用户记账 → 有网络？ 
            ├─ YES → 直接调 API 入库 + 写本地缓存 (syncStatus = "synced")
            └─ NO  → 存 Room (syncStatus = "pending", 生成 client_id)
                              ↓
                    ★ 落库那一刻立刻排一次 SyncWorker（见下「触发时机」）
                              ↓
                    逐条 POST /api/transactions (幂等)
                              ↓
                    成功 → "synced" / 401 → 中断等重登（剩余保持 pending）/ 其他失败 → "failed"
```

> **触发时机不止一条**（完整清单见 [§6.3](#63-workmanager-调度)）。其中「落库即排」是主路径：
> 记账请求因网络失败而落离线时，本进程的网络**并未发生跳变**，此时若只靠
> `NetworkMonitor` 的跳变监听，这笔账会一直躺在队列里，直到用户手动点同步或进程重启
> ——而 README 与 Home 待同步横幅都在承诺「联网后会自动补传」。
> 其余触发点（网络跳变、启动、401 后同账号重登）是**兜底**，用于覆盖进程被杀期间的积压。
> 完整取舍见 `createTransactionOffline` 与 `RecordIntakeImpl` 的 KDoc，入账侧的排调度收口见 [§2.3](#23-入账recordintake)。

### 6.2 离线队列（SyncQueue）与 SyncWorker 要点

队列的**读 / 排 / 恢复**归 [§2.5](#25-离线队列syncqueue) 的 `SyncQueue`；
`SyncWorker` 是它内部的上传实现，只管把 `pending` 行逐条 POST 出去：

- `@HiltWorker` + `@AssistedInject`
- 逐条同步而非批量，避免部分失败导致已成功的被重试
- `MAX_RETRY_COUNT = 5`，超过标记 `failed` 不再处理
- 401 → **中断本轮遍历**（`break`，剩余 pending 保持待同步不再发请求）+ 当前条标
  `failed`（`last_error = "Token expired, need re-login"`）+ `Result.failure()`，等待用户重新登录
- 网络异常 → 增加 `retryCount`，让 WorkManager 自动重试
- 异常返回 `Result.retry()` 由 WorkManager 指数退避（10s 起）

> 401 必须**立即中断**而非只置标志位继续遍历：`AuthInterceptor` 在第一个 401 就清了
> session，继续发只会把剩余 N−1 条 pending 无差别标成 failed，并对同一原因重复发 N 次请求。

**失败写入必须原子**：标 `failed` 一律走 `PendingTransactionDao.markFailed`（单条 UPDATE 同时写
`sync_status` / `last_error` / `updated_at`），**不得**用 `updateSyncStatus`（不写 `last_error`）。
历史上 `SyncWorker.markFailed` 误用了后者，使 `resetFailedMatching` 恒返回 0、401 恢复成为死代码。

**`failed` 是终态**：`getAllPending()` 只捞 `sync_status = 'pending'`，所以 `failed` 记录
不会被再次同步。因此「401」这种**可恢复**失败必须在用户重新登录时显式重置，
否则离线记账会静默卡死在本地（详见 §6.4）。

> **⚠️ 已知缺口：`failed` 行当前没有任何逐行 UI 处置入口。**
> 不可逐行查看（流水页 `TransactionsViewModel.getTransactions()` 的数据源是**服务端**，
> presentation 层没有展示本地 `pending_transactions` 明细的 UI）、不可逐行删除
> （`deleteByClientId` 只被 SyncWorker 补偿与通知撤销调用）、不可重试
> （`getAllPending()` 不捞它）。仅有的两条路经都是一次性清空全部数据：
> 切换服务器的 `DisposalIntent.SwitchingServer` 与
> 换号登录的 `DisposalIntent.SwitchingAccount`（两条都由本地数据归属 module 兑现，见 §2.6）；
> 此外只有 401 类失败
> 能靠同账号重新登录复位（`resetFailedMatching`）。
>
> 因此 Home 待同步横幅对 `failed` 只做**状态陈述、不给动作指引**
> （`SyncQueueStatus` → `bannerVariantOf`，Failed 档无按钮）——
> 指引一个用户做不到的动作就是新的谎报。处置能力待后续
> 「failed 明细页（逐行重试/删除）」切片解锁，届时该档才应拿到按钮并升级为 danger 警告色。

> **⚠️ 已知缺口：`synced` 行只增不减，存储无上限。**
> `PendingTransactionDao.cleanSyncedBefore(before)` 已在 DAO 定义，但**零调用点**
> ——从没有接线。每笔同步成功的记录永久留在 `pending_transactions` 表里。
> 该方法原先也被视为「撤销三态修好之后再接线」的候选项，因为过早启用可能激活历史
> `keyword` 兜底路径（见 `RecordUndo` KDoc 提及）；该前提现已满足
> （撤销三态与 R-Y/R-Z 补偿均已在 `804a23d` 修好），但**本次未接线**——
> 仅陈述该存储事实，接线时需确认清理窗口（多久前的 `synced` 可删）与
> 不误删在途补偿场景。类似地 `NotificationRecordDao.cleanIgnoredBefore` /
> `cleanAuthPendingBefore` 亦为定义未调用。

### 6.3 WorkManager 调度

| Worker | 类型 | 调度 | 触发时机 |
|---|---|---|---|
| SyncWorker | **OneTime** | NetworkType.CONNECTED + APPEND_OR_REPLACE | 触发点仍是这 5 处，只是**排调度入口统一为 `SyncQueue.requestSync()`**（收口前这 5 处各自直调 `SyncScheduler`）：**① 落库时即刻排**（`RecordIntake`：手工落离线 / 外部 Intent 静默入账 / 通知自动入账 / 弹窗确认 / 通知中心单条确认写完 pending 后；通知中心批量确认按 `RecordOutcome.syncScheduled` 在循环结束排一次）；② `NetworkMonitor` 监听「离线→在线」跳变；③ App 启动（`AiBillApp` 注入 `NetworkMonitor` → 注册回调时立即回调一次）；④ 用户手动点同步（`HomeViewModel.triggerSync`）；⑤ 同账号重新登录后（`onSameAccountRelogin` 经 `recoverTokenExpiredFailures` 重置 401 记录并主动排） |
| RulesSyncWorker | Periodic | NetworkType.CONNECTED | 拉取云端规则（ETag/304） |
| UpdateCheckWorker | Periodic | NetworkType.CONNECTED | GitHub Release 检查 |
| NlsHealthCheckWorker | Periodic | 通知监听断连恢复 |
| A11yHealthCheckWorker | Periodic | 无障碍服务健康检查 |

> **通知中心批量确认的排调度：一批最多排 1 次，且不是「无条件排」。**
> 收口前 `confirmAll` 循环结束后**无条件**调一次 `scheduleBatchSync()`，
> 于是「全部被跳过（金额无效 / 记录已不存在）的那一批」也会排一次同步——
> 顺带把队列里**别的** pending 冲一遍。现在排不排由 `RecordOutcome.syncScheduled`
> 说了算：**本批入过账就排 1 次（时机与旧实现相同）；一批都没入账就不排**，
> 那一顺带行为不再发生。其余触发点不受影响。
>
> 同理，`requestSync()` 被拒（WorkManager 不可用）时该笔的 `syncScheduled` 也是 false，
> 批量路径会因此在收尾补排一次——这是刻意的（失败重试），不是巧合。

> ⚠️ **SyncWorker 是 OneTime，不是 Periodic，也没有定时兜底。**
> 离线队列完全依赖上表列出的触发点，其中最关键的是 `NetworkMonitor`
> （由 `AiBillApp` 在启动时注入以触发 NetworkCallback 注册）。
> `registerNetworkCallback` 会**立即**回调一次当前网络状态，因此「App 启动时已联网」
> 也能把积压的 pending 队列排空。
> 若 `NetworkMonitor` 未被注入，离线记账将**永远不会**自动同步。

**Application 启动调度幂等**：`AiBillApp.scheduleWorkers()` 调用多次不会重复创建任务（各 Worker 用 `KEEP` 策略）。

---

### 6.4 登录 / 换号时的本地数据处置（数据安全关键路径）

**收口后（架构候选 4）**：归属判据与「清哪几张表」归本地数据归属 module（见 [§2.6](#26-本地数据归属localdataownership)）。
`login()` 只做两件事：在 `saveSession()` **之前**问一次归属（`LocalDataOwnership.currentOwnership()`
← `TokenManager.getLastKnownUserId()`），再把**意图**交给 module（`dispose`），
module 返回实际结果（`Retained` / `Cleared`）。4 张表的清单只在
`LocalDataOwnershipImpl` 一处，两个调用点各剩一句意图。

| 场景 | 判定 | 意图 | pending 队列 | categories/accounts/notifications |
|---|---|---|---|---|
| **同账号重登**（Token 过期或**显式登出后**重登，典型场景） | `oldId != null && oldId == newId` | `SameAccountRelogin` | **保留**；并把 `last_error LIKE '%Token expired%'` 的记录重置回 `pending`（`resetFailedMatching`），再主动排一次同步 | 保留（server 派生缓存，`HomeViewModel.refresh()` 会重新拉；清掉会造成登录后短暂空白） |
| **换号** | `oldId != null && oldId != newId` | `SwitchingAccount` | 清空 | 清空 |
| **首次登录 / 旧版本无 userId** | `oldId == null` | `SwitchingAccount`（保守，无法判断是否换号） | 清空 | 清空 |
| **切换服务器**（`ServerConfig` 页保存，`clearLocalCache = true`） | 用户主动保存（不走 `oldId` 判定） | `SwitchingServer` | 清空 | 清空（含 `notification_records`，与上面两类登录路径**同一份清单**，都是 4 张） |

> 表级不变量（清了哪几张表）现在由 `LocalDataOwnershipImplTest` 用**真实 Room 按表名**钉住；
> 「意图有没有选对」由 `AuthRepositoryImplTest` 钉住。两侧用例都在，语义等价。
> 收口前是两份清单、两个测试文件各钉一遍——「清单本身」反而没人钉。

> ⚠️ 历史 bug（1）：早期实现**无条件** `clearLocalCache()`。Token 30 天过期（无 refresh
> token）恰恰是离线数据最容易堆积的时刻，用户重登后所有未同步的记账被物理删除。
> 提交 `bd39a2b` 的原意是「**换号**清缓存」，条件判断漏了。
> `AuthRepositoryImplTest` 的 `DATA LOSS regression`（意图层）
> 与 `LocalDataOwnershipImplTest` 的同名用例（表层）同时锁住此不变量。

> ⚠️ 历史 bug（2，B6）：`logout()` 曾调 `clearSessionAndIdentity()` 把 `last_known_user_id`
> 一并删除。于是「登出 → 登回**同一账号**」时 `oldId == null`，退化为「首次登录」路径并
> 清空本地数据，把用户离线记的账物理删除——而 `ProfileScreen` 的登出确认弹窗向用户
> 承诺过「本地未同步的数据不会丢失」。现在 `logout()` 只调 `endSession(SIGNED_OUT)` +
> `clearUserInfo()`，**保留 `last_known_user_id`**，也**不处置本地数据**
> （`AuthRepositoryImplTest` 的 logout 用例断言 `dispose` 零调用）。
>
> 保留它**不降低**换号防护：上表第 2 行靠的是 `oldId != newId` 的**比较**，
> 不靠 `oldId != null`。`AuthRepositoryImplTest` 用 `logout then SAME/DIFFERENT account login`
> 两个用例同时锁住「同号保留」与「换号仍清空」。

> ⚠️ 同账号判定必须用 `TokenManager.getLastKnownUserId()`（经
> `LocalDataOwnership.currentOwnership()` 读），**不能**用 `getUserId()`：
> 会话结束时 `endSession()` 会把 `user_id` 一并清掉，而
> 「Token 过期 → 重登」「显式登出 → 登回」正是最需要保住离线队列的场景。
> 因此 `last_known_user_id` 刻意活得比 session 长——**任何**会话结束路径都不清除它。
> 它不是 session 的一部分，而是**本地缓存的归属判据**。

> ⚠️ `SyncWorker` 在**无 token 时直接 `Result.success()` 短路**，不发请求、不碰队列。
> `NetworkMonitor` 会在 App 启动时排同步（这是预期行为），若不加此守卫，
> 未登录状态下每条 pending 都会拿到 401 被误标 `failed`，
> 而它们本来可以在用户登录后继续上传。

## 七、数据模型

### 7.1 本地数据库（Room v7，9 张表）

| 表 | 说明 | 关键字段 |
|---|---|---|
| `pending_transactions` | 待同步交易（离线队列） | clientId (PK UUID), amount, syncStatus (pending/synced/failed), retryCount |
| `categories` | 分类缓存 | id (PK), name, type, icon, sortOrder |
| `accounts` | 账户缓存 | id (PK), name, type, icon, currentBalance |
| `notification_records` | 通知监听记录 | id (PK auto), packageName, content, parsedAmount, status (raw/parsed/confirmed/ignored) |
| `category_rules` | 分类学习规则 | keyword (PK), categoryId, hitCount |
| `templates` | 记账模板 | id, name, amount, categoryId, accountId |
| `auto_rules` | 自动记账规则 | id, matchPattern, action |
| `recurring_rules` | 周期记账 | id, frequency, amount, categoryId |
| `app_logs` | 应用日志 | id (PK auto), timestamp, level, message |

**迁移策略**：`DatabaseModule.kt` 显式列出 5→6、6→7 Migration，**禁止** `fallbackToDestructiveMigration`（保护 pending 队列不被静默清空）。

### 7.2 敏感数据：EncryptedSharedPreferences（`TokenManager`）

| Key | 类型 | 生命周期 | 说明 |
|---|---|---|---|
| `jwt_token` | String | session | 用户登录 Token |
| `user_id` | Int | session | 当前用户 ID |
| `username` / `nickname` | String | session | 用户信息 |
| `last_known_user_id` | Int | **跨 session 持久** | 本地缓存的归属判据（**换号判定依据**，见 §6.4）。任何会话结束路径都不清除 |
| `session_end_reason` | String | 跨 session | 上次会话结束原因：`token_expired` / `signed_out`；`saveSession()` 成功后清除 |

`saveSession()` 在**同一个 `commit()`** 里原子写入 token + userId + username + nickname +
`last_known_user_id`，并**清除** `session_end_reason`（用 `apply()` 会出现
「token 已写入但 userInfo 还在 DataStore」的不一致窗口，进程被杀即丢 token）。

### 7.2.1 会话结束：`endSession(reason)` 是唯一入口

`endSession()` 清 token + userId + username + nickname，写入结束原因，
**保留 `last_known_user_id`**。它是本地缓存归属判据的**唯一**写入者
（`saveSession()` 写、`endSession()` 保留），本地数据归属 module 对它**只读**
（`LocalDataOwnershipImplTest` 断言任何处置都不调 `saveSession` / `endSession`）。

| 调用方 | reason | 冷启动提示 |
|---|---|---|
| `AuthInterceptor` 收到**带凭证**的 401 | `TOKEN_EXPIRED` | 「登录已过期，请重新登录」 |
| `AuthRepositoryImpl.logout()`（显式登出） | `SIGNED_OUT` | **不提示**（预期内） |
| `AuthInterceptor` 收到**未带凭证**的 401（登录密码错误等） | `SIGNED_OUT` | **不提示**（预期内） |

> ⚠️ 不可用 `lastKnownUserId != null && !hasToken()` 猜测：登出保留 `last_known_user_id` 后
> （B6 修复的前提），主动登出也会命中该条件，从而把「我自己退出的」误报成「登录已过期」。
> 故必须用显式、持久化的 `session_end_reason`。

> ⚠️ `AuthInterceptor` 必须按「本次请求是否携带凭证」区分 reason：登录接口密码错误也会返回
> 401，若一律标 `TOKEN_EXPIRED`，用户输错一次密码后下次冷启动就会看到
> 「登录已过期」这种风马牛不相及的提示。

### 7.3 DataStore 存储项（非敏感，`UserPreferences`）

| Key | 类型 | 说明 |
|---|---|---|
| `server_url` | String | 服务端地址 |
| `default_account_id` | Int | 默认记账账户 |
| `theme_mode` | String | light / dark / system |
| `notification_enabled` | Boolean | 通知监听开关 |
| `app_lock_enabled` | Boolean | 应用锁开关 |
| `hide_from_recents` | Boolean | 从最近任务隐藏 |
| `last_sync_time` | Long | 最后全量同步时间 |

> 迁移期兼容：DataStore 里仍保留 `user_id` / `username` / `nickname` 的旧值作为**只读
> fallback**（不删，避免老用户首次启动读到 null）；**所有新写入一律走 `TokenManager`**。

**UserPreferences.kt** 使用 `stateIn(Eagerly)` + `AtomicReference` 暴露热流，避开 Interceptor 同步路径 runBlocking。

> ⚠️ 登出只调 `clearUserInfo()`，**只删 `user_id` / `username` / `nickname` 三项**。
> 禁止改回 `dataStore.edit { it.clear() }`：那会一并抹掉 `server_url`，
> 导致 `ServerUrlInterceptor` 读不到地址 → 请求回落到占位地址 `http://localhost:3000/api/`
> → **登出后无法再登录**，用户必须重新配置服务器。设备级设置（主题 / 通知隐私 / 应用锁 /
> AI 开关 / 默认账户）不属于用户身份，退登后应继续生效。
> `UserPreferencesClearUserInfoTest`（真实文件型 DataStore）钉住此不变量。

---

## 八、性能规范

冷启动 < 2s 首屏可交互（`Application.onCreate` 不做 I/O）；AI 超时 30s / 其他 15s；列表用 `LazyColumn` + `key()` + `contentType`；DB 索引 `sync_status` / `received_at`；分类/账户本地缓存。

---

## 九、安全规范

| 项目 | 规则 |
|---|---|
| Token 存储 | EncryptedSharedPreferences（AES256_GCM + AES256_SIV） |
| 网络传输 | 支持 HTTPS，NetworkSecurityConfig 配置自签证书 |
| 日志输出 | Release 禁止 debug/verbose 日志 |
| 代码混淆 | Release 开启 R8，保留 DTO/Entity/Retrofit 接口 |
| 敏感信息 | 禁止硬编码 URL/Key，使用 BuildConfig 或运行时配置 |
| 数据库 | `allowBackup=false` |
| 权限 | 按需申请，用户拒绝后功能降级不崩溃 |
| 导出 Receiver | `ACTION_QUICK_RECORD` / `ACTION_AI_PARSE` 加 signature permission |
| 下载完成 | `DownloadCompleteReceiver` 校验 `getCallingPackage()` |

---

## 十、错误处理

| 层级 | 错误类型 | 处理方式 |
|---|---|---|
| Network | IOException / TimeoutException | "网络连接失败"，重试按钮 |
| HTTP | 4xx / 5xx | 按错误码分类处理（4xx = 跳登录 / 5xx = 重试） |
| Business | code ≠ 0 | 展示服务端 message |
| Local | Room / DataStore 异常 | 降级处理，记录日志 |

### 10.2 AI 调用降级

```
用户输入 → 本地规则匹配（CategoryLearningEngine）
            ↓ 命中 → 直接返回
            ↓ 未命中
        POST /api/ai/parse
            ↓ 成功 → 入库 + 自动学习
            ↓ 失败 → 提示"AI 暂不可用，请手动记账"
```

### 10.3 UI 错误展示

成功 = Toast / 数据刷新 / 页面跳转；失败 = Snackbar + 错误信息 + 重试按钮；破坏性操作（删除/退出）= 确认弹窗或撤销机制；加载中 = `LoadingState`；空列表 = `EmptyState`（矢量图标 + 邀请行动文案）。

**UI 层的实现约定详见 [UI-DESIGN.md](UI-DESIGN.md)**：设计令牌、语义色（红=支出/绿=收入，明暗自适应）、等宽数字排版、共享组件清单、卡片数量上限、动态取色为何默认关闭。

---

## 十一、已知缺口与技术债

本节集中记录**已知但未修**的问题，避免它们散落在各处 KDoc 里无人发现。
每条都给出触发条件与「为何暂不修」，新增时请一并补上代码侧交叉引用。

### 11.1 用户可见的缺口

| # | 缺口 | 触发条件 | 现状判定 | 出处 |
|---|---|---|---|---|
| 1 | `failed` 行无任何逐行 UI 处置入口（不可查看/删除/重试） | 队列里存在 `failed` | 横幅只作状态陈述、不指引；待「failed 明细页」切片 | §6.2 |
| 2 | 补偿删除失败无重试队列 | 撤销时服务端删除失败 | 仅 error 日志对账，可能留服务端孤儿账 | `RecordUndoImpl`（§2.7） |
| 3 | 跨服务器 `userId` 命名空间碰撞 | 登出态换服务器 + 两端 userId 相同（自建部署常见） | **收口已做**（候选 4：4 张表的清单、归属判据、SyncLock 握手、`last_known_user_id` 存活期已归 `LocalDataOwnership`，并把归属键显式写成 `LocalOwnership`（server × account））；**根因待后续切片**——server 维度现在**故意为空**，换服务器仍被当作「整表作废」而不是「换命名空间」 | §2.6 / §6.4 |
| 4 | `ServerConfigUiState.unsyncedCount` 是 `init` 快照 | 进入页面时队列为空、期间后台落 pending、用户点保存 | 有竞态窗口可致「无警告即清空」；按钮需 `isConnected` 才可点，窗口极窄，定为低优先 | `ServerConfigViewModel` |

### 11.2 认知陷阱（不改会误导后来者）

- ~~`PendingTransactionDao.getPendingCount()` 生产零调用、最容易被误用~~ —— 架构候选 3 已**删除**该方法（它只数 `pending`，却与 `getAnyUnsyncedCount()` 只差一个字母）。现在 pending-only 的唯一读法是 `observeSyncStatus()` 的 pending 列（横幅），`pending + failed` 的唯一读法是 `SyncQueue.unsyncedCount()`（清空前的警告）。**新增计数前先想清楚要回答哪个问题**，这两个之外再加第三种就是新 bug。
- `SyncWorker` 尾部注释「检查剩余 pending（含 failed 状态）数……则 retry」易被误读成 `failed` 会被重试。实际 `getAllPending()` 只捞 `pending`，且空队列时 `doWork` 在计算该计数**之前**就早退 `Result.success()`。
- `SyncQueue.requestSync()` 返回的 `SyncRequest.Enqueued` **只代表已入队**，不代表已上传（`TransactionRepository.syncPending()` 的 `Result.Success` 已随该方法删除）。不得据此写「已同步/已上传」——首页手动同步的 toast 因此是「已加入同步队列」，`HomeViewModelTest` 显式禁掉了「已同步 / 已上传 / 同步完成」三个词。
- `RecordOutcome.syncScheduled` 回答的是「**本笔**排了没排」，不是「这批排了没排」。`NotificationCenterViewModel.confirmAll` 必须按它决定要不要在循环后补排一次；漏看 = 这一批通知确认的账永远不自动上传（兜底只剩网络跳变与启动）。
- 「本地那几张表归谁、什么时候该扔」只有一个 module 管：`LocalDataOwnership`（§2.6）。看到任何 `deleteAll()` 连着一串 DAO 调用出现在认证或设置代码里，那不是「顺手再清一张表」，而是把清单又抄了一份——收口前正是这样漏过一次（新增第 5 张表，两处只改了一处）。同理，`last_known_user_id` 只由 `TokenManager` 写，归属 module 只读。

### 11.3 测试基建的技术债

- **`testReleaseUnitTest` 有 9 个既有失败**（在收口队列 module 之前的 tip `c35ce3e` 与之后各跑一次，数字一致）：
  `TransactionsPagingAppendTest`(2) + `PendingSyncBannerRenderTest`(7)，全部抛
  `RuntimeException: Unable to resolve activity for Intent { … androidx.activity.ComponentActivity }`，
  **发生在用例体之前**（`ActivityScenario.launch` 起不来）。**根因已查明**：
  `app/build.gradle.kts:173` 的 `debugImplementation(libs.compose.ui.test.manifest)`。
  `androidx.compose.ui:ui-test-manifest` 会贡献一条清单声明
  `<activity android:name="androidx.activity.ComponentActivity" android:exported="true" />`，
  但它只进 **debug 变体**的合并清单；AGP 给单测的配置指向的是**被测变体**的
  `packaged_manifests/<variant>/…/AndroidManifest.xml`，于是：
  - debug 合并清单：有该 activity 声明（1 处）→ 用 `createComposeRule()` 的测试能起宿主；
  - release 合并清单：0 处 → 同样这些测试在 release 变体下 Robolectric 解析不到宿主，
    在用例体之前就炸。
  全仓用 Compose 测试规则的只有上面那两个套件，正好就是失败的 9 个用例；`:168` 的
  `testImplementation(libs.compose.ui.test.manifest)` 只把类放上**测试 classpath**
  （所以编得过、只在运行时炸），不参与清单合并。

  **为什么不修**：把这个依赖挪成 `implementation` 就等于往 **release APK** 里塞一个
  `android:exported="true"` 的空 activity（任何应用都能拉起它）。debug-only 才是正确的
  安全姿态，不是疏漏。因此 Compose UI 单测只在 debug 变体跑；看到这 9 个失败
  **不要**当成某次改动的回归——那是变体配置决定的既有事实。
  ⚠️ **门禁已同步收窄到 debug 变体**（`afbf0cd`）：Kover 0.9.0 的
  `reports { total { } }` 跨变体聚合，`koverVerify` 的任务图里**含** `:app:testReleaseUnitTest`，
  于是 CI 那一步本来必然被这 9 个打红（门禁被一个刻意不修的变体差异绑架）。
  现在规则挂在 `kover { reports { variant("debug") { } } }`，CI 跑的是
  **`koverHtmlReportDebug` + `koverVerifyDebug`**，实测 `--dry-run` 的任务图里只有
  `testDebugUnitTest`。`testReleaseUnitTest` 仍可手动跑（仍是那 9 个失败），但不作为门禁。
  ⚠️ 反过来说：**别在 CI 里写回 `koverVerify` / `koverHtmlReport`**——它们跨变体聚合，
  会把这 9 个失败重新拖进门禁；且 `minBound(9)` 只挂在 debug 上，`koverVerify` 现在是
  无规则的任务（跑它会得到一个「什么都没验」的绿）。
- **跨表写入的事务回滚未覆盖**：生产用 `RoomRecordWriter`（Room 真事务），单测用 `DirectRecordWriter`（直写），
  因为 mockk 1.13.13 跑不动 `RoomDatabase.withTransaction { }` 的 suspend lambda。
  因此「三步写在一个事务里、中途失败不留下半行」这条**没有自动化测试**（见 [§2.3](#23-入账recordintake)）。
  根治需引入 `hilt-android-testing` + Room 集成测试（Robolectric 或 androidTest），属新增依赖，需单独立项。
  （三步的**顺序与关联键**已由 `DirectRecordWriterTest` 钉死，缺的只是「原子」这一层。）
- `PendingTransactionDao.cleanSyncedBefore` 等三个「清理」方法定义未调用（存储无上限，见 §6.2）。
- `HomeViewModelTest` 用 5 个非 relaxed mock 打桩 `init` 的四路 `refresh()`；`init` 再新增任何调用都会让全部用例因 `no answer found` 变红（红因与被测行为无关）。
- ~~**Hilt 门禁挡住 receiver/service 的可测性**~~ —— **候选 5 已处理「为测试而开的后门」那一半**（架构候选 5，见 [§2.7](#27-撤销recordundo)）。门禁本身还在：`Hilt_*` 生成类在 `onReceive` / `goAsync()` 均 `inject()`，而本工程 Robolectric 给不出 `@HiltAndroidApp` 的 `Application`（`Application::class` 抛 Hilt 前置条件；`AiBillApp::class` 抛 `AndroidKeyStore not found`），且本轮**不引入** `hilt-android-testing`（需单独立项）。
  - **已撤掉的后门**（逻辑住在平台壳里、可见性只为测试放宽）：① `AutoRecordActionReceiver.handleUndo` → 判据与文案搬进 `domain/undo/RecordUndo` + `data/undo/RecordUndoImpl`，用例改名 `RecordUndoImplTest`（纯 JVM，不再需要 Robolectric 与 `Dispatchers.setMain`）；② `ExternalActionReceiver.handleAutoConfirmRecord` → 恢复 `private`，其用例并入 `RecordIntakeTest`（候选 1 之后该函数只剩「翻译成 `RecordIntent`」一步，逻辑早已在入账 module 里，后门没有存在必要）。
    **断言没有丢**：原 `ExternalActionReceiverTest` 的 3 条（写库→排调度顺序、排调度抛异常不抛不回滚、落库载荷）已 1:1 迁入 `RecordIntakeTest` 的「意图 4：外部 Intent 静默入账」一节（并补上 amount/type/description 透传与 clientId 非空断言）。
    **旧类名不保留**：保留 `ExternalActionReceiverTest`（哪怕改名成 `ExternalQuickRecordIntakeTest`）反而会误导——它现在驱动的是 `RecordIntakeImpl`，跟那个 receiver 无关；而那个 receiver 已无任何可测逻辑，留一个同名壳只会让人以为「receiver 还有覆盖」。
  - **盘点后判定为「自然分解」、刻意保留**：`SyncWorker.syncTransaction`（`doWork` 的真实调用点，且 `SyncWorker` 本身可用 assisted 构造参数直接构造测试）、`PaymentAccessibilityRecognition`（12 个生产调用点的纯函数提取）、`RecordWriter.applyRecordWrite`（两个 adapter 共用）、`NotificationRuleSourceAdapters.deriveSnapshot`。
  - **仍未测**：平台壳本身（取 extras / `goAsync()` 配对 / 撤通知 / Toast / 启动 Intent）依旧只能靠 review 把守——这正是收口的目的（可测的部分不该藏在壳里）。
- `ExternalActionReceiver` 的「权限被拒」与「autoConfirm=false」两条「不排同步」分支仍不可测：
  两条都在 `onReceive` 内，`onReceive` / `goAsync()` 会 `inject()`，必须由 Hilt 门禁才能到达，
  而本工程给不出 `@HiltAndroidApp` 的 Application（引入 `hilt-android-testing` 需单独立项）。
  候选 5 **刻意不拆**它们——它们是平台管道（Intent 解析 + 安全校验），不是「为测试而开的后门」；
  把它们拆成 handler 属于扩范围。权限校验本身逐字不动（`Binder.getCallingUid()` +
  `context.checkPermission(..., callingUid, -1)`，**不得**弱化成 `ContextCompat.checkSelfPermission`
  ——后者只检查自己进程，而本 App 自己声明了 `uses-permission`，永远 GRANTED，等于没有防护）。
  这两条靠 review 把守。
- `RecordUndoImplTest` 的约束断言（禁「上传/服务器/流水页」）未覆盖 `LocalMissing` 分支；现状文案合规，等值双写断言兜底。
- `RecordIntakeTest` 直接构造 `RecordIntakeImpl` + 匿名 `object : RecordWriter`（不 mock 入账入口），
  因此「入账时界面不崩」这点仍依赖 Hilt 图的编译期正确性，无运行时测试。
  ⚠️ 正因为它注入的是匿名 writer，`RecordWriter` 的三步写入一度**全仓无断言**（候选 1 的 KDoc 与代码不符），
  已在候选 3 补上 `DirectRecordWriterTest`。**再往 `RecordIntake` 里塞新的 writer 实现前，先想清楚它会被哪个用例驱动。**
- `NotificationCenterViewModel.confirmAll` 的「循环后收尾排一次」只有逐笔 `syncScheduled=false` 的事实，
  没有端到端用例（VM 本身因 `Settings.Secure` / channel 依赖不可直接测），与「Hilt 门禁」那条同源。
- **adapter 本身仍不可测**（同一道 Hilt 门禁）：`NotificationMonitorService` / `SmsReceiverService` /
  `PaymentAccessibilityService` 里的「平台翻译」（extras 取文本、报文拼接、节点树三重条件）无法进单测。
  预筛收口（见 [§2.4](#24-预筛notificationprefilter)）把**判据**从 adapter 挪进了可测的 module，
  剩下的纯平台代码靠 review 把守——这也正是收口的目的：可测的部分不该藏在 Service 里。
- 预筛的**云控 adapter** 只在 mock `NotificationRulesManager` 下被测过（`NotificationRuleSourceAdaptersTest`
  锁「代际变了就重派生」）；「真实云控 → SP → assets 兜底」这条链由 `NotificationRulesManagerTest` 守着，
  两段之间没有端到端用例——与 §11.3 第一条的跨表事务一样，属需要新基建才能补齐的部分。
