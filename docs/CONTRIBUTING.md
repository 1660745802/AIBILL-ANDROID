# 贡献指南

> 开发流程、测试、编码规范、AI 辅助开发规范——所有参与代码改动必读。

---

## 一、开发流程

### 1.1 分支策略

```
main              ← 生产分支，PR + CI 通过才能合并
├── feat/*        ← 新功能
├── fix/*         ← Bug 修复
└── refactor/*    ← 重构
```

### 1.2 Commit 规范（Conventional Commits）

```
<type>(<scope>): <subject>

# type: feat | fix | docs | style | refactor | test | chore | perf
# scope: ui | network | db | auth | transaction | ai | sync | notification | stats | widget | gradle
```

示例：`feat(notification): 实现通知监听 v3 架构`

### 1.3 验证清单（每批开发后必跑）

1. 编译 `./gradlew assembleDebug` 通过
2. 单元测试 `./gradlew testDebugUnitTest` 通过
3. 模拟以下 6 个用户路径无闪退/死按钮：
   - ①首次打开 ②AI 记账 ③手动记账 ④清后台重开不退登 ⑤退出登录清 Token ⑥每个按钮点一遍
4. 关键改动有测试覆盖（ViewModel/Repository/Util）

---

## 二、测试规范

### 2.1 测试金字塔

```
        ╱  E2E  ╲          # 模拟用户路径（手动冒烟）
       ╱ 集成测试  ╲        # Repository + Room + API（MockWebServer）
      ╱  单元测试   ╲       # ViewModel / Util / UseCase
```

### 2.2 工具矩阵

| 层 | 工具 |
|---|---|
| 框架 | JUnit5 + MockK + Turbine + Robolectric |
| 网络 | MockWebServer |
| 数据库 | Room Testing |
| 异步 | kotlinx-coroutines-test |
| UI | Compose UI Test（debugImplementation） |
| 覆盖率 | Kover |

### 2.3 覆盖率目标

- 工具类（AmountUtils / NotificationParser / BankSmsPatterns）：100%
- ViewModel：核心 8 个 ≥ 70%
- Repository：≥ 60%
- UI 测试：每个主页面至少 1 个冒烟用例

### 2.4 提交门禁

- `develop` 分支 CI：单元测试 + Lint + koverCheck 全部通过
- Lint：`./gradlew lintDebug` 无 error

---

## 三、代码规范

### 3.1 命名规范

| 类型 | 规范 | 示例 |
|---|---|---|
| 包名 | 全小写 | `com.aibill.android.data.remote` |
| 类名 | PascalCase | `TransactionRepositoryImpl` |
| 函数/变量 | camelCase | `getTransactions` |
| 常量 | UPPER_SNAKE | `MAX_RETRY_COUNT` |
| Compose 组件 | PascalCase + Screen 后缀 | `HomeScreen`, `TransactionDetailScreen` |
| ViewModel | PascalCase + ViewModel 后缀 | `HomeViewModel` |
| 资源 | snake_case | `ic_notification.xml`, `theme_color_primary` |

### 3.2 架构层级规范

- **domain 层**禁止依赖 `android.*` / `androidx.*` / `data.*`
- **data 层**实现 domain 接口，不暴露 Entity 给 UI
- **presentation 层**通过 Hilt 注入 ViewModel，禁止直接 new
- 跨层通信仅通过 Repository 接口和 UseCase

### 3.3 Compose 规范

- 所有 Screen 必须有 `@Preview`
- 大型 Composable 拆 `remember` / `derivedStateOf`
- 列表用 `LazyColumn` + `key()` + `contentType`
- 用 `collectAsStateWithLifecycle()` 而非 `collectAsState()`
- 一次性事件用 `Channel` + `receiveAsFlow()`，不用 `StateFlow`

### 3.4 ViewModel 规范

- 注入用 `@HiltViewModel` + `@Inject constructor`
- 暴露 `StateFlow<UiState>`（`asStateFlow()`）+ 私有 `MutableStateFlow`
- 一次性事件用 `Channel<UiEvent>`
- 不持有 Composable / Context 引用
- 协程在 `viewModelScope` 启动

### 3.5 网络层规范

- 所有 API 返回 `suspend fun → Result<T>`，不用 `Call<T>`
- Repository 合并 remote + local：先网络再缓存，先缓存再网络，看场景
- 401 不在 Repository 处理，统一走 AuthInterceptor

### 3.6 数据库规范

- Room schema export 开启（`app/schemas/`）
- 起始 version = 1，简单变更 AutoMigration，复杂手写
- Entity 字段必须显式 nullable，DAO 用 suspend / Flow
- 不在主线程做 DB I/O（Room 自动调度）

### 3.7 金额处理规范

- **存储 / 传输 / 计算**：一律用整数（分）
- **UI 显示**：用 `AmountUtils.formatYuan()`（分→元，"32.00"）
- **UI 输入**：计算器键盘实时解析
- **禁止** `Double` / `Float` 参与金额计算

---

## 四、AI 辅助开发规范

> 项目已大量使用 AI 辅助生成代码，下述规则确保不引入隐性技术债。

- AI 生成代码必须符合本文档所有规范（架构分层、命名、测试）
- 生成后必须编译通过
- 关键逻辑必须附带单元测试
- 禁止生成含 `TODO` / `FIXME` 的代码提交到 develop
- AI 生成的 Compose 组件必须支持 `@Preview`
- AI 辅助生成的代码在 commit body 标注：`AI-assisted: 核心逻辑由 AI 生成，已 review 并调整`
- AI 不可做：
  - 跳过测试
  - 引入未在 `libs.versions.toml` 中管理的依赖
  - 修改已稳定接口签名
  - 生成超过 300 行的单文件

---

## 五、提交流程

1. 在 `feat/*` / `fix/*` / `refactor/*` 分支开发
2. 跑完验证清单（§1.3）
3. 推送并开 PR → `main`
4. CI 通过 + Code Review 通过 → 合并
5. 合并后按 [RELEASE.md](./RELEASE.md) 流程发版（若是 `feat/*` 合并或紧急修复）
