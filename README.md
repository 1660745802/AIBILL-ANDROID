# 💰 AIBILL Android

AI 驱动的智能记账 Android 原生应用，复用 [AIBILL 后端](https://github.com/1660745802/AIBILL) API。
让记账像发微信一样简单——说一句、收一条通知、账就记好了。

## ✨ 核心特性

- 🤖 **AI 记账**：自然语言输入（"午饭32"），后端 AI 自动解析金额/分类
- ✏️ **手动记账**：计算器键盘 + 连续记账模式
- 🔔 **通知自动记账**：监听微信/支付宝/银行通知，自动识别并入账（项目核心差异化能力）
- 📊 **统计分析**：趋势图 + 分类排行（Vico 图表）
- 📴 **离线可用**：无网络手动记账不中断，联网后 WorkManager 自动同步
- 🌙 **深色模式**：跟随系统 / 浅色 / 深色
- 🧠 **智能分类学习**：AI 解析后自动学习商家→分类，稳态 60-70% 命中跳过 AI
- 🏠 **桌面小组件**：快速记账 + 月度汇总
- 🔒 **应用锁**：Biometric 验证，支持隐藏最近任务

## 🏗️ 技术栈

| 层 | 选型 |
|---|---|
| 语言 | Kotlin 1.9.x（JVM 17） |
| UI | Jetpack Compose + Material 3 |
| 架构 | MVVM + Clean Architecture（UI / Domain / Data） |
| DI | Hilt（含 `@HiltWorker`） |
| 本地存储 | Room v7（9 张表） + DataStore Preferences |
| 网络 | Retrofit + OkHttp + Moshi（codegen） |
| 后台 | WorkManager（5 个 Worker + 3 个健康检查） |
| 通知监听 | NotificationListenerService + 无障碍 + SMS 三渠道 |
| 图表 | Vico |
| 桌面 | Glance AppWidget |

## 🚀 快速开始

### 1. 部署后端

参考 [AIBILL 后端文档](https://github.com/1660745802/AIBILL) 启动 Node.js 服务（默认监听 `:3000`）。

### 2. 配置客户端

```bash
# 克隆代码
git clone <repo> && cd billapp

# 配置服务器地址（可选，App 启动后也可在「服务器配置」页输入）
echo "server.url=http://10.0.2.2:3000" > local.properties  # 模拟器访问宿主机
# 真机调试：将 IP 换成你后端机器的内网 IP
```

### 3. 编译运行

```bash
./gradlew assembleDebug          # 编译 Debug APK
./gradlew installDebug           # 安装到已连接设备
./gradlew testDebugUnitTest      # 运行单元测试
./gradlew koverHtmlReport        # 覆盖率报告
```

Release 构建需在根目录准备 `keystore.properties`（git 忽略）。

## 📂 目录结构

```
app/src/main/java/com/aibill/android/
├── di/                # Hilt 模块（Network/Database/Repository/Work）
├── domain/            # 业务逻辑层（纯 Kotlin）
│   ├── model/         # Transaction / Category / User / Result
│   ├── repository/    # 7 个 Repository 接口
│   └── usecase/       # CategoryLearningEngine / StreakTracker
├── data/              # 数据层
│   ├── local/         # Room (9 Entity + 9 DAO) + DataStore
│   └── remote/        # Retrofit (10 API) + Interceptor + SafeApiCall
├── presentation/      # UI 层
│   ├── navigation/    # Route + NavHost + BottomNavBar（类型安全路由）
│   ├── theme/         # Material 3 主题
│   ├── widget/        # Glance 桌面小组件
│   └── ui/            # 10 个功能模块（home/transactions/statistics/...）
├── service/           # 后台服务（NotificationMonitor/Accessibility/SmsReceiver/SyncWorker/UpdateManager/...）
└── util/              # 全局工具（NetworkMonitor/AppLogger/通知解析/...）
```

## 🔀 开发流程

### 分支策略

```
main              ← 生产分支，PR + CI 通过才能合并
├── feat/*        ← 新功能
├── fix/*         ← Bug 修复
└── refactor/*    ← 重构
```

### Commit 规范（Conventional Commits）

```
<type>(<scope>): <subject>

# type: feat | fix | docs | style | refactor | test | chore | perf
# scope: ui | network | db | auth | transaction | ai | sync | notification | stats | widget | gradle
```

示例：`feat(notification): 实现通知监听 v3 架构`

### 验证清单（每批开发后必跑）

1. 编译 `./gradlew assembleDebug` 通过
2. 单元测试 `./gradlew testDebugUnitTest` 通过
3. 模拟以下 6 个用户路径无闪退/死按钮：①首次打开 ②AI 记账 ③手动记账 ④清后台重开不退登 ⑤退出登录清 Token ⑥每个按钮点一遍
4. 关键改动有测试覆盖（ViewModel/Repository/Util）

## 🛠️ 工具与脚本

`scripts/` 下三个脚本默认服务器 `http://localhost:3000`，可用 `AIBILL_SERVER_URL` 覆盖。

| 脚本 | 方向 | 用途 | 鉴权 |
|---|---|---|---|
| `update_rules.sh` | 推送 | 把 `rules.json` 的通知规则推到 billserver 并激活 | admin |
| `upload_apk.sh` | 推送 | 构建 release APK + 发布到 billserver（可选 GitHub） | admin |
| `check_update.sh` | 拉取 | 在 app 项目内模拟客户端查/下载更新 | 无（公开） |

**update_rules.sh** — 云控通知记账的关键词/排除词/商家映射。客户端冷启动自动拉取，失败 fallback 硬编码默认值。
```bash
export AIBILL_SERVER_URL=http://your-server:3000
export AIBILL_ADMIN_PASS=xxx
./scripts/update_rules.sh scripts/rules.json
```

**upload_apk.sh** — 发布 APK 到 billserver（UpdateManager 优先源），可选同时发 GitHub Release。
```bash
export AIBILL_SERVER_URL=http://your-server:3000
export AIBILL_ADMIN_PASS=xxx

./scripts/upload_apk.sh              # 仅发 billserver（默认）
./scripts/upload_apk.sh --github     # billserver + GitHub 双发布
./scripts/upload_apk.sh --github-only # 仅发 GitHub（跳过 billserver）

# 可选环境变量：
# AIBILL_FORCE_UPDATE=true   标记为强制升级（客户端弹不可关闭通知）
# AIBILL_FORCE_GITHUB=true   覆盖已存在的 GitHub tag
# AIBILL_APK_PATH=/x/y.apk   指定已构建好的 APK（跳过 ./gradlew assembleRelease）
```

**check_update.sh** — 不构建 APK，直接模拟客户端调 `GET /api/app/update` 验证服务端，可选下载最新 APK 到 `app/build/outputs/apk/updates/`。
```bash
./scripts/check_update.sh                # 仅检查（不下载）
./scripts/check_update.sh --download     # 检查 + 下载新版本
./scripts/check_update.sh -d -f          # 强制下载最新版（无视 has_update）
```
下载完成后 `adb install -r <path>` 安装。脚本自动校验 APK magic（PK\x03\x04），失败时打印前 200 字节排查。

**客户端双源策略**（[UpdateManager](app/src/main/java/com/aibill/android/service/UpdateManager.kt)）：
1. billserver 可达 → 信任 billserver 判定（`has_update=false` **不** fallback GitHub）
2. billserver 不可达 → fallback GitHub Release
3. 全部失败 → 返回 null（"已是最新"或"获取失败"）

**公共环境变量**：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `AIBILL_SERVER_URL` | `http://localhost:3000` | billserver 地址 |
| `AIBILL_ADMIN_USER` | `admin` | admin 账号 |
| `AIBILL_ADMIN_PASS` | （必填） | admin 密码 |
| `AIBILL_APK_PATH` | `app/build/outputs/apk/release/app-release.apk` | upload_apk 的 APK 路径 |
| `AIBILL_FORCE_UPDATE` | `false` | 标记为强制升级 |
| `AIBILL_FORCE_GITHUB` | `false` | 覆盖已存在的 GitHub tag |

## 📤 发布流程

### 何时发版
- `feat/*` 合到 main 后：正常发版
- 紧急 bug 修复：提 PR + 直接从 main 发

### 前置清单
1. ✅ `app/build.gradle.kts`：`versionCode` +1、`versionName` 已 bump
2. ✅ 单元测试 `./gradlew testDebugUnitTest` 通过
3. ✅ APK 在真机/模拟器手动验证核心路径（AI记账 / 通知记账 / 同步）
4. ✅ `git status` 干净，所有改动已 commit

### 决策：发布到哪？

| 场景 | 命令 |
|---|---|
| **普通版本**（内网优先） | `./scripts/upload_apk.sh`（仅 billserver） |
| **双发布**（GitHub 公开 + billserver 内网） | `./scripts/upload_apk.sh --github` |
| **紧急回滚**（不想动 GitHub 公开记录） | `./scripts/upload_apk.sh` |
| **仅 GitHub**（billserver 临时不可用） | `./scripts/upload_apk.sh --github-only` |

### 流程示意

```
$ ./scripts/upload_apk.sh --github
  ↓ 读 versionName/versionCode
  ↓ 提示输入 changelog
  ↓
  [阶段 A] billserver 上传 + 激活
  ↓
  [阶段 B] gh release create v1.3.0 app-release.apk
  ↓
  ✅ 客户端下次 Settings → 检查更新 即可看到 v1.3.0
```

### 发布后验证

```bash
# 1. 验证 billserver 接口（不需要 admin token）
AIBILL_SERVER_URL=https://api.xxx.com ./scripts/check_update.sh

# 2. 真机/模拟器：Settings → 检查更新 → 应该看到新版本
```

### 回滚

| 渠道 | 回滚方式 |
|---|---|
| **billserver** | 重新上传旧版本 APK（versionCode 必须小于当前激活版本，或删除 app_updates 表对应行） |
| **GitHub** | `git tag -d vX.Y.Z && git push origin :refs/tags/vX.Y.Z && gh release delete vX.Y.Z` |

⚠️ 客户端会优先信任 billserver：billserver 收到的最新版是回滚目标，GitHub 才会兜底成功。

## 🤖 AI 代理发布 Prompt

把下面 prompt 发给 pi / 类似 agent，让它按流程发布：

```text
请按 README "📤 发布流程" 节发布新版本：

1. 跑 `./gradlew assembleRelease` 构建 release APK
2. 校验 versionCode 是否已 bump（app/build.gradle.kts）
3. 跑 `./gradlew testDebugUnitTest` 确认通过
4. 询问我发布渠道：默认 billserver / 双发（--github）/ 仅 GitHub（--github-only）
5. 设置环境变量 AIBILL_SERVER_URL、AIBILL_ADMIN_PASS 后执行对应脚本
6. 用 `./scripts/check_update.sh` 验证服务端返回
7. 汇报：发布渠道、APK URL、billserver id、GitHub Release URL（如有）

约束：
- 不要修改 build.gradle.kts 之外的代码
- 失败时立即停止，不要尝试绕过
- changelog 提示时如实记录我输入的内容
```

## 📖 文档

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — 架构设计、API 契约、通知监听 v3、规范