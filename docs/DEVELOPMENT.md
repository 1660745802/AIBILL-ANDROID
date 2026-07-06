# AIBILL Android 研发方案

> 版本: v1.0  
> 日期: 2026-07-02  
> 作者: likunhong  
> 状态: 初稿

---

## 一、技术栈

| 层级 | 技术选型 | 理由 |
|------|----------|------|
| 语言 | Kotlin | Android 官方推荐，空安全 + 协程支持 |
| UI 框架 | Jetpack Compose | 声明式 UI，现代化开发体验，Material 3 原生支持 |
| 架构模式 | MVVM + Clean Architecture | 分层清晰，单向数据流，易于测试 |
| 网络层 | Retrofit + OkHttp + Moshi | 成熟方案，codegen 序列化，拦截器链 |
| 本地存储 | Room (SQLite) | 离线数据 + 缓存，协程 + Flow 支持 |
| 轻量存储 | DataStore (Preferences) | 用户非敏感设置（主题/服务器地址），不存 Token |
| 依赖注入 | Hilt | Google 官方 DI 方案，编译期校验 |
| 异步处理 | Coroutines + Flow | 响应式数据流，取消安全 |
| 分页 | Paging 3 | 与 LazyColumn 无缝集成，自动管理加载状态 |
| 日志 | Timber | 结构化日志，Release 自动关闭 verbose/debug |
| 图表 | Vico | Compose 原生图表库，Material 3 适配 |
| 导航 | Navigation Compose | 类型安全路由，Deep Link 支持 |
| 通知监听 | NotificationListenerService | 系统级 API，无需 Root |
| 语音识别 | SpeechRecognizer | 系统内置，免费无限制 |
| 后台任务 | WorkManager | 保证同步任务可靠执行，支持约束条件 |
| 小组件 | Glance (Compose Widget) | Compose 风格 Widget 开发 |
| 图片加载 | Coil | Kotlin 优先，Compose 原生集成 |
| 加密存储 | EncryptedSharedPreferences | Token 安全存储 |

---

## 二、项目架构设计

### 2.1 分层架构图

```
┌─────────────────────────────────────────────────────┐
│                  Presentation Layer                   │
│   Compose UI ← ViewModel ← UiState (StateFlow)      │
└────────────────────────┬────────────────────────────┘
                         │ UseCase 调用
┌────────────────────────▼────────────────────────────┐
│                    Domain Layer                       │
│   UseCase ← Repository Interface ← Domain Model     │
└────────────────────────┬────────────────────────────┘
                         │ Repository 实现
┌────────────────────────▼────────────────────────────┐
│                     Data Layer                        │
│   Remote (Retrofit API)  ←→  Local (Room + DataStore)│
└─────────────────────────────────────────────────────┘
```

### 2.2 目录结构

```
app/src/main/java/com/aibill/android/
├── di/                           # Hilt 依赖注入模块
│   ├── NetworkModule.kt          # Retrofit/OkHttp 提供
│   ├── DatabaseModule.kt         # Room 数据库提供
│   └── RepositoryModule.kt       # Repository 接口绑定实现
│
├── domain/                       # 业务逻辑层（纯 Kotlin）
│   ├── model/                    # 业务实体
│   │   ├── Transaction.kt
│   │   ├── Category.kt
│   │   ├── Account.kt
│   │   ├── Budget.kt
│   │   ├── AiParseResult.kt
│   │   └── SyncStatus.kt
│   ├── repository/               # Repository 接口
│   │   ├── AuthRepository.kt
│   │   ├── TransactionRepository.kt
│   │   ├── CategoryRepository.kt
│   │   ├── AccountRepository.kt
│   │   ├── AiRepository.kt
│   │   ├── StatsRepository.kt
│   │   └── BudgetRepository.kt
│   └── usecase/                  # 业务用例
│       ├── auth/
│       │   ├── LoginUseCase.kt
│       │   ├── RegisterUseCase.kt
│       │   └── ValidateTokenUseCase.kt
│       ├── transaction/
│       │   ├── CreateTransactionUseCase.kt
│       │   ├── GetTransactionsUseCase.kt
│       │   ├── SyncPendingUseCase.kt
│       │   └── DeleteTransactionUseCase.kt
│       ├── ai/
│       │   ├── ParseInputUseCase.kt
│       │   ├── ChatUseCase.kt
│       │   └── LocalRuleMatchUseCase.kt
│       └── notification/
│           ├── ParseNotificationUseCase.kt
│           └── ConfirmNotificationUseCase.kt
│
├── data/                         # 数据层
│   ├── remote/                   # 网络
│   │   ├── api/                  # Retrofit 接口
│   │   │   ├── AuthApi.kt
│   │   │   ├── TransactionApi.kt
│   │   │   ├── AiApi.kt
│   │   │   ├── StatsApi.kt
│   │   │   ├── BudgetApi.kt
│   │   │   └── CategoryApi.kt
│   │   ├── dto/                  # 网络数据模型
│   │   │   ├── request/
│   │   │   └── response/
│   │   └── interceptor/          # OkHttp 拦截器
│   │       ├── AuthInterceptor.kt
│   │       ├── RetryInterceptor.kt
│   │       └── ServerUrlInterceptor.kt
│   ├── local/                    # 本地存储
│   │   ├── db/
│   │   │   ├── AppDatabase.kt
│   │   │   ├── dao/
│   │   │   │   ├── PendingTransactionDao.kt
│   │   │   │   ├── CategoryDao.kt
│   │   │   │   ├── AccountDao.kt
│   │   │   │   └── NotificationRecordDao.kt
│   │   │   └── entity/
│   │   │       ├── PendingTransactionEntity.kt
│   │   │       ├── CategoryEntity.kt
│   │   │       ├── AccountEntity.kt
│   │   │       └── NotificationRecordEntity.kt
│   │   └── datastore/
│   │       └── UserPreferences.kt
│   ├── repository/               # Repository 实现
│   │   ├── AuthRepositoryImpl.kt
│   │   ├── TransactionRepositoryImpl.kt
│   │   ├── AiRepositoryImpl.kt
│   │   └── ...
│   └── mapper/                   # Entity ↔ Domain 转换
│       ├── TransactionMapper.kt
│       └── CategoryMapper.kt
│
├── presentation/                 # UI 层
│   ├── ui/
│   │   ├── home/                 # 首页
│   │   │   ├── HomeScreen.kt
│   │   │   ├── HomeViewModel.kt
│   │   │   └── components/
│   │   ├── transactions/         # 流水
│   │   │   ├── TransactionsScreen.kt
│   │   │   ├── TransactionsViewModel.kt
│   │   │   └── components/
│   │   ├── statistics/           # 统计
│   │   ├── profile/              # 我的
│   │   ├── auth/                 # 登录注册
│   │   │   ├── LoginScreen.kt
│   │   │   ├── ServerConfigScreen.kt
│   │   │   └── AuthViewModel.kt
│   │   ├── record/               # 手动记账
│   │   ├── chat/                 # AI 对话
│   │   └── notification/         # 通知中心
│   ├── navigation/               # 导航
│   │   ├── NavGraph.kt
│   │   ├── Route.kt
│   │   └── BottomNavBar.kt
│   ├── theme/                    # 主题
│   │   ├── Color.kt
│   │   ├── Type.kt
│   │   └── Theme.kt
│   ├── components/               # 公共组件
│   │   ├── ConfirmCard.kt
│   │   ├── AmountText.kt
│   │   ├── CategoryGrid.kt
│   │   ├── LoadingShimmer.kt
│   │   └── EmptyState.kt
│   └── widget/                   # 桌面小组件
│       ├── QuickRecordWidget.kt
│       └── MonthlySummaryWidget.kt
│
├── service/                      # Android 服务
│   ├── NotificationMonitorService.kt
│   └── SyncWorker.kt
│
└── util/                         # 工具类
    ├── AmountUtils.kt            # 金额分↔元转换
    ├── DateUtils.kt              # 日期格式化
    ├── NetworkUtils.kt           # 网络状态检测
    └── NotificationParser.kt     # 通知文本正则解析
```

### 2.3 数据流设计

```
单向数据流（Unidirectional Data Flow）：

UI Event → ViewModel → UseCase → Repository → [Remote API / Local DB]
                                                        ↓
UI ← Compose State ← StateFlow ← Flow ← Repository ← Result
```

**ViewModel 状态管理模式**：

```kotlin
// 标准 UiState 模式（持续状态）
data class HomeUiState(
    val isLoading: Boolean = false,
    val monthlyExpense: Int = 0,
    val todayTransactions: List<Transaction> = emptyList(),
    val aiParseResults: List<AiParseResult>? = null,
    val error: String? = null
)

// 一次性事件（Toast/Snackbar/导航等不可重复消费的事件）
sealed class UiEvent {
    data class ShowSnackbar(val message: String) : UiEvent()
    data class ShowToast(val message: String) : UiEvent()
    data object NavigateToLogin : UiEvent()
}

// ViewModel 中
class HomeViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle, // 进程恢复
    ...
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    // 一次性事件通道，避免 Compose 重组时重复消费
    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    // 需要跨进程恢复的状态使用 SavedStateHandle
    val selectedFilter = savedStateHandle.getStateFlow("filter", "all")
}
```

**UseCase 注入方式**（无需 UseCaseModule，直接 `@Inject constructor`）：

```kotlin
class CreateTransactionUseCase @Inject constructor(
    private val transactionRepository: TransactionRepository
) {
    suspend operator fun invoke(items: List<TransactionItem>): Result<CreateTransactionResponse> {
        return transactionRepository.createTransactions(items)
    }
}
```

**三层模型规范**（DTO ↔ Domain ↔ Entity）：

| 模型 | 位置 | 职责 | 注解 |
|------|------|------|------|
| DTO | data/remote/dto/ | 网络序列化 | `@JsonClass`, `@Json(name=)` |
| Domain Model | domain/model/ | 纯业务对象 | 无框架注解，纯 Kotlin |
| Entity | data/local/entity/ | Room 表映射 | `@Entity`, `@PrimaryKey` |

转换通过 `data/mapper/` 中的扩展函数完成，Domain 层不依赖任何框架注解。


---

## 三、网络层设计

### 3.1 Retrofit 接口定义规范

```kotlin
interface TransactionApi {
    @POST("transactions")
    suspend fun createTransactions(
        @Body request: CreateTransactionRequest
    ): ApiResponse<CreateTransactionResponse>

    @GET("transactions")
    suspend fun getTransactions(
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 20,
        @Query("start_date") startDate: String? = null,
        @Query("end_date") endDate: String? = null,
        @Query("type") type: String? = null,
        @Query("category_id") categoryId: Int? = null,
        @Query("keyword") keyword: String? = null
    ): ApiResponse<PaginatedResponse<TransactionDto>>

    @PUT("transactions/{id}")
    suspend fun updateTransaction(
        @Path("id") id: Int,
        @Body request: UpdateTransactionRequest
    ): ApiResponse<TransactionDto>

    @DELETE("transactions/{id}")
    suspend fun deleteTransaction(@Path("id") id: Int): ApiResponse<Unit>
}
```

### 3.2 OkHttp 拦截器链

```
请求流程：
  App → ServerUrlInterceptor → AuthInterceptor → RetryInterceptor → LoggingInterceptor → Server
```

| 拦截器 | 职责 | 说明 |
|--------|------|------|
| ServerUrlInterceptor | 动态替换 Base URL | 从 DataStore 读取用户配置的服务器地址 |
| AuthInterceptor | 自动附加 JWT | `Authorization: Bearer <token>` |
| RetryInterceptor | 网络错误重试 | 指数退避，最多 3 次，仅重试网络异常 |
| LoggingInterceptor | 调试日志 | Debug 构建输出请求/响应日志 |

### 3.3 统一响应处理

```kotlin
// 通用 API 响应包装
@JsonClass(generateAdapter = true)
data class ApiResponse<T>(
    @Json(name = "code") val code: Int,
    @Json(name = "data") val data: T?,
    @Json(name = "message") val message: String
)

// 统一结果封装
sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val code: Int, val message: String) : Result<Nothing>()
    data object Loading : Result<Nothing>()
}

// Repository 中的统一处理
suspend fun <T> safeApiCall(apiCall: suspend () -> ApiResponse<T>): Result<T> {
    return try {
        val response = apiCall()
        when (response.code) {
            0 -> {
                // 安全处理 data 可能为 null 的情况（如 DELETE 操作）
                @Suppress("UNCHECKED_CAST")
                val data = response.data ?: (Unit as T)
                Result.Success(data)
            }
            else -> Result.Error(response.code, response.message)
        }
    } catch (e: IOException) {
        Result.Error(-1, "网络连接失败，请检查网络")
    } catch (e: HttpException) {
        Result.Error(e.code(), "服务器错误: ${e.code()}")
    } catch (e: Exception) {
        Result.Error(-2, "未知错误: ${e.message}")
    }
}
```

注意：401 统一在 AuthInterceptor 中处理（见 3.2），Repository 层不再需要单独判断 401。

### 3.4 Token 管理 + 401 全局处理

```kotlin
// 使用 EncryptedSharedPreferences 存储敏感数据
@Singleton
class TokenManager @Inject constructor(@ApplicationContext context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getToken(): String? = prefs.getString("jwt_token", null)
    fun saveToken(token: String) = prefs.edit().putString("jwt_token", token).apply()
    fun clearToken() = prefs.edit().remove("jwt_token").apply()
}

// 全局认证事件总线（用于 401 统一跳转登录）
@Singleton
class AuthEventBus @Inject constructor() {
    private val _events = MutableSharedFlow<AuthEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<AuthEvent> = _events.asSharedFlow()

    fun emit(event: AuthEvent) { _events.tryEmit(event) }
}

sealed class AuthEvent {
    data object TokenExpired : AuthEvent()
}

// AuthInterceptor 中统一处理 401
class AuthInterceptor @Inject constructor(
    private val tokenManager: TokenManager,
    private val authEventBus: AuthEventBus
) : Interceptor {
    override fun intercept(chain: Chain): Response {
        val token = tokenManager.getToken()
        val request = chain.request().newBuilder().apply {
            if (token != null) addHeader("Authorization", "Bearer $token")
        }.build()

        val response = chain.proceed(request)
        if (response.code == 401) {
            tokenManager.clearToken()
            authEventBus.emit(AuthEvent.TokenExpired) // UI 层监听并导航到登录页
        }
        return response
    }
}
```

### 3.5 OkHttp 超时与重试配置

```kotlin
// NetworkModule 中配置
OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)   // AI 接口可能响应慢
    .writeTimeout(15, TimeUnit.SECONDS)
    .addInterceptor(serverUrlInterceptor)
    .addInterceptor(authInterceptor)
    .addInterceptor(retryInterceptor)
    .addInterceptor(loggingInterceptor)
    .build()

// RetryInterceptor：仅对幂等请求重试
class RetryInterceptor : Interceptor {
    override fun intercept(chain: Chain): Response {
        val request = chain.request()
        var response: Response? = null
        var retryCount = 0
        val maxRetry = 3

        while (retryCount < maxRetry) {
            try {
                response = chain.proceed(request)
                if (response.isSuccessful) return response
                break // 非网络错误不重试
            } catch (e: IOException) {
                retryCount++
                if (retryCount >= maxRetry || !isRetryable(request)) throw e
                Thread.sleep(1000L * retryCount) // 简单退避
            }
        }
        return response ?: throw IOException("Max retries exceeded")
    }

    // 仅 GET/HEAD 或明确标记幂等的请求可重试
    private fun isRetryable(request: Request): Boolean {
        return request.method in listOf("GET", "HEAD", "OPTIONS") ||
               request.header("X-Idempotent") == "true"
    }
}
```

---

## 四、离线同步设计

### 4.1 同步流程

```
┌─────────────────────────────────────────────────────────┐
│                    用户记账操作                            │
└────────────────────────┬────────────────────────────────┘
                         │
            ┌────────────▼────────────┐
            │      检测网络状态        │
            ├───── 有网络 ────┬── 无网络 ──┤
            │                │            │
   ┌────────▼────────┐      │   ┌────────▼────────┐
   │  直接调 API 入库  │      │   │  存入 Room       │
   │  + 本地缓存写入   │      │   │  syncStatus =    │
   │  syncStatus =    │      │   │  "pending"       │
   │  "synced"        │      │   │  生成 client_id   │
   └─────────────────┘      │   └────────┬────────┘
                             │            │
                             │   ┌────────▼────────┐
                             │   │ 网络恢复触发      │
                             │   │ WorkManager      │
                             │   └────────┬────────┘
                             │            │
                             │   ┌────────▼────────┐
                             │   │ SyncWorker 执行   │
                             │   │ 批量 POST API    │
                             │   │ 幂等安全重试      │
                             │   └────────┬────────┘
                             │            │
                             │   ┌────────▼────────┐
                             │   │ 更新本地状态      │
                             │   │ pending → synced │
                             │   │ 或 → failed      │
                             │   └─────────────────┘
                             │
                             └─────────────────────
```

### 4.2 SyncWorker 实现要点

```kotlin
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val pendingDao: PendingTransactionDao,
    private val transactionApi: TransactionApi
) : CoroutineWorker(context, params) {

    companion object {
        const val MAX_RETRY_COUNT = 5
    }

    override suspend fun doWork(): Result {
        val pendingItems = pendingDao.getAllPending()
        if (pendingItems.isEmpty()) return Result.success()

        // 逐条同步，避免批量中部分失败导致已成功的被重试
        pendingItems.forEach { item ->
            // 超过最大重试次数，标记为 failed，不再处理
            if (item.retryCount >= MAX_RETRY_COUNT) {
                pendingDao.updateSyncStatus(item.clientId, "failed")
                return@forEach
            }

            try {
                val request = CreateTransactionRequest(
                    items = listOf(item.toRequestItem())
                )
                val response = transactionApi.createTransactions(request)
                if (response.code == 0) {
                    pendingDao.updateSyncStatus(item.clientId, "synced")
                } else if (response.code == 401) {
                    // Token 过期，中断同步，等待用户重新登录
                    return Result.failure()
                } else {
                    // 业务错误（如 422 参数错误），标记失败不重试
                    pendingDao.updateSyncStatus(item.clientId, "failed")
                    pendingDao.updateLastError(item.clientId, response.message)
                }
            } catch (e: IOException) {
                // 网络异常，增加重试计数
                pendingDao.incrementRetryCount(item.clientId, e.message)
            }
        }

        // 检查是否还有待同步项，有则重试
        val remaining = pendingDao.getPendingCount()
        return if (remaining > 0) Result.retry() else Result.success()
    }
}
```

### 4.3 WorkManager 调度策略

```kotlin
// 网络恢复时触发同步
val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
    .setConstraints(
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    )
    .setBackoffCriteria(
        BackoffPolicy.EXPONENTIAL,
        WorkRequest.MIN_BACKOFF_MILLIS,
        TimeUnit.MILLISECONDS
    )
    .build()

WorkManager.getInstance(context).enqueueUniqueWork(
    "sync_pending_transactions",
    ExistingWorkPolicy.KEEP,  // 避免重复调度
    syncRequest
)
```

### 4.4 冲突处理策略

| 场景 | 策略 | 实现方式 |
|------|------|----------|
| 新增冲突 | client_id 幂等 | 服务端 UNIQUE(user_id, client_id)，重复返回已有记录 |
| 编辑冲突 | Last Write Wins | 比较 updatedAt 时间戳，客户端较新则覆盖 |
| 删除冲突 | 删除优先 | 任一端删除则最终状态为已删除 |
| 离线编辑已同步记录 | 乐观锁 | 带 version 字段提交，409 冲突时提示用户 |

---

## 五、通知监听设计

### 5.1 NotificationListenerService

```kotlin
@AndroidEntryPoint
class NotificationMonitorService : NotificationListenerService() {

    @Inject lateinit var notificationParser: NotificationParser
    @Inject lateinit var notificationDao: NotificationRecordDao

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // 1. 检查全局开关
        if (!isNotificationMonitorEnabled()) return

        // 2. 检查应用白名单
        if (!isInWhitelist(sbn.packageName)) return

        // 3. 检查免打扰时段
        if (isInQuietHours()) return

        // 4. 防御性检查（通知 extras 可能为 null）
        val extras = sbn.notification?.extras ?: return

        // 5. 提取通知文本
        val text = extractNotificationText(sbn)
        if (text.isBlank()) return

        // 6. 去重检查（1秒内相同金额+来源不重复处理）
        if (isDuplicate(sbn.packageName, text)) return

        // 7. 本地正则解析
        val parseResult = notificationParser.parse(sbn.packageName, text)

        // 8. 存入数据库
        val record = NotificationRecordEntity(
            packageName = sbn.packageName,
            title = extras.getString("android.title"),
            content = text,
            parsedAmount = parseResult?.amount,
            parsedType = parseResult?.type,
            parsedDescription = parseResult?.description,
            status = if (parseResult != null) "parsed" else "raw",
            receivedAt = System.currentTimeMillis()
        )
        notificationDao.insert(record)

        // 9. 根据置信度决定行为
        when {
            parseResult == null -> { /* 无法识别，静默存储 */ }
            parseResult.confidence >= 90 -> autoConfirm(record, parseResult)
            parseResult.confidence >= 60 -> showConfirmPopup(record, parseResult)
            else -> { /* 存入待审列表，不打扰 */ }
        }
    }
}
```

**注意事项**：
- Hilt 2.51+ 支持对 Service 使用 `@AndroidEntryPoint`，NotificationListenerService 属于系统绑定服务
- 如遇注入问题，可改用 `EntryPointAccessors.fromApplication()` 手动获取依赖
- Android 13+ 需额外请求 `POST_NOTIFICATIONS` 运行时权限才能发送 Heads-up 通知

### 5.2 通知解析正则规则

```kotlin
@Singleton
class NotificationParser @Inject constructor() {
    // 微信支付
    private val WECHAT_PAY = Regex("""(微信支付|付款).*?[￥¥](\d+\.?\d*)""")
    // 支付宝
    private val ALIPAY = Regex("""(支付宝|付款).*?[￥¥](\d+\.?\d*)""")
    // 银行短信
    private val BANK_SMS = Regex("""(消费|支出|扣款).*?(\d+\.?\d*)元""")

    fun parse(packageName: String, text: String): ParseResult? {
        return when {
            packageName == "com.tencent.mm" -> parseWechat(text)
            packageName == "com.eg.android.AlipayGphone" -> parseAlipay(text)
            BANK_SMS.containsMatchIn(text) -> parseBankSms(text)
            else -> null
        }
    }
}
```

### 5.3 实时弹窗确认

```
App 在前台 → Snackbar/Dialog 弹窗
App 在后台 → Heads-up Notification + Action Buttons (确认/忽略)
连续多笔   → 队列依次弹出，不堆叠
10秒未操作 → 自动收起，进入通知中心待确认列表
```


---

## 六、分阶段研发计划

### M1 - v0.1 MVP 核心闭环（3 周）

#### Week 1：项目基础 + 认证 + 网络层

| # | 任务 | 产出 | 验收标准 |
|---|------|------|----------|
| 1 | 项目脚手架搭建 | Hilt + Compose + Navigation 基础项目 | 编译通过，空白页面可运行 |
| 2 | 网络层搭建 | Retrofit + OkHttp + 拦截器链 | 可发送请求到后端 |
| 3 | 本地存储搭建 | Room 数据库 + DataStore + Entity | 数据库创建成功 |
| 4 | 服务器地址配置页 | 输入框 + 连通性测试 + 持久化 | 配置地址后可连接后端 |
| 5 | 登录/注册页面 | 完整认证流程 + Token 存储 | 登录成功跳转首页 |
| 6 | 主框架搭建 | BottomNavBar + 4 Tab 空页面 | Tab 切换流畅 |
| 7 | 深色模式基础 | Theme.kt + 动态切换 | Light/Dark/System 三模式 |

#### Week 2：AI 记账 + 手动记账

| # | 任务 | 产出 | 验收标准 |
|---|------|------|----------|
| 8 | 分类/账户数据同步 | API 拉取 + Room 缓存 | 本地可读取分类和账户列表 |
| 9 | AI 记账核心流程 | 输入框 → AI 解析 → 确认卡片 | 文本输入后展示解析结果 |
| 10 | 确认卡片组件 | 单条/多条确认 + 修改 + 全部确认 | 确认后调 API 成功入库 |
| 11 | 手动记账页面 | 类型/金额/分类/账户/日期表单 | 表单提交成功创建交易 |
| 12 | 计算器键盘组件 | 自定义数字键盘 + 表达式计算 | 输入"32+15"得到 47 |
| 13 | 连续记账模式 | 保存后清空表单继续 | 连续记账不退出页面 |

#### Week 3：流水列表 + 离线同步

| # | 任务 | 产出 | 验收标准 |
|---|------|------|----------|
| 14 | 流水列表页面 | 按日分组 + 日小计 + 分页加载 | 列表正确展示，滚动流畅 |
| 15 | 搜索筛选功能 | 关键词 + 日期范围 + 类型/分类 | 筛选结果正确 |
| 16 | 交易编辑/删除 | 详情编辑 + 左滑删除 + 二次确认 | 编辑保存成功，删除软删除 |
| 17 | 离线暂存实现 | Room 存储 pending 交易 | 无网络时手动记账不报错 |
| 18 | 同步机制实现 | SyncWorker + 网络监听触发 | 联网后自动同步 pending 数据 |
| 19 | 首页实现 | 月支出 + AI 输入 + 快捷短语 + 今日流水 | 首页完整可用 |
| 20 | v0.1 联调测试 | 全流程测试 + Bug 修复 | 核心闭环可日常使用 |

---

### M2 - v0.2 自动化记账（3 周）

#### Week 4：通知监听 + 统计分析

| # | 任务 | 产出 | 验收标准 |
|---|------|------|----------|
| 21 | NotificationListenerService | 通知监听服务 + 权限引导 | 可捕获微信/支付宝通知 |
| 22 | 通知文本正则解析器 | 金额/商家/类型提取 | 微信支付宝解析成功率 > 80% |
| 23 | 实时弹窗确认 | Heads-up 通知 + Action Buttons | 后台可快速确认记账 |
| 24 | 通知中心页面 | 待确认列表 + 批量操作 | 可查看/确认/忽略待处理项 |
| 25 | 统计摘要接口 | 月度收支 + 环比变化 | 数据正确 |
| 26 | 趋势图实现 | Vico 折线图 + 日/周/月 | 图表正确渲染 |
| 27 | 分类饼图 + 排行 | 环形图 + 排行列表 | 占比计算正确 |

#### Week 5：智能分类 + 记账模板

| # | 任务 | 产出 | 验收标准 |
|---|------|------|----------|
| 28 | 智能分类学习引擎 | 本地规则存储 + 匹配逻辑 | 修改分类后下次自动匹配 |
| 29 | 本地规则优先策略 | 规则命中跳过 AI 调用 | 相同关键词不重复调 AI |
| 30 | 记账模板 | 保存/加载/使用模板 | 一键复用常用记账 |
| 31 | App Shortcuts | 长按图标快捷入口 | 快速记账/语音记账/AI记账 |
| 32 | 置信度计算引擎 | 多因素评分 | 高置信度自动入库 |

#### Week 6：短信解析 + 体验完善

| # | 任务 | 产出 | 验收标准 |
|---|------|------|----------|
| 33 | 短信解析能力 | BroadcastReceiver + 正则 | 银行扣款短信自动识别 |
| 34 | 自动记账设置页 | 开关/白名单/免打扰/自动化程度 | 用户可控制自动化行为 |
| 35 | 免确认规则配置 | 小额/商家/时段规则 | 规则匹配后静默入库 |
| 36 | 省电保活引导 | 品牌检测 + 图文引导 | 各品牌手机不杀后台 |
| 37 | v0.2 联调测试 | 通知监听全流程 | 自动记账稳定可用 |

---

### M3 - v0.3 预算与数据（2 周）

| # | 任务 | 产出 | 验收标准 |
|---|------|------|----------|
| 38 | 预算管理页面 | 总预算 + 分类预算 + 进度条 | CRUD 正常，进度正确 |
| 39 | 预算超支推送 | 超支检测 + 本地通知 | 超支时推送提醒 |
| 40 | 消费洞察提醒 | 环比异常检测 + 推送 | "本月餐饮比上月多40%" |
| 41 | 账单导入 | CSV 文件选择 + 上传 | 微信/支付宝 CSV 导入成功 |
| 42 | 周期记账 | WorkManager 定时触发 | 每月固定日期自动记录 |
| 43 | 数据导出 | JSON/CSV 下载保存 | 文件正确导出到本地 |
| 44 | 记账打卡统计 | 连续天数 + 里程碑 | 统计正确，里程碑触发 |

---

### M4 - v0.4 体验增强（2 周）

| # | 任务 | 产出 | 验收标准 |
|---|------|------|----------|
| 45 | 语音输入 | SpeechRecognizer + 长按交互 | 语音转文字后走 AI 解析 |
| 46 | 快速记账 Widget | Glance 实现 2×1 Widget | 桌面点击直接打开记账 |
| 47 | 月度摘要 Widget | Glance 实现 4×2 Widget | 展示本月收支数据 |
| 48 | 通知栏快捷入口 | 常驻通知 + Action | 下拉通知栏可快速记账 |
| 49 | 应用锁 | BiometricPrompt 实现 | 指纹/面部解锁 App |
| 50 | 性能优化 | 启动优化 + 列表优化 | 冷启动 < 2s，列表 60fps |


---

## 七、关键技术决策

| 决策 | 方案 | 原因 |
|------|------|------|
| 金额存储 | 整数（分） | 避免浮点精度问题，¥32.50 存为 3250 |
| 序列化 | Moshi + codegen | 编译期生成适配器，非反射，性能好 |
| JSON 字段映射 | `@Json(name = "snake_case")` | 后端 snake_case → 客户端 camelCase |
| 模型分层 | 三层模型 (DTO ↔ Domain ↔ Entity) | DTO 用于网络序列化、Entity 用于 Room 映射、Domain 为纯业务对象，通过 Mapper 转换 |
| 数据隔离 | 所有 API 自动附加 JWT | 服务端强制 user_id 过滤 |
| 幂等保障 | UUID v4 作为 client_id | 防重复提交/重试，服务端 UNIQUE 约束 |
| Token 存储 | EncryptedSharedPreferences | AES256 加密，DataStore 只存非敏感设置 |
| 无 Refresh Token | 过期强制重登 | 自部署场景，30 天有效期足够，简化实现 |
| 401 处理 | OkHttp Interceptor 全局拦截 | 统一处理，通过 AuthEventBus 通知 UI 层跳转登录 |
| 图标方案 | Material Icons 名称映射 | 服务端返回 icon 名称字符串，App 维护映射表 |
| 分页策略 | Paging 3 + page/page_size | 与 LazyColumn 无缝集成，自动管理加载态 |
| Room Migration | AutoMigration + schema export | 简单变更自动迁移，复杂变更手写 |
| 网络状态 | ConnectivityManager.NetworkCallback | 实时监听，网络恢复触发 WorkManager |
| 状态恢复 | SavedStateHandle | 进程死亡后恢复关键 UI 状态（筛选条件/滚动位置） |
| 一次性事件 | Channel → receiveAsFlow | Toast/Snackbar/导航事件不被 Compose 重组重复消费 |
| 图表库 | Vico | Compose 原生，Material 3 适配，轻量 |
| 日期处理 | java.time (LocalDate/LocalTime) | API 26+，无需额外依赖 |
| 构建类型 | debug (无混淆) / release (R8 + ProGuard) | Release 包体积优化 + 代码保护 |
| ProGuard 规则 | 保留 DTO/Entity/Retrofit 接口 | Moshi codegen 需保留 @Json 注解，Retrofit 需保留方法签名 |
| 最低 API | 26 (Android 8.0) | 覆盖 95%+ 设备，支持 NotificationChannel |
| 目标 API | 34 (Android 14) | 适配最新系统，前台服务需声明 foregroundServiceType |
| 通知权限 | POST_NOTIFICATIONS (API 33+) | Android 13+ 发送通知需运行时权限 |
| 模块化 | 初期单 app 模块 | 编译时间超 60s 时启动拆分 (:core:network / :core:database / :feature:*) |
| 短信解析 | 通过 NotificationListenerService 捕获短信通知 | Android 10+ 后台 App 无法接收 SMS_RECEIVED 广播 |

---

## 八、错误处理规范

### 8.1 错误分层处理

| 层级 | 错误类型 | 处理方式 |
|------|----------|----------|
| Network | IOException / TimeoutException | 提示"网络连接失败"，显示重试按钮 |
| HTTP | 4xx / 5xx | 按错误码分类处理 |
| Business | code ≠ 0 | 展示服务端 message |
| Local | Room / DataStore 异常 | 降级处理，记录日志 |

### 8.2 错误码处理映射

| 错误码 | 含义 | App 行为 |
|--------|------|----------|
| 0 | 成功 | 正常流程 |
| 401 | Token 无效/过期 | 清除 Token → 跳转登录页 |
| 403 | 无权限 | Toast 提示 "无权限访问" |
| 422 | 参数校验失败 | 显示具体 message 到表单 |
| 5001 | AI 解析失败 | 提示 "AI 暂时无法理解，请手动记账" |
| 5002 | AI 服务不可用 | 提示 "AI 服务不可用，请稍后重试" |
| 500x | 服务端错误 | 通用错误提示 + 重试按钮 |
| -1 | 网络异常 (本地) | "网络连接失败，请检查网络" |
| -2 | 未知错误 (本地) | "发生未知错误，请重试" |

### 8.3 AI 调用降级策略

```
用户输入 → 本地规则匹配
    ├── 匹配成功 → 直接生成确认卡片（不调 AI）
    └── 匹配失败 → 调用 AI 解析 API
                    ├── 成功 → 展示确认卡片 + 记录规则
                    ├── 超时(>5s) → 提示手动记账
                    └── 失败 → 展示手动记账表单（预填原始输入）
```

### 8.4 UI 错误展示规范

| 场景 | 展示方式 | 示例 |
|------|----------|------|
| 操作成功 | Toast (短时) | "记账成功" |
| 操作失败 | Snackbar + 重试 | "保存失败" [重试] |
| 网络错误 | Snackbar + 重试 | "网络异常" [重试] |
| 空状态 | 全屏引导 | 插图 + "还没有记录" + [去记账] |
| 加载中 | Shimmer 骨架屏 | 列表页占位 |
| Token 过期 | Dialog | "登录已过期，请重新登录" [确定] |

---

## 九、开发环境配置

### 9.1 环境要求

| 工具 | 版本要求 | 说明 |
|------|----------|------|
| Android Studio | Ladybug (2024.2+) | Compose 支持最佳 |
| JDK | 17 | Gradle 兼容 |
| Kotlin | 2.0+ | K2 编译器 |
| Gradle | 8.6+ | 版本目录 (TOML) |
| Android SDK | 26 (min) / 34 (target) | |
| 后端服务 | Node.js 20 + AIBILL 服务端 | 本地或远程均可 |

### 9.2 项目初始化

```bash
# 1. 克隆项目
git clone <repo-url> && cd bill-android

# 2. 后端准备（本地开发用）
# 方式一：Docker 启动 AIBILL 后端
docker-compose -f ../AIBILL/docker-compose.yml up -d

# 方式二：直接运行（需要 Node.js）
cd ../AIBILL/server && npm install && npm run dev

# 3. 配置 local.properties
echo "SERVER_URL=http://10.0.2.2:3000/api" >> local.properties
# 注：10.0.2.2 是模拟器访问宿主机的特殊 IP

# 4. Android Studio 打开项目，同步 Gradle，运行
```

### 9.3 构建变体

| 变体 | 用途 | 特点 |
|------|------|------|
| debug | 开发调试 | 日志输出、无混淆、可调试、Mock 数据 |
| release | 发布 | R8 混淆、日志关闭、签名、ProGuard 规则 |

### 9.4 代码规范

| 规范项 | 标准 |
|--------|------|
| 代码风格 | ktlint (标准 Kotlin 风格) |
| 命名 | 类 PascalCase / 函数 camelCase / 常量 UPPER_SNAKE |
| Compose 函数 | 大驼峰命名，接收 Modifier 参数 |
| 注释 | 公共 API KDoc，复杂逻辑行内注释 |
| 分支策略 | main (稳定) / develop (开发) / feature/* (功能) |
| 提交信息 | `type(scope): description`，如 `feat(ai): add parse input` |

### 9.5 依赖版本管理

使用 Gradle Version Catalog (`libs.versions.toml`)：

```toml
[versions]
kotlin = "2.0.21"
compose-bom = "2024.12.01"
hilt = "2.51.1"
room = "2.6.1"
retrofit = "2.11.0"
moshi = "1.15.1"
coroutines = "1.8.1"
navigation = "2.8.4"
vico = "2.0.0"
coil = "2.7.0"
work = "2.9.1"

[libraries]
# Compose
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "compose-bom" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
# Hilt
hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
hilt-compiler = { group = "com.google.dagger", name = "hilt-compiler", version.ref = "hilt" }
# Room
room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
# Network
retrofit = { group = "com.squareup.retrofit2", name = "retrofit", version.ref = "retrofit" }
moshi-kotlin = { group = "com.squareup.moshi", name = "moshi-kotlin-codegen", version.ref = "moshi" }
# ...
```

---

## 十、性能优化策略

| 优化项 | 目标 | 方案 |
|--------|------|------|
| 冷启动 | < 2s | Splash Screen API + 延迟初始化非关键模块 |
| 列表性能 | 60fps | LazyColumn + key 稳定 + 避免重组 |
| 内存占用 | < 150MB | 图片按需加载 + Compose 生命周期管理 |
| 网络优化 | 减少请求 | 分类/账户本地缓存 + 分页加载 + 防抖搜索 |
| APK 体积 | < 30MB | R8 + 资源压缩 + 按需依赖 |
| 数据库 | 查询 < 50ms | 索引优化 + 适当反范式 |
| Compose | 最小重组 | remember + derivedStateOf + 稳定参数 |

---

## 十一、自动记账架构（v3）

### 11.1 数据流总览

```
┌─────────────────────────────────────────────────────────────────────────┐
│  信号源                                                                   │
│  NotificationListenerService / SmsReceiverService / AccessibilityService │
└───────────────┬──────────────────────────────────────────────────────────┘
                │
                ▼
┌───────────────────────────────────────────────────────────────────────────┐
│ L1: 排除层 (isLikelyFinancial)                                            │
│   微信：title=="微信支付" 或 全文含 PAYMENT_SIGNAL → 放行；否则丢弃         │
│   支付宝：title 含支付/账单/花呗/余额/到账 或全文含 PAYMENT_SIGNAL → 放行   │
│   银行/短信 App：全部放行                                                  │
│   效果：微信聊天/订阅号/广告 100% 过滤                                     │
└───────────────────────────────────────────────────────────────────────────┘
                │
                ▼
┌───────────────────────────────────────────────────────────────────────────┐
│ L2: 缓冲去重 (NotificationBuffer, 60s 窗口)                               │
│   通知入内存池，60s 或满 5 条时 flush                                      │
│   去重：跨包名 + 同金额 + 60s 内 → 合并（保留信息最丰富的）                │
│   不合并：同包名（视为真实两笔）/ 金额不同 / 超过 60s                       │
│   效果：微信+银行+短信同一笔 → 只保留 1 条给 AI                            │
└───────────────────────────────────────────────────────────────────────────┘
                │
                ▼
┌───────────────────────────────────────────────────────────────────────────┐
│ L3: 解析（学习引擎 > AI > 正则降级）                                       │
│   ① CategoryLearningEngine 命中 → 秒出结果，跳过 AI（0 成本）              │
│   ② AI /api/ai/parse → 返回金额/类型/分类/商家                            │
│   ③ AI 失败/超时 → 丢弃（正则不面对用户）                                  │
│   AI 成功后自动触发学习引擎记忆 merchant→category                          │
└───────────────────────────────────────────────────────────────────────────┘
                │
                ▼
┌───────────────────────────────────────────────────────────────────────────┐
│ L4: 入库策略                                                              │
│   AI 结果 + 信息完整 → 入库 + 轻通知（无按钮 5s 消失）                     │
│   AI 结果 + 缺关键信息 → 待审池 + 发确认通知                               │
│   AI 返回空 → 丢弃                                                        │
│   AI 失败 → 丢弃                                                          │
│   ★ 正则永远不面对用户，仅用于去重层提取金额比较                             │
└───────────────────────────────────────────────────────────────────────────┘
```

### 11.2 核心设计原则

| 原则 | 说明 |
|------|------|
| AI 是唯一裁判 | 不在本地判断"是不是支付"，只排除"100%不是"的垃圾 |
| 正则永远不面对用户 | 正则仅用于去重金额提取，不生成记录、不发通知 |
| 用户看到的 = AI 确认的 | 待审池只接受"AI有结果但缺信息"的记录 |
| 入库即通知 | AI 确认入库后发轻通知（无按钮，5s消失），用户知道但无需操作 |
| 去重在本地 | 60s 缓冲覆盖银行短信延迟，后端零改动 |

### 11.3 数据来源（多渠道互补）

| 渠道 | 技术方案 | 覆盖场景 | 权限 |
|------|----------|----------|------|
| 通知监听 | NotificationListenerService | 微信/支付宝/银行付款通知 | 通知使用权 |
| 短信读取 | BroadcastReceiver(RECEIVE_SMS) | 银行卡消费短信 | 短信权限 |
| 无障碍服务 | PaymentAccessibilityService | 支付结果页（覆盖无通知场景） | 无障碍权限 |
| 分享 OCR | ShareReceiverActivity + ML Kit | 用户主动截图/分享补录 | 无 |

所有渠道统一入 NotificationBuffer → 跨渠道去重 → AI 解析 → 入库。

### 11.4 分类学习 (CategoryLearningEngine)

- 精确匹配 → 包含匹配（近似词边界）→ 无匹配返回 null
- AI 返回结果后自动 `learnFromCorrection(merchant, categoryId)`
- 用户在通知中心修改分类后反向学习
- 稳态后学习命中率 60-70%，大幅减少 AI 调用

### 11.5 AI 结果校验 (AiResultValidator)

| 规则 | 校验内容 |
|------|----------|
| 金额范围 | 0 < amount ≤ 1,000,000 分（1万元） |
| 类型合法 | expense / income / transfer |
| 分类存在 | categoryId 在本地分类表中 |
| 描述长度 | ≤ 200 字符 |

校验通过 = 信息完整 → 直接入库
校验失败 = 缺关键信息 → 待审池（让用户补充）

### 11.6 权限引导设计

| 权限 | 能否检测 | 展示方式 |
|------|----------|----------|
| 通知监听 | ✓ 可检测 | 显示 ✓/✗ + "去开启" |
| 通知弹窗 | ✓ 可检测 | 显示 ✓/✗ + "去开启" |
| 电池优化白名单 | ✓ 可检测 | 显示 ✓/✗ + "去设置" |
| 无障碍服务 | ✗ 无法检测 | 不显示状态，常驻"去设置"按钮 |
| 后台自启动 | ✗ 无法检测 | 不显示状态，常驻"去设置"按钮 |

---

*文档结束*
