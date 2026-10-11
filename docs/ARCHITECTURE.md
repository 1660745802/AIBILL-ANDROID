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
├── di/                          # Hilt 模块（5 个：Network/Database/Repository/Work/CoroutineScope）
├── domain/
│   ├── model/                   # Transaction / User / Result / CategoryAndAccount
│   ├── repository/              # 10 个接口（Auth/Transaction/Category/Account/Ai/BudgetAndStats/...）
│   └── usecase/                 # CategoryLearningEngine / StreakTracker
├── data/
│   ├── local/
│   │   ├── db/AppDatabase.kt    # Room v7，9 张表
│   │   ├── entity/              # 9 个 Entity
│   │   ├── dao/                 # 9 个 DAO
│   │   └── datastore/           # UserPreferences + SyncLock
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

---

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
                    NetworkMonitor 监听到「离线→在线」→ 排入 SyncWorker
                              ↓
                    逐条 POST /api/transactions (幂等)
                              ↓
                    成功 → "synced" / 401 → 中断等重登（剩余保持 pending）/ 其他失败 → "failed"
```

### 6.2 SyncWorker 要点

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
> 切换服务器的 `ServerConfigViewModel.onSave(clearLocalCache = true)` 与
> 换号登录的 `AuthRepositoryImpl.clearLocalCache()`；此外只有 401 类失败
> 能靠同账号重新登录复位（`resetFailedMatching`）。
>
> 因此 Home 待同步横幅对 `failed` 只做**状态陈述、不给动作指引**
> （`SyncQueueStatus` → `bannerVariantOf`，Failed 档无按钮）——
> 指引一个用户做不到的动作就是新的谎报。处置能力待后续
> 「failed 明细页（逐行重试/删除）」切片解锁，届时该档才应拿到按钮并升级为 danger 警告色。

### 6.4 登录 / 换号时的本地数据处置（数据安全关键路径）

`login()` 在 `saveSession()` **之前**读取旧 `userId`（`TokenManager.getLastKnownUserId()`），
据此分三条路径：

| 场景 | 判定 | pending 队列 | categories/accounts/notifications |
|---|---|---|---|
| **同账号重登**（Token 过期或**显式登出后**重登，典型场景） | `oldId != null && oldId == newId` | **保留**；并把 `last_error LIKE '%Token expired%'` 的记录重置回 `pending`（`resetFailedMatching`），再主动排一次同步 | 保留（server 派生缓存，`HomeViewModel.refresh()` 会重新拉；清掉会造成登录后短暂空白） |
| **换号** | `oldId != null && oldId != newId` | 清空 | 清空 |
| **首次登录 / 旧版本无 userId** | `oldId == null` | 清空（保守，无法判断是否换号） | 清空 |
| **切换服务器**（`ServerConfig` 页保存，`clearLocalCache = true`） | 用户主动保存（不走 `oldId` 判定） | 清空 | 清空（含 `notification_records`，与上面两类登录路径**逐表一致**，都是 4 张；两边测试各钉 4 表） |

> ⚠️ 历史 bug（1）：早期实现**无条件** `clearLocalCache()`。Token 30 天过期（无 refresh
> token）恰恰是离线数据最容易堆积的时刻，用户重登后所有未同步的记账被物理删除。
> 提交 `bd39a2b` 的原意是「**换号**清缓存」，条件判断漏了。
> `AuthRepositoryImplTest` 的 `DATA LOSS regression` 用例锁住此不变量。

> ⚠️ 历史 bug（2，B6）：`logout()` 曾调 `clearSessionAndIdentity()` 把 `last_known_user_id`
> 一并删除。于是「登出 → 登回**同一账号**」时 `oldId == null`，退化为「首次登录」路径并
> `clearLocalCache()`，把用户离线记的账物理删除——而 `ProfileScreen` 的登出确认弹窗向用户
> 承诺过「本地未同步的数据不会丢失」。现在 `logout()` 只调 `endSession(SIGNED_OUT)`，
> **保留 `last_known_user_id`**。
>
> 保留它**不降低**换号防护：上表第 2 行靠的是 `oldId != newId` 的**比较**，
> 不靠 `oldId != null`。`AuthRepositoryImplTest` 用 `logout then SAME/DIFFERENT account login`
> 两个用例同时锁住「同号保留」与「换号仍清空」。

> ⚠️ 同账号判定必须用 `TokenManager.getLastKnownUserId()`，**不能**用 `getUserId()`：
> 会话结束时 `endSession()` 会把 `user_id` 一并清掉，而
> 「Token 过期 → 重登」「显式登出 → 登回」正是最需要保住离线队列的场景。
> 因此 `last_known_user_id` 刻意活得比 session 长——**任何**会话结束路径都不清除它。
> 它不是 session 的一部分，而是**本地缓存的归属判据**。

> ⚠️ `SyncWorker` 在**无 token 时直接 `Result.success()` 短路**，不发请求、不碰队列。
> `NetworkMonitor` 会在 App 启动时排同步（这是预期行为），若不加此守卫，
> 未登录状态下每条 pending 都会拿到 401 被误标 `failed`，
> 而它们本来可以在用户登录后继续上传。

### 6.3 WorkManager 调度

| Worker | 类型 | 调度 | 触发时机 |
|---|---|---|---|
| SyncWorker | **OneTime** | NetworkType.CONNECTED + APPEND_OR_REPLACE | `NetworkMonitor` 监听到「离线→在线」跳变；通知自动记账；通知中心确认；用户手动点同步；同账号重新登录后 |
| RulesSyncWorker | Periodic | NetworkType.CONNECTED | 拉取云端规则（ETag/304） |
| UpdateCheckWorker | Periodic | NetworkType.CONNECTED | GitHub Release 检查 |
| NlsHealthCheckWorker | Periodic | 通知监听断连恢复 |
| A11yHealthCheckWorker | Periodic | 无障碍服务健康检查 |

> ⚠️ **SyncWorker 是 OneTime，不是 Periodic，也没有定时兜底。**
> 离线队列完全依赖上表列出的触发点，其中最关键的是 `NetworkMonitor`
> （由 `AiBillApp` 在启动时注入以触发 NetworkCallback 注册）。
> `registerNetworkCallback` 会**立即**回调一次当前网络状态，因此「App 启动时已联网」
> 也能把积压的 pending 队列排空。
> 若 `NetworkMonitor` 未被注入，离线记账将**永远不会**自动同步。

**Application 启动调度幂等**：`AiBillApp.scheduleWorkers()` 调用多次不会重复创建任务（各 Worker 用 `KEEP` 策略）。

---

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
**保留 `last_known_user_id`**。

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
