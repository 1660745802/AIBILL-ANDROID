# 通知记账规则云控 + APK 自动更新

## 概述

| 流程 | 脚本 | 默认服务器 |
|---|---|---|
| 通知记账规则下发 | `update_rules.sh` | `http://localhost:3000` |
| APK 上传 + 注册更新版本 | `upload_apk.sh` | `http://localhost:3000` |

两个脚本默认都指向 `http://localhost:3000`，可通过 `AIBILL_SERVER_URL` 环境变量覆盖到远程服务器。

---

## 1. 更新通知记账规则（update_rules.sh）

无需发版即可调整通知排除词、关键词、包名白名单等。

### 用法

```bash
# 默认指向 localhost:3000
export AIBILL_ADMIN_PASS=你的密码
./scripts/update_rules.sh scripts/rules.json

# 远程服务器
export AIBILL_SERVER_URL=http://your-billserver:3000
export AIBILL_ADMIN_PASS=你的密码
./scripts/update_rules.sh
```

### 流程

```
1. admin 登录 → JWT
2. 读取当前生效版本
3. POST /api/admin/notification-rules 推送新版本
4. PUT /api/admin/notification-rules/:id/activate 激活
5. 验证客户端下次启动生效
```

### 手动 API（高级）

```bash
# 查看当前生效版本
curl $AIBILL_SERVER_URL/api/config/notification-rules

# 创建新版本（需 admin token）
POST /api/admin/notification-rules
Authorization: Bearer <token>
Content-Type: application/json
{ ... rules.json 内容 ... }

# 激活指定版本
PUT /api/admin/notification-rules/:id/activate
```

---

## 2. 上传 APK + 注册更新版本（upload_apk.sh）

发版流程：build → 上传 APK 到 billserver → 客户端自动检测更新。

### 前置条件

1. billserver 已实现：
   - `GET /api/app/update?versionName=<>&versionCode=<>&platform=android`（公开查询）
   - `POST /api/admin/updates`（admin 上传，multipart/form-data）
2. admin 账号存在
3. `./gradlew assembleRelease` 已构建

### 用法

```bash
# 默认指向 localhost:3000，APK 路径默认 app/build/outputs/apk/release/app-release.apk
export AIBILL_ADMIN_PASS=你的密码
./scripts/upload_apk.sh

# 远程服务器 + 自定义 APK 路径
export AIBILL_SERVER_URL=http://your-billserver:3000
export AIBILL_ADMIN_PASS=你的密码
export AIBILL_APK_PATH=/path/to/app-release.apk
./scripts/upload_apk.sh
```

### 强制升级

```bash
export AIBILL_FORCE_UPDATE=true
./scripts/upload_apk.sh
```

### 流程

```
1. 从 app/build.gradle.kts 读取 versionName / versionCode
2. admin 登录 → JWT
3. POST /api/admin/updates 上传 APK + 元数据（multipart）
   字段: apk_file / version_name / version_code / changelog / force_update
4. 验证 GET /api/app/update 返回的新版本信息
```

### 客户端行为

- 定时检查：`UpdateCheckWorker` 每 24h 调 `GET /api/app/update`
- 手动检查：Settings → 检查更新（先弹确认对话框，用户点"立即更新"才下载）
- 通知按钮：定时检查到更新时发通知，含「立即更新」「稍后」两个按钮
- 失败降级：billserver 不可达时自动 fallback 到 GitHub Release

---

## 规则结构说明（rules.json）

| 模块 | 用途 | 常见修改场景 |
|------|------|-------------|
| `nls` | 通知监听排除层 | 新增支付App、调整微信/支付宝放行规则 |
| `a11y` | 无障碍识别层 | 新增/移除电商App监听、调整排除词 |
| `sms` | 短信垃圾过滤 | 新增营销短信关键词 |
| `source_mapping` | 包名→友好名称 | 新增App的展示名称 |
| `processor` | AI处理器参数 | 调整评分窗口/去重时间/营销后缀 |

## 客户端拉取规则的行为

- **拉取时机**：App 冷启动时异步拉取
- **缓存**：内存 → SharedPreferences → 硬编码默认值
- **ETag**：版本未变时返回 304，不重复下载
- **容错**：网络失败/解析失败静默回退到默认值，不影响使用