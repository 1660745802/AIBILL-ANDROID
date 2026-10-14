# 贡献指南

> 开发流程、测试、代码与 UI 规范、发布、AI 协作。系统是什么见 [ARCHITECTURE.md](ARCHITECTURE.md)。

---

## 一、开发流程

分支：`main`（生产，PR + CI 通过才合并）· `feat/*` · `fix/*` · `refactor/*`。

Commit 用 Conventional Commits：`<type>(<scope>): <subject>`，
type = `feat|fix|docs|style|refactor|test|chore|perf`，
scope = `ui|network|db|auth|transaction|ai|sync|notification|stats|widget|gradle`。

### 验证清单（每批必跑）

1. `./gradlew assembleDebug` 通过
2. `./gradlew testDebugUnitTest` 通过
3. 手动冒烟：①首次打开 ②AI 记账 ③手动记账 ④清后台重开不退登 ⑤退出登录 ⑥每个按钮点一遍
4. 关键改动有测试覆盖

### 版本号是硬规矩

**默认不动 `versionCode` / `versionName`。** 只有维护者明确说「发版」才改，
改前必须把打算用的版本号发给维护者确认；「可以 / ok」这类回答**不构成授权**。
bump 单独提 `chore: bump versionCode X → Y`，不要夹在 fix/feat 里。

> 原因：tag 打上去之后该版本就不能再含后续 commit。看到「tag 已存在」而顺手 bump，
> 会把一批未经确认的修复卷进已发布版本，绕过发布节奏。

---

## 二、测试

工具：JUnit5 + MockK + Turbine + Robolectric + MockWebServer + Room Testing + Compose UI Test + Kover。

| 覆盖目标 | 门槛 |
|---|---|
| 工具类（AmountUtils / NotificationParser 等） | 100% |
| ViewModel 核心 | ≥ 70% |
| Repository | ≥ 60% |
| 每个主页面 | 至少 1 个冒烟用例 |

**门禁命令**

```bash
./gradlew :app:testDebugUnitTest        # 单测
./gradlew :app:koverVerifyDebug         # 覆盖率门槛（只认 debug 变体）
./gradlew :app:detekt
./gradlew lintDebug
```

⚠️ 用 `koverVerifyDebug`,**别用 `koverVerify`**(跨变体聚合,会把 release 的 2 个已知失败
拖进来，见 [ARCHITECTURE.md §12.3](ARCHITECTURE.md#123-测试基建)）。同理 `koverHtmlReportDebug`。
测试必须用 JDK 17：`JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew …`（JDK 25 会报
`IllegalArgumentException: 25.0.4.1`）。

**测试写法**：打 interface，不打实现细节；断言「顺序 + 关联键」这类不变量，
而不是逐字段复述实现；不要把生产判据抄进测试文件（改生产不会红，护栏就失效了）。

---

## 三、代码规范

- **分层**：domain 禁止依赖 `android.*` / `androidx.*` / `data.*`；Entity 不出 data 层；
  跨层只走 Repository 接口与 UseCase；ViewModel 不直接 new，走 Hilt。
- **命名**：包全小写 · 类 PascalCase · 函数/变量 camelCase · 常量 UPPER_SNAKE ·
  Compose 组件 PascalCase + `Screen` 后缀 · ViewModel + `ViewModel` 后缀 · 资源 snake_case。
- **Compose**：每个 Screen 有 `@Preview`；列表 `LazyColumn` + `key()` + `contentType`；
  状态用 `collectAsStateWithLifecycle()`；一次性事件用 `Channel` + `receiveAsFlow()`，
  不用 `StateFlow`；大 Composable 拆 `remember` / `derivedStateOf`。
- **ViewModel**：`@HiltViewModel` + `@Inject constructor`；暴露 `StateFlow<UiState>`；
  不持有 Composable / Context；协程在 `viewModelScope`。
- **网络**：API 一律 `suspend fun → Result<T>`；401 不在 Repository 处理，走 `AuthInterceptor`。
- **数据库**：Room schema export 开启；简单变更 AutoMigration、复杂手写；字段显式 nullable；
  不在主线程做 DB I/O。
- **金额**：存储/传输/计算一律整数（分）；显示走 `AmountUtils`；**禁止 `Double`/`Float` 参与计算**。
- **日期**：一律 `java.time`。**禁止 `SimpleDateFormat(pattern, Locale.getDefault())`** 落库日期
  （泰语 locale 会写出佛历年）。

## 四、UI 规范（改 UI 前必读）

**唯一来源是 `presentation/theme/`**，文档不复述数值，用之前去那里查：
`Tokens`（间距/圆角/图标/触摸目标/动效时长）· `Colors`（语义色）· `Type`（排版）· `Shape`。

几条必须知道的：

- **语义色是信息契约，不是装饰**：红=支出、绿=收入、必须区分明暗（同色在深底上对比度不足、
  在浅底上过饱和）。`MaterialTheme.semantic` 自动跟随明暗。
- **所有金额走 `AmountTypography.*`**，它们开了 `tnum`（等宽数字）。比例数字会让同列金额
  左右跳动，用户无法竖着比较——记账类 App 最容易忽略却最影响可用性的细节。
- **层级 = 圆角递增**：行/按钮 `sm~md` → 卡片 `lg` → 汇总卡 `xl` → Sheet `xxl`。
- **页面卡片阴影为 0**，用色调分层；只有 sheet/fab/dialog 有 elevation。
- **卡片预算**：一个页面不超过 3-4 张卡片，多了改用分组列表。
- **动态取色默认关闭**：Material You 取色会破坏「红=支出」的语义契约。
- **动效只用于回应用户动作**（按下、展开、保存成功、转场），不要给每行加装饰性淡入。
- **空/错/加载态都要有**：`EmptyState`（矢量图 + 邀请行动）/ `LoadingState` / Snackbar + 重试。
- **文案底线**：不指引用户做不到的动作，不主张不确定的同步状态
  （失败就说「没记上」，文案里不要出现「同步 / 队列」字眼——不存在同步队列）。

改完自查：明暗两套主题各看一遍 · 真机字号/语言环境 · 金额列对齐 · 空态与错误态 ·
对比度 · 触摸目标 ≥ 40dp。

---

## 五、发布

脚本在 `scripts/`，默认服务器 `http://localhost:3000`，用 `AIBILL_SERVER_URL` 覆盖；
`AIBILL_ADMIN_USER` / `AIBILL_ADMIN_PASS` 是 admin 鉴权。

| 脚本 | 用途 |
|---|---|
| `upload_apk.sh` | 构建 release APK 并发布到 billserver（`--github` 双发布，`--dry-run` 演练） |
| `check_update.sh` | 模拟客户端查/下载更新，验证发布结果 |
| `update_rules.sh` | **仅本地开发/联调**：把指定的完整规则文件（格式同 `server/config/rules.json`）推到 billserver 并激活。⚠️ 规则内容真源在服务端的 `server/config/rules.json`——生产上线改那份并重启服务（启动时自动校验 + 内容 hash 派生版本后激活）；本脚本推的试验规则会被下次重启的文件内容覆盖（期望语义） |

**发版前**：跑完验证清单 + `./gradlew testDebugUnitTest` · 确认版本号已与维护者核对 ·
Release 构建需根目录有 `keystore.properties`（不入 git）。

**回滚**：服务端 `PUT /api/admin/updates/:id` 停用该版本，客户端下次检查更新即回落到上一版本；
GitHub 侧删 tag 与 release。发版后用 `check_update.sh` 验证一次。

---

## 六、AI 辅助开发

AI 生成的代码必须同样符合上述全部规范：编译通过、关键逻辑有测试、不留 `TODO`/`FIXME`、
Compose 组件支持 `@Preview`、不引入 `libs.versions.toml` 外的依赖、不改已稳定接口签名、
单文件不超过 300 行。commit body 标注 `AI-assisted: …`。

**AI 不可做**：跳过测试、未经确认改版本号、把生产判据抄进测试、为测试给生产代码开 `internal` 后门
（该做的是把逻辑提进可注入的 module）。
