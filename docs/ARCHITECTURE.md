# AIBILL Android 架构文档

> 分层、模块、API 契约、技术决策、数据模型、已知缺口。**只写现状与不变量**，
> 改动历史一律看 git（`git log -- docs/`）。怎么干活见 [CONTRIBUTING.md](CONTRIBUTING.md)，
> 决策记录见 [docs/adr/](adr/)。
>
> 对应代码：`app/src/main/java/com/aibill/android/` · minSdk 26 / targetSdk 35

---

## 一、架构总览

### 1.1 分层

```
Presentation   Compose UI ← ViewModel ← UiState(StateFlow)
Domain         UseCase ← Repository interface ← 纯 Kotlin Model
Data           Remote(Retrofit) ←→ Local(Room + DataStore)
```

**domain 层禁止依赖 `android.*` / `androidx.*` / `data.*`**；Entity 不出 data 层。

### 1.2 单向数据流

```
UI Event → ViewModel → Repository → [Remote / Local]
UI ← Compose State ← StateFlow ← Flow ← Repository ← Result
```

ViewModel 约定：持续状态 `StateFlow<UiState>`；一次性事件 `Channel<UiEvent>` + `receiveAsFlow()`；
进程恢复用 `SavedStateHandle.getStateFlow()`。

### 1.3 三层模型

| 模型 | 位置 | 注解 |
|---|---|---|
| DTO | `data/remote/dto/` | `@JsonClass(generateAdapter=true)` + `@Json(name=)` |
| Domain | `domain/model/` | 纯 Kotlin |
| Entity | `data/local/entity/` | `@Entity` / `@PrimaryKey` |

转换只走 Mapper，禁止跨层直接用模型。

---

## 二、模块设计

### 2.1 目录

```
com.aibill/android/
├── di/            Hilt 装配（Network/Database/Repository/Work/CoroutineScope + 各 module）
├── domain/        model / repository(接口) / usecase / record / notification / queue / ownership / undo
├── data/          local(db,dao,entity,datastore) / remote(api,dto,interceptor) / repository(实现)
│                  record / notification / queue / ownership / undo（domain 接口的实现）
├── presentation/  navigation / theme / components / widget / ui(10 个页面模块)
├── service/       后台：通知监听、无障碍、SMS、Worker(5)、小组件、更新
└── util/          NetworkMonitor / AppLogger / AmountUtils / NotificationHelper / AiResultValidator
```

### 2.2 路由（14 条类型安全）

认证 `ServerConfig` / `Login` / `Register` · 主框架 `Home` / `Transactions` / `Statistics` / `Profile`
· 独立页 `ManualRecord` / `TransactionDetail` / `NotificationCenter` / `Settings` /
`PermissionGuide` / `CategoryManage` / `AccountManage` / `Trash`。FAB 在 4 个主 Tab 均可见。

### 2.3 五个收口 module

每个都是「interface 在 `domain/`、实现在 `data/`、外部只表达意图」。**同一判据 / 同一清单不留第二个入口。**

| module | 独占什么 | interface |
|---|---|---|
| **RecordIntake**（入账） | clientId、时钟、entity 装配、分类预测/学习、排调度、widget、回执、隐私档 | `intake(intent)`：5 个意图 subtype = 5 条入账路径的全部差异 |
| **NotificationPrefilter**（预筛） | 「这条通知值不值得当账」的全部判据 + 每源状态 | `evaluate(Capture): Verdict` |
| **SyncQueue**（离线队列） | 入队 / 快照 / 排调度 / 失败恢复 | `observeStatus` / `unsyncedCount` / `clear` / `requestSync` / `recoverTokenExpiredFailures` |
| **LocalDataOwnership**（本地归属） | 哪些表归 server × account、三种处置、SyncLock 握手 | `currentOwnership` / `dispose(intent)` / `awaitSyncQuiescence` |
| **RecordUndo**（撤销） | 「这笔还撤不撤得掉」的判据与文案 | `undo(clientId): UndoOutcome` |

**跨表写入**（`data/queue/RecordWriter.kt`）：`RoomRecordWriter`（生产，Room 真事务）/
`DirectRecordWriter`（单测）。两个 adapter 是为了换回可测性——mockk 跑不动 `withTransaction` 的
suspend lambda。**测试里跑的是 `DirectRecordWriter`，不提供原子性，禁止接到生产**。
三步顺序（建审计行 → 写队列行 → 回填 status + `linked_client_id`）与两表关联键由测试钉死。

**不变量（所有入账路径共用）**

- **先写库、后排调度**。反过来会让 SyncWorker 早于提交跑到空队列。
- 排调度失败**只记日志，绝不回滚、绝不抛出**：账已落库，回滚 = 丢数据；抛出会崩进程。
- **已入队 ≠ 已上传**：`requestSync()` 返回 `SyncRequest.Enqueued`，只是 WorkManager 接受了请求。
  任何「已同步 / 已上传」的文案都必须先解释为什么能把 `Enqueued` 当结果。
- 落库 `date` 一律 `java.time` 生成 ISO `YYYY-MM-DD`。**禁止 `SimpleDateFormat(Locale.getDefault())`**
  ——泰语 locale 写出佛历年、阿拉伯语 locale 写出非 ASCII 数字，服务端会拒收。
- `clientId` = UUID v4，是幂等与撤销的唯一凭据。

**入账路径之间的差异是历史事实，不要顺手统一**（收口只搬运）：批量确认不预测分类不学习；
弹窗直接确认不学习、带备注确认才学；弹窗确认隐私档写死 `false` 而 AI 自动入账读偏好；
AI 自动入账的 `source_detail` 走云控来源名映射、其余走硬编码；弹窗确认只写 `category_id`、
落离线冗余写 `category_name/category_icon`；批量确认循环结束后才排一次同步；
静默入账与手工落离线不刷 widget；静默入账的 `client_created_at` 是无时区本地串，其余是 UTC Instant。

### 2.4 通知入账（核心差异化能力）

```
信号源 + 平台翻译（adapter 只做这件事）
  NLS: StatusBarNotification.extras → 清洗/拼全文 → Capture
  SMS: SmsMessage[] 拼接 → Capture
  A11Y: 节点树三重条件 + 8s 防抖 + 金额|商家|页面 冷却 → Capture
        ↓
L1 预筛（三渠道同一套文本判据）
  0 自身通知 → 1 内存去重(5s)【NLS】 → 2 空文本 → 3 系统分组摘要
  → 4 日报汇总 → 5 促销句式 → 6 营销词 → 7 账务判据
  → 8 长窗内容记忆(5min)【NLS】 → 9 AI 熔断【NLS】 → 10 DB 1s 去重【NLS】
        ↓
L2 评分窗口 + 跨渠道去重（NotificationProcessor，60s 窗口）
  10s 内同金额择优；同渠道同包名同金额 = 两笔真交易
        ↓
L3 解析：CategoryLearningEngine 命中 → 直接给分类；否则 AI /api/ai/parse
  → AiResultValidator 校验 → 401 则落 auth_pending 等同号重登重放
        ↓
L4 入库策略：信息完整 → 直接入账 + 回执（带撤销）；缺信息 → 待审池 + 确认通知；空/失败 → 丢弃
```

**判定顺序本身是行为**：日报/促销必须早于营销词（它们含「支出」会触发强交易特征豁免，词表拦不住）。

**四道状态闸只对 NLS 生效**（5s 去重 / 5min 记忆 / AI 熔断 / DB 1s）：它们是为 NLS 噪音
（资讯 App 反复 post、系统分组摘要单秒 10 条）发明的；而下游明写「同渠道同包名同金额 =
两笔真交易」——对 SMS/A11Y 闸住同源同文本的第二条就是丢真交易。取舍：误杀 > 多调一次 AI。

**判据数据源可替换**：生产 `CloudNotificationRuleSource`（云控 + ETag + 代际缓存）、
单测 `InMemoryNotificationRuleSource`（固定规则，零 IO）。回归护栏直接打生产判据
（`scripts/rules.json` → 生产映射 → 生产链），不是复刻。

**核心原则**：AI 是唯一裁判，本地只排除「100% 不是账」的垃圾；正则不面对用户，
仅用于去重层提取金额；用户看到的 = AI 确认的；去重在本地，后端零改动。

**数据来源互补**：通知监听（微信/支付宝/银行）· 短信 `RECEIVE_SMS`（银行卡）·
无障碍（支付结果页，覆盖无通知场景）· 分享文本（`text/plain`，无 OCR）。

**AI 结果校验（`AiResultValidator`）**：0 < amount ≤ 10,000,000 分（**10 万元**）·
类型合法 · 分类在本地分类表 · 描述 ≤ 200 字 · **金额必须等于原文里某个带货币单位的数字**
（防「花3得29」这类促销句式幻觉出金额）。过 = 直接入账；不过 = 待审。

**分类学习**：精确匹配 → 包含匹配；AI 入库后 `learnFromCorrection(merchant, categoryId)`；
用户改分类后反向学习。稳态命中 60-70%，显著省 AI 调用。

#### 踩坑（改这块前必读）

- **金额分散在任意字段**：`EXTRA_TITLE/TEXT/BIG_TEXT/SUB_TEXT/INFO_TEXT` 都可能含金额，
  必须合并全文再解析，只取 `EXTRA_TEXT` 会解析成 0。
- **`sourceDetail` 不要存全文**：它会同步到后端，存来源名（"微信支付"），全文可能含私聊内容。
- **批量确认要跳过无金额记录**：否则生成 0 元账单同步到后端。单条确认有编辑对话框兜底，批量没有。
- **收支类型不要硬编码**：确认通知的标题/图标按 `type` 显示，收款到账是 income。
- **通知点击跳转**：`MainActivity` 必须 `launchMode="singleTop"`（否则重建 Activity 丢返回栈）；
  `navigate_to` extra 要一次性消费。
- **MIUI 测不了通知**：`adb shell cmd notification post` 不分发给 NLS，用 App 内测试按钮或真实通知。
  `MainActivity` 用 `BiometricPrompt` 必须继承 `FragmentActivity`。
- **通知文本里的营销后缀要截断**（"点击领取…"），促销句式（花N得M / 0元兑）在调 AI 前拦。

---

## 三、API 契约

> 后端对接的唯一权威。Base URL `http(s)://<ip>:3000/api`，`Authorization: Bearer <jwt>`，
> 响应 `{ code, data, message }`，成功 `code = 0`，JSON snake_case。
> Token 30 天，无 refresh token，过期重登。分页 `page` + `page_size`。

```
POST /api/auth/register|login    GET /api/auth/me    PUT /api/auth/password
POST /api/transactions           # { items: [...] }，同 client_id 幂等 → { created, duplicates }
GET  /api/transactions           # page/page_size/start_date/end_date/type/category_id/account_id/keyword/tag
GET|PUT|DELETE /api/transactions/:id     # DELETE = 软删除
POST /api/ai/parse               # { input } 自然语言解析
GET  /api/categories  /api/accounts
GET  /api/stats/summary|by-category|trend
GET|POST|PUT|DELETE /api/budgets # 当前无 UI 消费
GET|PUT /api/settings
GET  /api/config/notification-rules      # 云控，ETag/304
GET  https://api.github.com/repos/<owner>/<repo>/releases/latest
```

**错误码**：`0` 成功 · `401` 清 Token 跳登录 · `403` 提示 · `422` 显示 message ·
`5001` AI 解析失败 · `5002` AI 不可用 · `-1` 网络异常（本地） · `-2` 未知错误（本地）。

**业务规则**：金额一律整数分 · `client_id` UUID v4 幂等 · `client_type="app_android"` ·
`transfer` 的 `category_id` 为 null 且不计收支 · 日期是客户端本地日期串 · `items` 单次 ≤ 50。

---

## 四、关键技术决策

| # | 决策 | 原因 |
|---|---|---|
| 1 | 金额整数（分） | 避免浮点误差 |
| 2 | Moshi + codegen | 编译期适配器，非反射 |
| 3 | DTO/Domain/Entity 三层 + Mapper | 各层职责清晰 |
| 4 | UUID v4 作 client_id | 幂等，防重复提交 |
| 5 | Token 存 EncryptedSharedPreferences | AES256_GCM |
| 6 | 无 refresh token | 自部署 30 天够用 |
| 7 | 401 走 Interceptor + AuthEventBus | UI 统一跳登录 |
| 8 | Paging 3 + page/page_size | 与 LazyColumn 集成 |
| 9 | 原生 Canvas 手绘图表 | 不引重型图表库，APK 体积敏感 |
| 10 | Room 显式 Migration，禁 `fallbackToDestructiveMigration` | 保护 pending 队列不被静默清空 |
| 11 | 单 app 模块 | 编译 > 60s 时再拆 |
| 12 | `java.time` | API 26+，无额外依赖 |

---

## 五、网络层

**拦截器链**：`ServerUrlInterceptor`（DataStore 读地址动态改 BaseUrl）→ `AuthInterceptor`
（附 JWT；401 清 Token + `AuthEventBus.emit(TokenExpired)`）→ `RetryInterceptor`（仅 IOException，
指数退避 3 次）→ Logging（仅 debug）。

**统一响应**：`safeApiCall { }` 把 `ApiResponse<T>` 转 `Result<T>`，`code==0` 为成功，
IOException → `-1`，HttpException → 服务器错误，其它 → `-2`。

**超时**：AI 30s，其它 15s；连接 10s / 读 15s。

---

## 六、同步、会话与本地数据

### 6.1 队列语义

`pending_transactions` 是**所有通知入账的写路径**（在线也一样），行状态：

| 状态 | 含义 | 谁处理 |
|---|---|---|
| `pending` | 排队等上传，**不含 failed** | `SyncWorker` 自动捞起 |
| `failed` | 本地终态，需人介入，不会自动重试 | 仅 401 可在同号重登时复活 |
| `synced` | 已上传，留着是为了撤销时能拿 `server_transaction_id` | 不清理（见 ADR-0001） |

两个数字**从不相加**：横幅的 pending 数「排队等上传的条数」，failed 数「需要人管的条数」；
只有 `unsyncedCount()`（= pending + failed）用于「清空前会带走多少数据」的警告。

**SyncWorker 要点**：逐条 POST（避免部分失败重传已成功的）· `MAX_RETRY_COUNT=5` ·
**401 立即中断遍历**（继续发会把剩余 N-1 条无差别标 failed）· 网络异常 `incrementRetryCount` ·
标 failed 一律走 `markFailed`（同一条 UPDATE 写 status + `last_error` + 时间戳），
**不得**用 `updateSyncStatus` —— 后者不写 `last_error`，会让 401 复活的 `resetFailedMatching`
恒匹配不到，离线账永久卡死 · 无 token 时直接短路，不碰队列。

**撤销补偿**：快照与 POST 之间行可能被用户撤销删除。POST 后 `markSynced` 影响 0 行 →
反向删服务端那笔（幽灵账）；POST 前复查行已不在 → 不发。补偿失败只留日志，不写成功态。

### 6.2 排调度触发点

`requestSync()` 用 OneTime + `NetworkType.CONNECTED` + `APPEND_OR_REPLACE` 串行。
触发点：落离线时 · 网络「离线→在线」跳变（`NetworkMonitor`）· App 启动（靠注入 `NetworkMonitor`）
· 通知入账 · 通知中心确认 · 外部 Intent 静默入账 · 手动点同步 · 同账号重登后。
⚠️ **SyncWorker 是 OneTime，没有定时兜底**——`NetworkMonitor` 未注入则永远不会自动同步。

### 6.3 登录 / 换号 / 换服务器的本地数据处置（数据安全关键路径）

`LocalDataOwnership` 三种意图，**4 张表的清单只有一处**（`pending/categories/accounts/notification_records`）：

| 场景 | 意图 | 队列 | 其余三张 |
|---|---|---|---|
| 同号重登（Token 过期 / 登出后登回） | `SameAccountRelogin` | **保留**，只复活 401 失败的行 + 排同步 | 保留 |
| 换号 / 首次登录 / 旧版本无 userId | `SwitchingAccount` | 清空 | 清空 |
| 切换服务器 | `SwitchingServer` | 清空 | 清空 |

**判定必须在 `saveSession()` 之前**（它会覆盖归属判据），处置也必须在之前
（否则 user B 的 session 盖在没清掉的 user A 数据上）。换服务器路径不做 SyncLock 握手
（`ServerConfig` 只在未登录态可达）。

**归属判据是 `TokenManager.getLastKnownUserId()`，不能用 `getUserId()`**：
会话结束会清 `user_id`，而「Token 过期→重登」正是最需要保住队列的场景。
`last_known_user_id` **任何会话结束路径都不清除**——它不是 session 的一部分，
而是本地缓存的归属判据。

⚠️ 历史 bug 的两条教训（回归用例已钉住）：「换号才清缓存」曾因条件写漏变成无条件清空，
Token 过期时堆积的离线账被物理删除；`logout()` 曾把 `last_known_user_id` 一并删掉，
导致「登出→登回同号」退化成首次登录路径。**登出只结束会话，不删本地数据。**

### 6.4 会话生命周期

`saveSession()` 在**同一个 `commit()`** 里原子写 token + userId + 用户信息 + `last_known_user_id`
并清 `session_end_reason`。`endSession(reason)` 是唯一结束入口：清 token + 用户信息，
保留 `last_known_user_id`。

| 调用方 | reason | 冷启动提示 |
|---|---|---|
| 带凭证的 401 | `TOKEN_EXPIRED` | 「登录已过期」 |
| 显式登出 | `SIGNED_OUT` | 不提示 |
| 未带凭证的 401（密码错） | `SIGNED_OUT` | 不提示 |

⚠️ 不要用「有 userId 但无 token」猜原因：主动登出也命中该条件。
`AuthInterceptor` 必须按本次请求是否带凭证区分 reason，否则输错一次密码，
下次冷启动就会看到「登录已过期」。

⚠️ **登出只删 `user_id/username/nickname` 三项**，不 `clear()` 整个 DataStore ——
那会连 `server_url` 一起删掉，登出后无法再登录。

---

## 七、数据模型

### 7.1 Room（9 张表）

`pending_transactions`（队列行）· `categories` / `accounts`（服务端派生缓存）·
`notification_records`（通知审计）· `category_rules`（分类学习）· `templates` ·
`auto_rules` · `recurring_rules` · `app_logs`。
索引：`sync_status` / `received_at`。Migration 显式列出，禁 destructive fallback。

### 7.2 敏感数据（`TokenManager`）

`jwt_token` / `user_id` / `username` / `nickname`（session）· `last_known_user_id`（**跨 session**）·
`session_end_reason`（跨 session，saveSession 成功后清除）。

### 7.3 DataStore（`UserPreferences`）

`server_url` · `default_account_id` · `theme_mode` · `notification_enabled` · `app_lock_enabled` ·
`hide_from_recents` · `last_sync_time`。迁移期保留旧 `user_id/username/nickname` 作只读 fallback。
用 `stateIn(Eagerly)` + `AtomicReference` 暴露热流，避开 Interceptor 同步路径的 `runBlocking`。

---

## 八、性能 / 九、安全

冷启动 < 2s（`Application.onCreate` 不做 I/O）· 列表 `LazyColumn` + `key()` + `contentType` ·
分类/账户本地缓存。

Token 加密存储 · 支持 HTTPS（自签证书走 NetworkSecurityConfig）· Release 禁 debug 日志 ·
R8 开启（保留 DTO/Entity/Retrofit 接口）· `allowBackup=false` · 权限按需申请、拒绝则降级不崩 ·
导出 Receiver 加 signature 权限 · `DownloadCompleteReceiver` 校验 `getCallingPackage()` ·
`ExternalActionReceiver` 的静默入账校验 `Binder.getCallingUid()`，
**不得**弱化成 `checkSelfPermission`（那只查自己进程，等于没防护）。

---

## 十、错误处理

网络异常 → 「网络连接失败」+ 重试按钮 · 4xx → 跳登录 / 显示 message · 5xx → 重试 ·
本地 Room/DataStore 异常 → 降级 + 日志。UI：成功 = Toast/刷新；失败 = Snackbar + 重试；
破坏性操作 = 确认或撤销；空列表 = `EmptyState`。

**AI 降级链**：本地学习规则命中 → 不调 AI；否则 `POST /api/ai/parse`；
失败 → 提示「AI 暂不可用，请手动记账」。

---

## 十一、术语

| 术语 | 含义 |
|---|---|
| 入账（记一笔） | 让一笔收支成为本地事实：手工 / 通知自动 / 通知确认 / 外部 Intent / 小组件 |
| 队列行 | `pending_transactions` 的一行，未必已上传 |
| 排队等上传（pending） | `sync_status='pending'`，可被自动捞起，不含 failed |
| 待人管（failed） | 本地终态，需用户介入 |
| 已入队 ≠ 已上传 | 排上 `SyncWorker` 只代表 WorkManager 接受了请求 |
| 来源渠道 | 通知入账的三个平台入口：NLS / A11Y / SMS |
| 预筛 | 调 AI 之前「值不值得当账」的判定链 |
| 评分窗口 | 同金额的 AI 结果 10s 内择优，跨渠道同一笔只留最优 |
| 待审池 | AI 有结果但信息不完整，等用户确认 |
| 会话 / 上次已知用户 | token + 用户信息；`last_known_user_id` 跨 session 存活，是本地缓存归属判据 |
| 同号重登 / 换号 / 换服务器 | 三种本地数据处置意图 |
| 服务端派生缓存 | categories / accounts，服务端真相的本地副本 |

---

## 十二、已知缺口与技术债

决策记录见 [docs/adr/](adr/)。[ADR-0001](adr/0001-offline-reliability-is-not-a-goal.md)
明确「离线记账不做可靠性投入」，已据此关闭下表 #1、#4。**重提前先读那篇 ADR。**

### 12.1 用户可见

| # | 缺口 | 现状 |
|---|---|---|
| 1 | `failed` 行无逐行处置入口 | **已关闭（ADR-0001，不做）**；横幅保持「只陈述、不指引」——指引做不到的动作就是谎报 |
| 2 | 补偿删除失败无重试队列 | 仅日志对账，可能留服务端孤儿账（属撤销正确性，不受 ADR-0001 影响） |
| 3 | 跨服务器 `userId` 命名空间碰撞 | 归属已收进 `LocalOwnership`（server × account），**server 维度仍为空**，换服务器仍是「整表作废」。唯一还在丢数据的根因 |
| 4 | `unsyncedCount` 是 `init` 快照 | **已关闭（ADR-0001，不做）** |

### 12.2 认知陷阱

- `PendingTransactionDao.getPendingCount()` 已删除。pending-only 的唯一读法是快照的 pending 列。
- `syncPending()` 类方法返回的「成功」只代表已入队，不代表已上传。
- `SyncWorker` 尾部注释提到的「剩余未同步」计数，`failed` 不会被重试。

### 12.3 测试基建

- **`testReleaseUnitTest` 有 9 个既有失败**（`PendingSyncBannerRenderTest` 7 +
  `TransactionsPagingAppendTest` 2）。根因：`compose-ui-test-manifest` 只作 `debugImplementation`，
  其清单贡献的 `ComponentActivity`（`exported="true"`）只进 debug 合并清单，release 清单里 0 处，
  `createComposeRule()` 在 release 变体下 Robolectric 起不来。**不修**：挪成 `implementation`
  等于往 release APK 塞一个谁都能拉起的空 activity。CI 只跑 `testDebugUnitTest`，
  覆盖率门禁用 `koverVerifyDebug`（聚合版 `koverVerify` 会把这 9 个拖进来）。
- **跨表事务回滚未覆盖**：测试走 `DirectRecordWriter`，只钉顺序与关联键；回滚要 Room 集成测试。
- **平台壳不可测**：receiver / service 的 `onReceive` 里 `Hilt_*` 会 `inject()`，
  而 Robolectric 给不出 `@HiltAndroidApp` 的 Application。根治要引入 `hilt-android-testing`
  （需单独立项）。因此 `ExternalActionReceiver` 的「权限被拒 / autoConfirm=false」两条分支靠 review 把守。
