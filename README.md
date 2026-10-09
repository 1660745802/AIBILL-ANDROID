# 💰 AIBILL Android

AI 驱动的智能记账 Android 原生应用，复用 [AIBILL 后端](https://github.com/1660745802/AIBILL) API。
让记账像发微信一样简单——说一句、收一条通知、账就记好了。

## ✨ 核心特性

- 🤖 **AI 记账**：自然语言输入（"午饭32"），后端 AI 自动解析金额/分类
- ✏️ **手动记账**：计算器键盘 + 连续记账模式
- 🔔 **通知自动记账**：监听微信/支付宝/银行通知，自动识别并入账（项目核心差异化能力）
- 📊 **统计分析**：趋势图 + 分类排行（原生 Canvas 绘制）
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
| 后台 | WorkManager（5 个 Worker：Sync / RulesSync / UpdateCheck / NlsHealth / A11yHealth） |
| 通知监听 | NotificationListenerService + 无障碍 + SMS 三渠道 |
| 图表 | 原生 Canvas 手绘（不引第三方图表库） |
| 桌面 | Glance AppWidget |

## 🚀 快速开始

### 1. 部署后端

参考 [AIBILL 后端文档](https://github.com/1660745802/AIBILL) 启动 Node.js 服务（默认监听 `:3000`）。

### 2. 配置客户端

```bash
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

Release 构建需在根目录准备 `keystore.properties`（已在 `.gitignore`）。

## 📂 目录结构

采用 Clean Architecture 三层 + DI/Service/Util 辅助模块。完整模块清单与设计见 [docs/ARCHITECTURE.md §2](docs/ARCHITECTURE.md)。

```
app/src/main/java/com/aibill/android/
├── di/        domain/    data/{local,remote}/
├── presentation/{navigation,theme,widget,ui}/
├── service/   util/
```

## 📖 文档

| 文档 | 用途 |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 架构设计：分层、模块、API 契约、技术决策、网络、同步、数据模型 |
| [docs/UI-DESIGN.md](docs/UI-DESIGN.md) | UI 设计规范：设计令牌、语义色、排版、共享组件、改 UI 前必读 |
| [docs/NOTIFICATION.md](docs/NOTIFICATION.md) | 通知监听 v3 架构 + 实战经验（核心差异化能力必读） |
| [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md) | 开发流程、测试、编码规范、AI 辅助开发规范 |
| [docs/RELEASE.md](docs/RELEASE.md) | 发布流程、脚本用法、回滚策略、AI 发布 Prompt |

## 📤 发布

```bash
# 发版前：bump versionCode/versionName + 跑 ./gradlew testDebugUnitTest
./scripts/upload_apk.sh                # 仅 billserver（默认）
./scripts/upload_apk.sh --github       # billserver + GitHub 双发布
./scripts/check_update.sh              # 验证服务端更新接口
```

详见 [docs/RELEASE.md](docs/RELEASE.md)。
