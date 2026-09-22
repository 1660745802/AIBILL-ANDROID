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
| 语言 | Kotlin 17 |
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
│   └── remote/        # Retrofit (9 API) + Interceptor + SafeApiCall
├── presentation/      # UI 层
│   ├── navigation/    # Route + NavHost + BottomNavBar（类型安全路由）
│   ├── theme/         # Material 3 主题
│   ├── widget/        # Glance 桌面小组件
│   └── ui/            # 10 个功能模块（home/transactions/statistics/...）
├── service/           # 后台服务（NotificationMonitor/Accessibility/SmsReceiver/SyncWorker/...）
└── util/              # 全局工具（NetworkMonitor/AppLogger/通知解析/...）
```

## 🔀 开发流程

### 分支策略

```
main          ← 生产分支，PR + CI 通过才能合并
└── develop   ← 开发主线
     ├── feat/*     ← 新功能
     ├── fix/*      ← Bug 修复
     └── refactor/* ← 重构
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

## 📖 文档

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — 架构设计、API 契约、通知监听 v3、规范

## 🛠️ 工具与脚本

- `scripts/rules.json` + `scripts/update_rules.sh` — 通知解析规则云控（详见 [scripts/README.md](scripts/README.md)）