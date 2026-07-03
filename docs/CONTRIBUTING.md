# AIBILL Android 项目开发规范

> 本文档定义项目从开发到发布的全流程规范。
> 目标：代码可追溯、质量可控、架构清晰、项目可持续演进。

---

## 一、版本控制

### 1.1 分支策略（Git Flow 简化版）

```
main ─── 生产分支，始终可构建，仅通过 PR 合并
└── develop ─── 开发主线，功能完成后合并到 main
     ├── feat/xxx ─── 功能分支
     ├── fix/xxx ─── 缺陷修复
     └── refactor/xxx ─── 重构
```

| 分支 | 用途 | 保护规则 |
|------|------|----------|
| main | 可发布 APK 的稳定代码 | 禁止直接 push，必须 PR + CI 通过 |
| develop | 日常开发集成 | PR 合并，允许 squash merge |
| feat/* | 新功能开发 | 从 develop 拉出，完成后 PR 回 develop |
| fix/* | Bug 修复 | 从 develop 拉出，紧急修复可从 main 拉 hotfix |
| refactor/* | 重构 | 从 develop 拉出，不改变外部行为 |

### 1.2 分支命名

```
feat/ai-parse          # 功能：AI 记账解析
feat/notification-listen # 功能：通知监听
fix/amount-precision   # 修复：金额精度
fix/sync-retry         # 修复：同步重试
refactor/network-layer # 重构：网络层
docs/testing-plan      # 文档
chore/gradle-update    # 工程配置
```

### 1.3 Commit 规范（Conventional Commits）

```
<type>(<scope>): <subject>

<body>（可选）

<footer>（可选，如 BREAKING CHANGE）
```

**type 枚举：**

| type | 说明 |
|------|------|
| feat | 新功能 |
| fix | Bug 修复 |
| docs | 文档变更 |
| style | 格式调整（不影响逻辑） |
| refactor | 重构（不改变行为） |
| test | 测试相关 |
| chore | 构建/工具/依赖变更 |
| perf | 性能优化 |

**scope 参考：**

`ui`, `network`, `db`, `auth`, `transaction`, `ai`, `sync`, `notification`, `stats`, `budget`, `widget`, `theme`, `nav`, `gradle`

**示例：**

```
feat(ai): 实现 AI 记账解析核心流程
fix(sync): 修复离线同步部分失败后重复上传问题
refactor(network): 统一 401 全局拦截到 AuthInterceptor
test(transaction): 补充离线创建交易集成测试
chore(gradle): 升级 Compose BOM 到 2024.12.01
perf(ui): 优化流水列表 LazyColumn 重组范围
```

**规则：**
- subject 不超过 72 字符
- 使用中文描述
- 每个 commit 只做一件事，禁止混合提交
- 禁止提交包含 TODO / FIXME 的代码到 develop（本地分支可以）

### 1.4 Tag 与版本号

遵循 [Semantic Versioning](https://semver.org/)：`vMAJOR.MINOR.PATCH`

| 变更类型 | 版本号变化 | 示例 |
|---------|-----------|------|
| 不兼容架构变更 | MAJOR | v1.0.0 → v2.0.0 |
| 新增功能（向后兼容） | MINOR | v0.1.0 → v0.2.0 |
| Bug 修复 | PATCH | v0.1.0 → v0.1.1 |

里程碑对应：
- M1 完成（MVP 闭环） → v0.1.0
- M2 完成（自动化记账） → v0.2.0
- M3 完成（预算与数据） → v0.3.0
- M4 完成（体验增强） → v0.4.0
- 稳定可发布 → v1.0.0

### 1.5 禁止操作

- ❌ 直接 push 到 main
- ❌ force push 到公共分支（main / develop）
- ❌ commit 包含 API Key、密码、Token 等敏感信息
- ❌ 单个 commit 超过 500 行变更（应拆分）
- ❌ 提交无法编译通过的代码到 develop

---

## 二、代码规范

### 2.1 通用规则

- Kotlin 严格空安全，禁止 `!!` 操作符（除测试代码）
- 禁止使用 `var` 可变变量（除 ViewModel 内部状态和局部计算）
- 文件不超过 300 行，超过则拆分
- 函数不超过 30 行，超过则提取子函数
- 禁止嵌套超过 3 层（使用 `when`、`?.let`、提前 `return`）
- 所有公共 API 必须有 KDoc 注释
- 使用 `ktlint` 格式化，提交前自动检查

### 2.2 命名规范

| 类型 | 风格 | 示例 |
|------|------|------|
| 包名 | 全小写 | `com.aibill.android.data.remote` |
| 类/接口/对象 | PascalCase | `TransactionRepository` |
| 函数/属性 | camelCase | `getTransactions()` |
| 常量 | UPPER_SNAKE_CASE | `MAX_RETRY_COUNT` |
| Compose 函数 | PascalCase | `HomeScreen()`, `ConfirmCard()` |
| 布局/资源 | snake_case | `ic_category_food.xml` |
| Room Entity | PascalCase + Entity 后缀 | `PendingTransactionEntity` |
| DTO | PascalCase + Request/Response/Dto | `CreateTransactionRequest` |
| DAO | PascalCase + Dao 后缀 | `PendingTransactionDao` |
| ViewModel | PascalCase + ViewModel 后缀 | `HomeViewModel` |
| UseCase | PascalCase + UseCase 后缀 | `ParseInputUseCase` |

### 2.3 架构层级规范

```
presentation/  → UI + ViewModel（Compose + 状态管理）
domain/        → 纯业务逻辑（UseCase + Repository 接口 + Model）
data/          → 数据实现（API + Room + DataStore + Mapper）
```

**层级依赖规则（严格单向）：**

```
presentation → domain → data  ✅
presentation → data           ❌ 禁止跨层
domain → presentation         ❌ 禁止反向依赖
domain → data                 ❌ domain 只定义接口，不依赖实现
```

**各层职责：**

| 层级 | 可以做 | 不可以做 |
|------|--------|----------|
| Presentation | Compose UI、ViewModel 状态管理、导航、格式化展示 | 直接调 API/数据库、包含业务逻辑 |
| Domain | UseCase 编排、业务规则校验、定义 Repository 接口 | 依赖 Android 框架、直接网络/数据库操作 |
| Data | API 调用、Room 操作、DataStore 读写、DTO↔Model 转换 | UI 相关逻辑、直接暴露 Entity/DTO 给 UI |

### 2.4 Compose 规范

```kotlin
// ✅ 正确：Stateless Composable + 事件回调
@Composable
fun ConfirmCard(
    item: AiParseResult,
    onConfirm: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier  // Modifier 始终作为参数暴露
) { ... }

// ✅ 正确：Screen 级 Composable 通过 ViewModel 获取状态
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    HomeContent(uiState = uiState, onParseInput = viewModel::parseInput)
}

// ❌ 错误：在 Composable 中直接调用 suspend 函数
// ❌ 错误：在 Composable 中创建 ViewModel 实例
// ❌ 错误：Composable 中包含业务逻辑
```

**Compose 性能规则：**
- LazyColumn 必须使用 `key` 参数
- 避免在 Composable 中创建新对象（使用 `remember`）
- 使用 `derivedStateOf` 避免不必要的重组
- 大列表使用 `@Stable` 或 `@Immutable` 标注数据类
- Modifier 链中耗时操作使用 `drawBehind` / `graphicsLayer`

### 2.5 ViewModel 规范

```kotlin
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val parseInputUseCase: ParseInputUseCase,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    // ✅ 持续状态：StateFlow
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    // ✅ 一次性事件：Channel
    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    // ✅ 需要进程恢复的状态
    val selectedFilter = savedStateHandle.getStateFlow("filter", "all")

    // ✅ 公共方法统一命名为 on + 动作
    fun onParseInput(input: String) { ... }
    fun onConfirmItem(item: AiParseResult) { ... }
    fun onConfirmAll() { ... }
}
```

### 2.6 网络层规范

- 所有 API 接口定义在 `data/remote/api/` 包下
- DTO 类使用 Moshi `@JsonClass(generateAdapter = true)` 注解
- 后端 snake_case 字段用 `@Json(name = "xxx")` 映射
- 禁止在 DTO 中包含业务逻辑
- 所有网络调用必须通过 Repository，禁止 ViewModel 直接调用 API

### 2.7 数据库规范

- Entity 类统一放在 `data/local/entity/` 包下
- 主键使用 `@PrimaryKey`，自增用 `autoGenerate = true`
- 索引：查询频繁的字段添加 `@ColumnInfo(index = true)`
- 所有 DAO 方法返回 `Flow`（查询）或 `suspend`（写入）
- Room schema export 开启，起始 version = 1
- Migration 优先使用 AutoMigration，复杂变更手写

### 2.8 金额处理规范

```kotlin
// ✅ 所有金额以「分」为单位的整数存储和传输
val amount: Int = 3200  // 代表 ¥32.00

// ✅ 仅在 UI 展示时转换为元
fun Int.toYuanDisplay(): String = "¥${String.format("%.2f", this / 100.0)}"

// ✅ 用户输入的元转换为分
fun Double.toFen(): Int = Math.round(this * 100).toInt()

// ❌ 禁止在任何层使用 Double/Float 表示金额
// ❌ 禁止直接传输/存储小数金额
```

---

## 三、依赖管理

### 3.1 版本目录

所有依赖版本统一在 `gradle/libs.versions.toml` 中管理：

```toml
[versions]
kotlin = "2.0.21"
compose-bom = "2024.12.01"
hilt = "2.51.1"
room = "2.6.1"
# ...

[libraries]
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "compose-bom" }
# ...
```

### 3.2 依赖引入规则

- 新增依赖必须说明原因（commit message 或 PR 描述）
- 优先使用 Google/JetBrains 官方库
- 禁止引入不活跃维护的库（6 个月无更新需评估）
- 版本锁定，禁止使用 `+` 或 `latest`
- 同类功能只用一个库（如网络只用 Retrofit，不混用 Ktor）

---

## 四、测试规范

### 4.1 测试金字塔

| 层级 | 覆盖 | 工具 | 要求 |
|------|------|------|------|
| 单元测试 | UseCase / ViewModel / Utils / Parser | JUnit5 + MockK + Turbine | 核心逻辑 ≥ 90% |
| 集成测试 | Repository + Room + API | MockWebServer + Room inMemory | 关键路径覆盖 |
| UI 测试 | 关键页面交互 | Compose Testing | 冒烟测试 |

### 4.2 测试文件规范

```
src/test/         → 单元测试（JVM 运行）
src/androidTest/  → Instrumented 测试（设备/模拟器）
```

- 测试类命名：`被测类名 + Test`，如 `HomeViewModelTest`
- 测试方法命名：反引号 + 场景描述，如 `` `AI parse success should update state` ``
- 每个测试方法只验证一个行为
- 使用 AAA 模式：Arrange → Act → Assert

### 4.3 提交门禁

- 所有 PR 必须通过单元测试
- 新增功能必须附带对应测试
- Bug 修复必须附带回归测试
- 覆盖率不低于当前水平（只增不减）

---

## 五、错误处理规范

### 5.1 Result 封装

```kotlin
// 统一使用 sealed class 封装结果
sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val code: Int, val message: String) : Result<Nothing>()
    data object Loading : Result<Nothing>()
}

// ❌ 禁止在 Repository 中抛出异常（除 CancellationException）
// ✅ 所有异常在 Repository 中 catch 并转为 Result.Error
```

### 5.2 错误展示策略

| 场景 | 展示方式 |
|------|----------|
| 操作成功 | Toast（短时） |
| 操作失败（可重试） | Snackbar + 重试按钮 |
| 加载失败（页面级） | 全屏错误态 + 重试按钮 |
| Token 过期 | Dialog → 跳转登录 |
| 空数据 | 空状态插图 + 引导文案 |

### 5.3 日志规范

```kotlin
// ✅ 使用 Timber，禁止直接使用 Log.x()
Timber.d("解析成功: ${results.size} 条")
Timber.e(exception, "同步失败: clientId=$clientId")

// ❌ 禁止在日志中打印 Token / 密码 / 完整金额
// ❌ Release 构建禁止输出 verbose / debug 级别日志
```

---

## 六、性能规范

### 6.1 启动性能

- Application.onCreate() 禁止同步 IO 操作
- 非关键模块延迟初始化（使用 `by lazy` 或 Hilt 按需注入）
- Splash Screen API 管理启动画面

### 6.2 UI 性能

- 列表使用 LazyColumn + key，禁止用 Column + forEach
- 图片使用 Coil 异步加载，设置占位图和错误图
- 大计算使用 `remember` / `derivedStateOf` 缓存
- 禁止在 Compose 中阻塞主线程

### 6.3 数据库性能

- 查询必须在 IO 调度器执行
- 频繁查询的字段建立索引
- 分页使用 Paging 3，禁止一次性加载全部数据
- 批量写入使用 `@Transaction`

### 6.4 网络性能

- 搜索输入防抖 300ms
- 分类/账户数据本地缓存，减少重复请求
- 图片等大资源使用缓存策略
- AI 接口超时设为 30s，其他接口 15s

---

## 七、安全规范

| 项目 | 规则 |
|------|------|
| Token 存储 | 必须使用 EncryptedSharedPreferences |
| 网络传输 | 支持 HTTPS，NetworkSecurityConfig 配置自签证书 |
| 日志输出 | Release 禁止 debug/verbose 日志 |
| 代码混淆 | Release 必须开启 R8，保留 DTO/Entity/Retrofit 接口 |
| 敏感信息 | 禁止硬编码 URL/Key，使用 BuildConfig 或运行时配置 |
| 数据库 | allowBackup=false，考虑 SQLCipher 加密 |
| 权限 | 按需申请，用户拒绝后功能降级不崩溃 |

---

## 八、项目结构约定

```
bill-android/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/aibill/android/
│   │   │   │   ├── di/              # Hilt 模块
│   │   │   │   ├── domain/          # 业务逻辑层
│   │   │   │   ├── data/            # 数据层
│   │   │   │   ├── presentation/    # UI 层
│   │   │   │   ├── service/         # Android 服务
│   │   │   │   └── util/            # 工具类
│   │   │   ├── res/                  # 资源文件
│   │   │   └── AndroidManifest.xml
│   │   ├── test/                     # 单元测试
│   │   └── androidTest/              # Instrumented 测试
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── gradle/
│   └── libs.versions.toml            # 依赖版本目录
├── docs/                             # 项目文档
│   ├── PRD.md
│   ├── DEVELOPMENT.md
│   ├── TESTING.md
│   └── CONTRIBUTING.md
├── build.gradle.kts                  # 根构建文件
├── settings.gradle.kts
└── .gitignore
```

---

## 九、发布流程

### 9.1 发布检查清单

- [ ] develop 分支 CI 全部通过（单元测试 + Lint）
- [ ] 冒烟测试通过（8 项核心用例）
- [ ] 无 P0/P1 未解决 Bug
- [ ] 版本号已更新（versionCode + versionName）
- [ ] 更新 CHANGELOG.md
- [ ] 创建 PR: develop → main
- [ ] 合并后打 Tag

### 9.2 构建命令

```bash
# Debug 构建
./gradlew assembleDebug

# Release 构建（需要签名配置）
./gradlew assembleRelease

# 运行全部单元测试
./gradlew testDebugUnitTest

# Lint 检查
./gradlew lintDebug

# 覆盖率报告
./gradlew koverHtmlReport
```

---

## 十、AI 辅助开发规范

> 本项目使用 AI 辅助编码，以下规则确保 AI 生成代码的质量。

### 10.1 AI 生成代码要求

- 必须符合本文档所有规范（架构分层、命名、测试）
- 生成后必须编译通过
- 关键逻辑必须附带单元测试
- 禁止生成包含 `TODO`、`FIXME` 的代码提交到 develop
- AI 生成的 Compose 组件必须支持 Preview

### 10.2 AI 提交标注

AI 辅助生成的代码在 commit body 中标注：

```
feat(ai): 实现 AI 记账解析核心流程

AI-assisted: 核心逻辑由 AI 生成，已 review 并调整
```

### 10.3 AI 不可做

- ❌ 不可跳过测试直接提交
- ❌ 不可引入未在 libs.versions.toml 中管理的依赖
- ❌ 不可修改已稳定的接口签名（除非有明确需求）
- ❌ 不可生成超过 300 行的单文件

---

## 十一、验证交付流程（必须执行）

> 复盘教训：把"生成代码"等同于"完成功能"是最大的错误。以下流程强制执行。

### 11.1 每批开发后的验证清单

每次开发完成后，**必须依次通过**以下检查才能算"完成"：

```
开发完成
  → ① 编译检查（import/类型/签名/DI链路）
  → ② API 格式验证（DTO 是否与后端实际返回匹配）
  → ③ 导航链路检查（NavHost 参数 vs Screen 签名）
  → ④ 用户路径模拟（从打开 App 到完成操作的完整路径）
  → ⑤ PRD 功能对照（checklist 逐一打勾）
  → ⑥ 交付
```

### 11.2 编译检查规则

| 检查项 | 方法 |
|--------|------|
| NavHost 参数匹配 | grep 每个 Screen 的 `fun XxxScreen(` 签名，对照 NavHost 调用 |
| DI 完整性 | ViewModel 依赖 → Repository → RepositoryModule bind → NetworkModule provide |
| import 存在性 | 新文件的类名在引用处是否 import |
| Route 一致性 | Route.kt 每个路由在 NavHost 中都有 composable |
| AppDatabase 注册 | 新 Entity 是否加入 entities 数组 + 新 DAO 是否有 abstract 方法 |

### 11.3 API 格式验证规则

- **禁止假设后端返回格式**——必须对照后端文档或实际 curl 响应
- 后端返回 `{ data: { items: [...] } }` 时，必须创建包装 DTO，禁止直接用 `List<T>`
- 字段可空性（`null`）必须在 DTO 中用 `?` 标注 + 给默认值
- 每个新 API 对接后，首次联调必须确认 Moshi 反序列化不抛异常

### 11.4 用户路径模拟（6 个必测场景）

每次发版前必须模拟通过：

| # | 场景 | 验证点 |
|---|------|--------|
| 1 | 首次打开 App | 进服务器配置 → 登录 → 首页有数据 |
| 2 | AI 记账 | 输入 → 解析 → 确认 → 今日流水更新 |
| 3 | 手动记账 | 填写 → 保存 → 明确反馈 → 连续模式清空 |
| 4 | 清后台重开 | 直接进首页，不重新登录 |
| 5 | 退出登录 | Token 清除 → 下次打开在登录页 |
| 6 | 每个按钮点一遍 | 无死按钮（onClick 为空）、无闪退 |

### 11.5 交互体验底线要求

| 操作 | 必须有的反馈 |
|------|-------------|
| 网络请求成功 | Toast / 数据刷新 / 页面跳转 |
| 网络请求失败 | Snackbar + 错误信息 + 重试按钮 |
| 破坏性操作（删除/退出） | 确认弹窗 或 撤销机制 |
| 加载中 | Loading 指示器（CircularProgress / Shimmer） |
| 列表为空 | 空状态插图 + 引导文案 |
| 保存成功 | 明确的视觉反馈（Toast/动画/页面跳转） |

### 11.6 禁止事项

- ❌ 禁止 `onClick = {}` 空回调上线——没做的功能用 "开发中" 提示或不显示入口
- ❌ 禁止 `// TODO` 提交到 develop——未完成的功能不上线
- ❌ 禁止假设后端返回格式——必须验证
- ❌ 禁止"文件存在=功能完成"——必须走完验证清单
- ❌ 禁止不经 Review 直接交付

---

*文档结束*


## 十一、通知监听 & AI 记账经验

> 通知自动记账是本项目最易踩坑的模块，以下为实战总结。

### 11.1 通知文本解析

- **金额可能分散在任意字段**：`EXTRA_TITLE` / `EXTRA_TEXT` / `EXTRA_BIG_TEXT` / `EXTRA_SUB_TEXT` / `EXTRA_INFO_TEXT`。必须合并全文再解析，只取 `EXTRA_TEXT` 会导致金额解析为 0。
  ```kotlin
  val fullText = listOf(title, text, bigText, subText, infoText)
      .filter { it.isNotBlank() }.distinct().joinToString(" ")
  ```
- 合并全文后，务必确保后续所有引用都用 `fullText`，不要残留旧的 `text`/`title` 变量（编译不报错但逻辑错误）。

### 11.2 AI 兜底调用要「双重拦截」

正则命中优先，正则失败才走 AI。但 AI 调用有成本+隐私风险（会把通知全文发给后端），微信/短信白名单会捕获大量普通聊天，必须预筛：

```kotlin
// 入库前 & AI 调用前都要判断「支付特征」
val hasDigit = text.any { it.isDigit() }
val hasPaymentSignal = text.contains(Regex(
    "[¥￥$]|元|支付|付款|收款|到账|消费|交易|转账|红包|退款|扣款|余额|账单|还款"
))
if (!hasDigit || !hasPaymentSignal) return  // 直接丢弃，不存 raw 不调 AI
```

否则微信聊天会以 `raw` 状态刷屏通知中心（`observePending` 查 `status IN ('raw','parsed')`），且含数字的聊天会白白触发后端 AI。

### 11.3 隐私：sourceDetail 不要存全文

`PendingTransactionEntity.sourceDetail` 会同步到后端。存来源名（"微信支付"）而非 `fullText`（可能含私聊内容）。三处构造入口（Service 静默入库、ViewModel confirmItem、confirmAll）字段应保持一致。

### 11.4 批量确认要跳过无金额记录

`confirmAll` 必须跳过 `parsedAmount <= 0` 的记录，否则会生成 0 元账单同步到后端（资损/脏数据）。单条确认走编辑对话框（有金额校验），批量确认无对话框保护，需代码兜底。

### 11.5 收支类型不要硬编码

确认通知标题、图标等不要写死"支出"。parser/AI 会产出 income（收款到账、退款），需按 `type` 动态显示，否则收入被标成支出。

### 11.6 通知点击跳转

- `MainActivity` 必须声明 `android:launchMode="singleTop"`，否则点击通知会重建 Activity 而非走 `onNewIntent`，丢失返回栈。
- `navigate_to` extra 要「一次性消费」：NavHost 的 `LaunchedEffect(navigateTo)` 处理后回调清空，否则解锁/重建时会误跳，且相同值再次点击（key 不变）不触发。

### 11.7 MIUI 测试限制

- MIUI 对 `adb shell cmd notification post` 发的通知**不分发**给 `NotificationListenerService`，无法用 adb 造测通知。用 App 内测试按钮发本地通知，或用真实支付通知验证。
- `MainActivity` 用 `BiometricPrompt`（应用锁）时必须继承 `FragmentActivity`，`ComponentActivity` 会崩溃。

---
