# AIBILL Android 架构文档

> 本文档描述项目从架构到编码规范的完整设计。  
> 对应代码：`app/src/main/java/com/aibill/android/`  
> 适用版本：versionCode 3 / versionName 1.2.0 / minSdk 26 / targetSdk 35

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

### 2.1 当前实际目录

```
app/src/main/java/com/aibill/android/
├── AiBillApp.kt                 # Application：Timber + 定时任务调度
├── di/                          # Hilt 模块
│   ├── NetworkModule.kt         # Retrofit/OkHttp/拦截器
│   ├── DatabaseModule.kt        # Room + Migration
│   ├── RepositoryModule.kt      # 接口绑定实现
│   └── WorkModule.kt
├── domain/
│   ├── model/                   # Transaction / User / Result / CategoryAndAccount
│   ├── repository/              # 6 个接口（Auth/Transaction/Category/Account/Ai/BudgetAndStats）
│   └── usecase/                 # CategoryLearningEngine / StreakTracker
├── data/
│   ├── local/
│   │   ├── db/AppDatabase.kt    # Room v7，9 张表
│   │   ├── entity/              # 9 个 Entity
│   │   ├── dao/                 # 9 个 DAO
│   │   └── datastore/           # UserPreferences + SyncLock
│   └── remote/
│       ├── api/                 # 10 个 Retrofit API（含 AppUpdateApi）
│       ├── dto/request|response/
│       ├── interceptor/         # Auth/Retry/ServerUrl + TokenManager + AuthEventBus
│       └── SafeApiCall.kt
├── presentation/
│   ├── MainActivity.kt + MainViewModel.kt
│   ├── navigation/              # Route（13 条类型安全路由）+ NavHost + BottomNavBar
│   ├── theme/                   # Theme/Type/Shape/AppButtons
│   ├── widget/                  # Glance 桌面小组件（QuickRecord/MonthlySummary）
│   ├── utils/                   # AmountUtils（分↔元）
│   └── ui/                      # 10 个模块（home/transactions/statistics/...）
├── service/                     # 后台服务（13 个核心组件）
└── util/                        # NetworkMonitor / AppLogger / 通知解析 / 银行短信正则
```

### 2.2 13 条类型安全路由

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
| 13 | 网络状态 | NetworkCallback | 网络恢复触发 WorkManager |
| 14 | 状态恢复 | SavedStateHandle | 进程死亡恢复筛选/滚动 |
| 15 | 一次性事件 | Channel → receiveAsFlow | 不被 Compose 重组重复消费 |
| 16 | 图表库 | Vico | Compose 原生，Material 3 |
| 17 | 日期处理 | java.time | API 26+，无额外依赖 |
| 18 | 构建类型 | debug / release（R8 + ProGuard） | Release 体积优化 |
| 19 | ProGuard | 保留 DTO/Entity/Retrofit 接口 | Moshi codegen 需保留 @Json |
| 20 | 最低 API | 26（Android 8.0） | 覆盖 95%+ 设备 |
| 21 | 目标 API | 35（Android 15） | 适配最新系统 |
| 22 | 通知权限 | POST_NOTIFICATIONS（API 33+） | Android 13+ 运行时申请 |
| 23 | 模块化 | 初期单 app 模块 | 编译 > 60s 时启动拆分 |
| 24 | 通知监听架构 | L1-L4 分层（v3） | 见 §七 |
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
    data object TokenExpired : AuthEvent()  // UI 收到后 navigate "login_force"
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
                    网络恢复 → WorkManager 触发 SyncWorker
                              ↓
                    逐条 POST /api/transactions (幂等)
                              ↓
                    成功 → "synced" / 401 → 中断等重登 / 其他失败 → "failed"
```

### 6.2 SyncWorker 要点

- `@HiltWorker` + `@AssistedInject`
- 逐条同步而非批量，避免部分失败导致已成功的被重试
- `MAX_RETRY_COUNT = 5`，超过标记 `failed` 不再处理
- 401 → `Result.failure()` 中断，等待用户重新登录
- 网络异常 → 增加 `retryCount`，让 WorkManager 自动重试

### 6.3 WorkManager 调度

| Worker | 调度 | 触发时机 |
|---|---|---|
| SyncWorker | NetworkType.CONNECTED + Periodic | 网络恢复时 |
| RulesSyncWorker | Periodic + NetworkType.CONNECTED | 拉取云端规则（ETag/304） |
| UpdateCheckWorker | Periodic + NetworkType.CONNECTED | GitHub Release 检查 |
| InsightWorker | Periodic | 统计洞察 |
| NlsHealthCheckWorker | Periodic | 通知监听断连恢复 |
| A11yHealthCheckWorker | Periodic | 无障碍服务健康检查 |

**Application 启动调度幂等**：`AiBillApp.scheduleWorkers()` 调用多次不会重复创建任务（各 Worker 用 `KEEP` 策略）。

---

## 七、通知监听 v3 架构（L1-L4 分层）

> 项目核心差异化能力。三渠道（通知 + 无障碍 + SMS）统一入 `NotificationBuffer` → 跨渠道去重 → AI 解析 → 入库。

### 7.1 数据流总览

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

### 7.2 核心设计原则

| 原则 | 说明 |
|---|---|
| AI 是唯一裁判 | 不在本地判断"是不是支付"，只排除"100%不是"的垃圾 |
| 正则永远不面对用户 | 正则仅用于去重金额提取，不生成记录、不发通知 |
| 用户看到的 = AI 确认的 | 待审池只接受"AI 有结果但缺信息"的记录 |
| 入库即通知 | AI 确认入库后发轻通知（无按钮，5s 消失），用户知道但无需操作 |
| 去重在本地 | 60s 缓冲覆盖银行短信延迟，后端零改动 |

### 7.3 数据来源（多渠道互补）

| 渠道 | 技术方案 | 覆盖场景 | 权限 |
|---|---|---|---|
| 通知监听 | NotificationListenerService | 微信/支付宝/银行付款通知 | 通知使用权 |
| 短信读取 | BroadcastReceiver(RECEIVE_SMS) | 银行卡消费短信 | 短信权限 |
| 无障碍服务 | PaymentAccessibilityService | 支付结果页（覆盖无通知场景） | 无障碍权限 |
| 分享 OCR | ShareReceiverActivity + ML Kit | 用户主动截图/分享补录 | 无 |

### 7.4 分类学习（CategoryLearningEngine）

- 精确匹配 → 包含匹配（近似词边界）→ 无匹配返回 null
- AI 返回结果后自动 `learnFromCorrection(merchant, categoryId)`
- 用户在通知中心修改分类后反向学习
- 稳态后学习命中率 60-70%，大幅减少 AI 调用

### 7.5 AI 结果校验（AiResultValidator）

| 规则 | 校验内容 |
|---|---|
| 金额范围 | 0 < amount ≤ 1,000,000 分（1 万元） |
| 类型合法 | expense / income / transfer |
| 分类存在 | categoryId 在本地分类表中 |
| 描述长度 | ≤ 200 字符 |

校验通过 = 信息完整 → 直接入库；校验失败 = 缺关键信息 → 待审池。

### 7.6 权限引导

| 权限 | 可检测 | 展示方式 |
|---|---|---|
| 通知监听 | ✓ | ✓/✗ + "去开启" |
| 通知弹窗 | ✓ | ✓/✗ + "去开启" |
| 电池优化白名单 | ✓ | ✓/✗ + "去设置" |
| 无障碍服务 | ✗ | 常驻"去设置"按钮 |
| 后台自启动 | ✗ | 常驻"去设置"按钮 |

---

## 八、数据模型

### 8.1 本地数据库（Room v7，9 张表）

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

### 8.2 DataStore 存储项

| Key | 类型 | 说明 |
|---|---|---|
| `jwt_token` | String | 用户登录 Token |
| `user_id` | Int | 当前用户 ID |
| `username` / `nickname` | String | 用户信息 |
| `server_url` | String | 服务端地址 |
| `default_account_id` | Int | 默认记账账户 |
| `theme_mode` | String | light / dark / system |
| `notification_enabled` | Boolean | 通知监听开关 |
| `app_lock_enabled` | Boolean | 应用锁开关 |
| `hide_from_recents` | Boolean | 从最近任务隐藏 |
| `last_sync_time` | Long | 最后全量同步时间 |

**UserPreferences.kt** 使用 `stateIn(Eagerly)` + `AtomicReference` 暴露热流，避开 Interceptor 同步路径 runBlocking。

---

## 九、性能规范

| 维度 | 要求 |
|---|---|
| 冷启动 | < 2s 首屏可交互 |
| 启动耗时 | Application.onCreate 不做 I/O（仅 Timber + Worker schedule） |
| AI 接口 | 30s 超时，其他 15s |
| 列表 | LazyColumn + `key()` + `contentType` |
| 重组 | 大型 Composable 拆 `remember`/`derivedStateOf` |
| DB 查询 | 索引列（sync_status / received_at），避免全表扫描 |
| 图片 | Coil + 缓存策略 |
| 数据缓存 | 分类/账户本地缓存，减少重复请求 |

---

## 十、安全规范

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

## 十一、错误处理

### 11.1 错误分层

| 层级 | 错误类型 | 处理方式 |
|---|---|---|
| Network | IOException / TimeoutException | "网络连接失败"，重试按钮 |
| HTTP | 4xx / 5xx | 按错误码分类处理 |
| Business | code ≠ 0 | 展示服务端 message |
| Local | Room / DataStore 异常 | 降级处理，记录日志 |

### 11.2 AI 调用降级

```
用户输入 → 本地规则匹配（CategoryLearningEngine）
            ↓ 命中 → 直接返回
            ↓ 未命中
        POST /api/ai/parse
            ↓ 成功 → 入库 + 自动学习
            ↓ 失败 → 提示"AI 暂不可用，请手动记账"
```

### 11.3 UI 错误展示

- 网络请求成功：Toast / 数据刷新 / 页面跳转
- 网络请求失败：Snackbar + 错误信息 + 重试按钮
- 破坏性操作（删除/退出）：确认弹窗或撤销机制
- 加载中：Loading 指示器（CircularProgress / Shimmer）
- 列表为空：空状态插图 + 引导文案
- 保存成功：明确视觉反馈（Toast/动画）

---

## 十二、测试规范

### 12.1 测试金字塔

```
        ╱  E2E  ╲          # 模拟用户路径（手动冒烟）
       ╱ 集成测试  ╲        # Repository + Room + API（MockWebServer）
      ╱  单元测试   ╲       # ViewModel / Util / UseCase
```

### 12.2 工具矩阵

| 层 | 工具 |
|---|---|
| 框架 | JUnit5 + MockK + Turbine + Robolectric |
| 网络 | MockWebServer |
| 数据库 | Room Testing |
| 异步 | kotlinx-coroutines-test |
| UI | Compose UI Test（debugImplementation） |
| 覆盖率 | Kover |

### 12.3 覆盖率目标

- 工具类（AmountUtils / NotificationParser / BankSmsPatterns）：100%
- ViewModel：核心 8 个 ≥ 70%
- Repository：≥ 60%
- UI 测试：每个主页面至少 1 个冒烟用例

### 12.4 提交门禁

- `develop` 分支 CI：单元测试 + Lint + koverCheck 全部通过
- Lint：`./gradlew lintDebug` 无 error

---

## 十三、代码规范

### 13.1 命名规范

| 类型 | 规范 | 示例 |
|---|---|---|
| 包名 | 全小写 | `com.aibill.android.data.remote` |
| 类名 | PascalCase | `TransactionRepositoryImpl` |
| 函数/变量 | camelCase | `getTransactions` |
| 常量 | UPPER_SNAKE | `MAX_RETRY_COUNT` |
| Compose 组件 | PascalCase + Screen 后缀 | `HomeScreen`, `TransactionDetailScreen` |
| ViewModel | PascalCase + ViewModel 后缀 | `HomeViewModel` |
| 资源 | snake_case | `ic_notification.xml`, `theme_color_primary` |

### 13.2 架构层级规范

- **domain 层**禁止依赖 `android.*` / `androidx.*` / `data.*`
- **data 层**实现 domain 接口，不暴露 Entity 给 UI
- **presentation 层**通过 Hilt 注入 ViewModel，禁止直接 new
- 跨层通信仅通过 Repository 接口和 UseCase

### 13.3 Compose 规范

- 所有 Screen 必须有 `@Preview`
- 大型 Composable 拆 `remember` / `derivedStateOf`
- 列表用 `LazyColumn` + `key()` + `contentType`
- 用 `collectAsStateWithLifecycle()` 而非 `collectAsState()`
- 一次性事件用 `Channel` + `receiveAsFlow()`，不用 `StateFlow`

### 13.4 ViewModel 规范

- 注入用 `@HiltViewModel` + `@Inject constructor`
- 暴露 `StateFlow<UiState>`（`asStateFlow()`）+ 私有 `MutableStateFlow`
- 一次性事件用 `Channel<UiEvent>`
- 不持有 Composable / Context 引用
- 协程在 `viewModelScope` 启动

### 13.5 网络层规范

- 所有 API 返回 `suspend fun → Result<T>`，不用 `Call<T>`
- Repository 合并 remote + local：先网络再缓存，先缓存再网络，看场景
- 401 不在 Repository 处理，统一走 AuthInterceptor

### 13.6 数据库规范

- Room schema export 开启（`app/schemas/`）
- 起始 version = 1，简单变更 AutoMigration，复杂手写
- Entity 字段必须显式 nullable，DAO 用 suspend / Flow
- 不在主线程做 DB I/O（Room 自动调度）

### 13.7 金额处理规范

- **存储 / 传输 / 计算**：一律用整数（分）
- **UI 显示**：用 `AmountUtils.formatYuan()`（分→元，"32.00"）
- **UI 输入**：计算器键盘实时解析
- **禁止** `Double` / `Float` 参与金额计算

---

## 十四、发布流程

### 14.1 发布检查清单

- [ ] `develop` 分支 CI 全部通过（单元测试 + Lint + koverCheck）
- [ ] 6 个用户路径冒烟通过
- [ ] 无 P0/P1 未解决 Bug
- [ ] 版本号已更新（`versionCode` + `versionName`）
- [ ] 创建 PR：`develop` → `main`
- [ ] 合并后打 Tag

### 14.2 构建命令

```bash
./gradlew assembleDebug           # Debug APK
./gradlew assembleRelease         # Release APK（需 keystore.properties）
./gradlew testDebugUnitTest       # 单元测试
./gradlew koverHtmlReport         # 覆盖率 HTML 报告
./gradlew lintDebug               # Lint 检查
```

### 14.3 6 个必测用户路径

| # | 场景 | 验证点 |
|---|---|---|
| 1 | 首次打开 App | 服务器配置 → 登录 → 首页有数据 |
| 2 | AI 记账 | 输入 → 解析 → 确认 → 今日流水更新 |
| 3 | 手动记账 | 填写 → 保存 → 明确反馈 → 连续模式清空 |
| 4 | 清后台重开 | 直接进首页，不重新登录 |
| 5 | 退出登录 | Token 清除 → 下次打开在登录页 |
| 6 | 每个按钮点一遍 | 无死按钮、无闪退 |

---

## 十五、实战经验（通知监听 & AI 记账踩坑）

> 项目独有沉淀，新成员必读。

### 15.1 通知文本解析：金额分散在任意字段

`EXTRA_TITLE` / `EXTRA_TEXT` / `EXTRA_BIG_TEXT` / `EXTRA_SUB_TEXT` / `EXTRA_INFO_TEXT` 都可能含金额，**必须合并全文**再解析。只取 `EXTRA_TEXT` 会导致金额解析为 0。

```kotlin
val fullText = listOf(title, text, bigText, subText, infoText)
    .filter { it.isNotBlank() }.distinct().joinToString(" ")
// 后续所有引用都用 fullText，不要残留旧的 text/title 变量
```

### 15.2 AI 兜底调用要"双重拦截"

正则命中优先，正则失败才走 AI。但 AI 调用有成本 + 隐私风险（会把通知全文发给后端），微信/短信白名单会捕获大量普通聊天，必须预筛：

```kotlin
val hasDigit = text.any { it.isDigit() }
val hasPaymentSignal = text.contains(Regex(
    "[¥￥$]|元|支付|付款|收款|到账|消费|交易|转账|红包|退款|扣款|余额|账单|还款"
))
if (!hasDigit || !hasPaymentSignal) return  // 直接丢弃，不存 raw 不调 AI
```

否则微信聊天会以 `raw` 状态刷屏通知中心，且含数字的聊天会白白触发后端 AI。

### 15.3 隐私：sourceDetail 不要存全文

`PendingTransactionEntity.sourceDetail` 会同步到后端。存来源名（"微信支付"）而非 `fullText`（可能含私聊内容）。三处构造入口（Service 静默入库、ViewModel confirmItem、confirmAll）字段应保持一致。

### 15.4 批量确认要跳过无金额记录

`confirmAll` 必须跳过 `parsedAmount <= 0` 的记录，否则会生成 0 元账单同步到后端（资损/脏数据）。单条确认走编辑对话框（有金额校验），批量确认无对话框保护，需代码兜底。

### 15.5 收支类型不要硬编码

确认通知标题、图标等不要写死"支出"。parser/AI 会产出 income（收款到账、退款），需按 `type` 动态显示，否则收入被标成支出。

### 15.6 通知点击跳转

- `MainActivity` 必须声明 `android:launchMode="singleTop"`，否则点击通知会重建 Activity 而非走 `onNewIntent`，丢失返回栈
- `navigate_to` extra 要"一次性消费"：NavHost 的 `LaunchedEffect(navigateTo)` 处理后回调清空，否则解锁/重建时会误跳

### 15.7 MIUI 测试限制

- MIUI 对 `adb shell cmd notification post` 发的通知**不分发**给 `NotificationListenerService`，无法用 adb 造测通知。用 App 内测试按钮发本地通知，或用真实支付通知验证
- `MainActivity` 用 `BiometricPrompt`（应用锁）时必须继承 `FragmentActivity`，`ComponentActivity` 会崩溃

---

## 附录 A：AI 辅助开发规范

- AI 生成代码必须符合本文档所有规范（架构分层、命名、测试）
- 生成后必须编译通过
- 关键逻辑必须附带单元测试
- 禁止生成含 `TODO`/`FIXME` 的代码提交到 develop
- AI 生成的 Compose 组件必须支持 `@Preview`
- AI 辅助生成的代码在 commit body 标注：`AI-assisted: 核心逻辑由 AI 生成，已 review 并调整`
- AI 不可做：跳过测试 / 引入未在 libs.versions.toml 中管理的依赖 / 修改已稳定接口签名 / 生成超过 300 行的单文件

---

## 附录 B：Release 发布流程

### B.1 准备签名

仓库根目录准备 `keystore.properties`（已在 `.gitignore`）：

```properties
storeFile=/absolute/path/to/release.keystore
storePassword=<store-pass>
keyAlias=<alias>
keyPassword=<key-pass>
```

生成签名：

```bash
keytool -genkey -v \
  -keystore release.keystore \
  -alias aibill \
  -keyalg RSA -keysize 4096 -validity 10000
```

### B.2 版本号约定

- `versionCode`：单调递增整数（用户设备升级对比），每次发版 +1
- `versionName`：语义化版本 `MAJOR.MINOR.PATCH`
  - MAJOR：不兼容的 API 变更
  - MINOR：向后兼容的新功能
  - PATCH：向后兼容的 Bug 修复

### B.3 发布步骤

```bash
# 1. 确认 CI 绿：单元测试 + Kover 覆盖率门槛通过
./gradlew :app:testDebugUnitTest :app:koverVerify

# 2. Release 构建（产出 app-release.apk）
./gradlew :app:assembleRelease

# 3. 验证 APK 完整性
$ANDROID_HOME/build-tools/<v>/apksigner verify --print-certs \
  app/build/outputs/apk/release/app-release.apk

# 4. 打 tag（annotated tag + release notes）
git tag -a v1.1.0 -m "v1.1.0 (2026-09-22)
- 重大变更：xxx
- 修复：xxx
- 测试：xxx"
git push origin v1.1.0

# 5. 触发自更新：UpdateCheckWorker 24h 内会检测到新 release
#    用户收到温和的"有新版本"通知
```

### B.4 自更新机制（双源 + 通知确认）

发布后由 [UpdateCheckWorker](./ARCHITECTURE.md#六-离线同步) 调度 [UpdateManager](./ARCHITECTURE.md#六-离线同步)，通过系统安装器触发升级：

**双源优先级策略**（[UpdateManager.checkUpdate()](./ARCHITECTURE.md#六-离线同步)）：

1. **billserver 自托管**（[AppUpdateApi](./ARCHITECTURE.md#三-api-契约)，`GET /api/app/update`）—— 公司可控源，内网可达
   - `has_update=true` → 返回该版本
   - `has_update=false` → 返回"已是最新"，**不** fallback GitHub（避免误判）
   - 网络/5xx 异常 → fallback GitHub
2. **GitHub Release fallback**（[GithubReleaseApi](./ARCHITECTURE.md#三-api-契约)）—— 兜底
   - 内网 billserver 不可达时启用
   - 用 tag 名做语义化版本对比（[isNewerVersion](./ARCHITECTURE.md#六-离线同步)）
3. 全部失败 → 返回 null（App 显示"获取失败"）

**三态判定**（`BillserverResult` sealed class）：
- `Success(info)`：billserver 可达且有版本
- `NoUpdate`：billserver 可达且已是最新（**关键**：不 fallback）
- `Failure(t)`：billserver 不可达

**UpdateInfo 字段**：
```kotlin
data class UpdateInfo(
    val versionName: String,    // "1.3.0"
    val changelog: String,      // 版本说明
    val apkUrl: String,         // 绝对 URL（billserver 或 GitHub）
    val apkSize: Long,
    val source: Source,         // BILLSERVER / GITHUB / UNKNOWN（诊断/统计用）
    val forceUpdate: Boolean,   // billserver 可下发强制升级
)
```

**下载与安装**（[UpdateManager.downloadAndInstall](./ARCHITECTURE.md#六-离线同步)）：
- 系统 DownloadManager（带进度通知）
- 完成 → [DownloadCompleteReceiver](./ARCHITECTURE.md#七-通知监听-v3-架构) → FileProvider → 系统安装器

**通知确认流程**（避免误触下载）：
- [UpdateNotifier](./ARCHITECTURE.md#七-通知监听-v3-架构) 通知带「立即更新」「稍后」两按钮
- Settings 页检查到新版本时弹 [AlertDialog](./ARCHITECTURE.md#七-通知监听-v3-架构) 二次确认
- `SettingsViewModel.checkUpdate` 返回三态：`Available / UpToDate / Failed`

**强制升级**：
- billserver 下发 `force_update=true` 时忽略用户延迟，直接走下载流程
- 仅按需启用（一般版本不强制）
- 客户端实现（P0-2）：
  * 通知：隐藏「稍后」按钮，设为 Ongoing（用户不能普通滑动取消），标题改「必须升级到 X.Y.Z」
  * Settings 页 AlertDialog：强制升级时隐藏「稍后」按钮，禁止对话框 dismiss，标题红色「必须升级到 X.Y.Z」+ 顶部加「服务端标记为强制升级」说明
  * UpdateInstallReceiver：接收 EXTRA_FORCE_UPDATE intent extra，ACTION_DISMISS 时若强制升级则忽略
  * UpdateInfo 字段保留 forceUpdate，传递链路完整

**手动检查反馈**（三态，P0-2 + 1737f1d）：
- SettingsViewModel.checkUpdate 用 `UpdateManager.fetchLatestResult()` 三态（Success / NoUpdate / Failure）
- 每个分支保证有用户可见 Toast（修复「点了没反应」永久沉默 bug）：
  * Success + 版本更新 → `发现新版本 x.y.z` + 弹 AlertDialog（强制升级时隐藏「稍后」）
  * Success + 版本不新 → `已是最新版本 vX.Y.Z`
  * NoUpdate（billserver 权威说不需更新）→ `已是最新版本 vX.Y.Z（服务器无更新）`
  * Failure（所有源都拿不到）→ `获取失败：billserver 不可达且 GitHub 拉取失败`
- UpdateCheckWorker（后台）P1-1：异常返回 Result.retry() 而非无脑 success，WorkManager 指数退避（10s 起）

**降级与回退**：
- billserver 不可达 → GitHub Release（不影响使用）
- 旧版 App（无 AppUpdateApi）走纯 GitHub 路径

**安全**：
- APK 签名由系统安装器校验
- `apk_url` 必须是**绝对 URL**，由服务端控制（CORS/防盗链）
- 不强制升级（除服务端主动下发 `force_update`）

### B.5 ProGuard / R8 规则

Release 构建启用 R8 (`isMinifyEnabled = true` + `isShrinkResources = true`)，配置见 [`app/proguard-rules.pro`](../app/proguard-rules.pro)。

**关键保留**：

| 库 | 处理方式 |
|---|---|
| Moshi DTO | `-keep class com.aibill.android.data.remote.dto.**` |
| Moshi codegen 字段 | `@Json` 注解字段保留 |
| Retrofit 接口 | `-keepattributes Signature` + `@retrofit2.http.*` 注解方法 |
| Room Entity | `@androidx.room.Entity` 注解类 |
| Hilt 注入点 | `@dagger.hilt.*` 注解方法 |
| Worker 子类 | `extends androidx.work.*Worker` |
| Kotlin Serialization Route | `com.aibill.android.presentation.navigation.Route$*` |
| Tink（EncryptedSharedPrefs） | `-keep class com.google.crypto.tink.**` |

依赖库自带 `consumer-rules.pro`（retrofit/okhttp/navigation/glance/biometric）**不再额外全量 keep**，避免削弱 R8 优化收益。

### B.6 CI 流水线

[`.github/workflows/ci.yml`](../.github/workflows/ci.yml)：

- **触发**：push 到 main/develop / PR
- **步骤**：checkout → JDK 17 → Gradle cache → 单测 + Kover → assembleDebug → 上传 artifact
- **覆盖**：单测 207+ / Kover minBound 50%
- **artifact**：coverage-report / app-debug APK

### B.7 已知约束

- 自部署应用，签名密钥由用户自管（请妥善备份 `release.keystore`，丢失则无法升级覆盖旧版本）
- Release APK 未上传 Play Store（自托管，无需注册开发者账号）
- 服务器地址变更时，已同步数据保留，本地缓存保留（详见 [§3.1 API 契约](./ARCHITECTURE.md#31-基础协议)）