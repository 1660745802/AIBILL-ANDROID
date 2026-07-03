# 💰 AIBILL Android

AI 驱动的智能记账 Android 原生应用，复用 [AIBILL](https://github.com/1660745802/AIBILL) 后端 API。

## ✨ 功能

- 🤖 AI 记账：自然语言输入，AI 自动解析
- ✏️ 手动记账：计算器键盘 + 连续记账模式
- 🔔 通知自动记账：监听微信/支付宝支付通知
- 📊 统计分析：趋势图 + 分类排行
- 💰 预算管理：超支自动提醒
- 📴 离线可用：无网络时本地暂存，联网后自动同步
- 🌙 深色模式：跟随系统 / 浅色 / 深色

## 🏗️ 技术栈

- Kotlin + Jetpack Compose + Material 3
- MVVM + Clean Architecture
- Hilt (DI) + Room (本地存储) + Retrofit (网络)
- WorkManager (后台同步) + NotificationListenerService (通知监听)

## 🚀 快速开始

1. 克隆本仓库
2. Android Studio 打开项目
3. 配置后端服务器（需先部署 [AIBILL 后端](https://github.com/1660745802/AIBILL)）
4. Run 安装到设备

## 📖 文档

- [产品需求文档 (PRD)](docs/PRD.md)
- [技术设计文档](docs/DEVELOPMENT.md)
- [测试方案](docs/TESTING.md)
- [开发规范](docs/CONTRIBUTING.md)
- [后端 API 需求](docs/API_REQUIREMENTS.md)
