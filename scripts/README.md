# scripts 目录

app 项目内用于和 billserver 后端交互的运维脚本集合。默认服务器地址 `http://localhost:3000`，可用环境变量 `AIBILL_SERVER_URL` 覆盖。

## 脚本清单

| 脚本 | 方向 | 用途 | 鉴权 |
|---|---|---|---|
| `update_rules.sh` | 推送 | 把 `rules.json` 的通知规则推到 billserver 并激活 | admin |
| `upload_apk.sh` | 推送 | 构建 release APK + 上传到 billserver + 自动激活 | admin |
| `check_update.sh` | 拉取 | 在 app 项目内模拟客户端查/下载更新 | 无（公开） |

## update_rules.sh — 推送通知解析规则

云控通知记账的关键词、排除词、商家映射等。客户端冷启动自动拉取最新规则，失败时 fallback 硬编码默认值。

```bash
# 1. 编辑 scripts/rules.json（注意递增 version 字段）
# 2. 执行推送
export AIBILL_SERVER_URL=http://your-server:3000
export AIBILL_ADMIN_PASS=your-admin-pass
./scripts/update_rules.sh scripts/rules.json
```

详情参见后端文档：`GET /api/config/notification-rules`、`POST /api/admin/notification-rules`。

## upload_apk.sh — 发布 APK 到 billserver

构建 release APK 并上传到 billserver，让客户端走 billserver 自托管下载（不走 GitHub Release）。

```bash
# 前置：在根目录配置 keystore.properties（git 忽略）
export AIBILL_SERVER_URL=http://your-server:3000
export AIBILL_ADMIN_PASS=your-admin-pass

# 可选参数
# AIBILL_FORCE_UPDATE=true   强制升级（客户端弹不可关闭通知）
# AIBILL_APK_PATH=/x/y.apk   指定已构建好的 APK（跳过 ./gradlew assembleRelease）

./scripts/upload_apk.sh
```

**流程**：`./gradlew assembleRelease` → 读 `versionName/versionCode` → `POST /api/admin/updates`（multipart 上传）→ billserver 自动激活。

## check_update.sh — 在 app 项目内验证/下载更新

不构建 APK，直接模拟客户端调 `GET /api/app/update` 验证服务端返回，可选下载最新 APK 到 `app/build/outputs/apk/updates/`。

```bash
# 仅检查（不下载）—— 适合开发时调试服务端接口
./scripts/check_update.sh

# 检查 + 下载新版本（has_update=true 才下载）
./scripts/check_update.sh --download

# 强制下载最新版（忽略 has_update，回退测试用）
./scripts/check_update.sh -d -f
```

下载完成后可手动安装：
```bash
adb install -r app/build/outputs/apk/updates/aibill-1.3.0-20260922_223000.apk
```

脚本会自动校验下载文件是有效 APK（zip magic `PK\x03\x04`），失败时打印前 200 字节便于排查（HTML 错误页 / JSON 错误响应）。

## 客户端降级 fallback

客户端 [UpdateManager](../app/src/main/java/com/aibill/android/service/UpdateManager.kt) 的双源策略：

1. billserver 可达 → 信任 billserver 判定
   - `has_update=true` → 下载该版本
   - `has_update=false` → 显示"已是最新"，**不** fallback GitHub
2. billserver 不可达（网络/5xx）→ fallback GitHub Release
3. 全部失败 → 返回 null

这意味着 billserver 临时挂掉时，旧版 App 仍能从 GitHub Release 升级；billserver 恢复后自动回到自托管优先链路。

## 公共环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `AIBILL_SERVER_URL` | `http://localhost:3000` | billserver 地址 |
| `AIBILL_ADMIN_USER` | `admin` | admin 账号（update_rules / upload_apk 用） |
| `AIBILL_ADMIN_PASS` | （必填） | admin 密码 |
| `AIBILL_APK_PATH` | `app/build/outputs/apk/release/app-release.apk` | upload_apk 的 APK 路径 |
| `AIBILL_FORCE_UPDATE` | `false` | upload_apk 是否标记为强制升级 |

Windows (PowerShell) 用 `$env:AIBILL_SERVER_URL = "..."` 设置变量。