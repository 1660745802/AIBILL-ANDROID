# 发布流程

> AIBILL Android 的发版流程、脚本用法、回滚策略的**唯一权威**。从 README 分离出来是为了避免发布信息分散在多份文档里漂移。

---

## 一、脚本一览

`scripts/` 下三个脚本默认服务器 `http://localhost:3000`，可用 `AIBILL_SERVER_URL` 覆盖。

| 脚本 | 方向 | 用途 | 鉴权 |
|---|---|---|---|
| `upload_apk.sh` | 推送 | 构建 release APK + 发布到 billserver（可选 GitHub） | admin |
| `check_update.sh` | 拉取 | 在 app 项目内模拟客户端查/下载更新 | 无（公开） |
| `update_rules.sh` | 推送 | 把 `rules.json` 的通知规则推到 billserver 并激活 | admin |

### 公共环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `AIBILL_SERVER_URL` | `http://localhost:3000` | billserver 地址 |
| `AIBILL_ADMIN_USER` | `admin` | admin 账号 |
| `AIBILL_ADMIN_PASS` | （必填） | admin 密码 |
| `AIBILL_APK_PATH` | `app/build/outputs/apk/release/app-release.apk` | upload_apk 的 APK 路径 |
| `AIBILL_FORCE_UPDATE` | `false` | 标记为强制升级 |
| `AIBILL_FORCE_GITHUB` | `false` | 覆盖已存在的 GitHub tag |

---

## 二、`upload_apk.sh` — 发布 APK

### 用法

```bash
export AIBILL_SERVER_URL=http://your-server:3000
export AIBILL_ADMIN_PASS=xxx

./scripts/upload_apk.sh              # 仅发 billserver（默认）
./scripts/upload_apk.sh --github     # billserver + GitHub 双发布
./scripts/upload_apk.sh --github-only # 仅发 GitHub（跳过 billserver）
./scripts/upload_apk.sh --dry-run    # 演练，不实际执行
```

### 流程

```
[阶段 A] billserver 上传 + 激活
  A1. POST /api/auth/login（admin）
  A2. POST /api/admin/updates（multipart：apk + 元数据）
  A3. GET /api/app/update 验证激活

[阶段 B] GitHub Release（--github / --github-only）
  B1. 检测 gh CLI
  B2. 检测 gh 登录
  B3. 检查 tag 是否占用（AIBILL_FORCE_GITHUB=true 强制覆盖）
  B4. git tag + push
  B5. gh release create
```

### 客户端双源策略

客户端通过 [UpdateManager](app/src/main/java/com/aibill/android/service/UpdateManager.kt) 拉取更新，优先级：

1. **billserver 可达** → 信任 billserver 判定（`has_update=false` **不** fallback GitHub）
2. **billserver 不可达** → fallback GitHub Release
3. **全部失败** → 返回 null（"已是最新"或"获取失败"）

---

## 三、`check_update.sh` — 验证服务端

不构建 APK，直接模拟客户端调 `GET /api/app/update` 验证服务端，可选下载最新 APK 到 `app/build/outputs/apk/updates/`。

```bash
./scripts/check_update.sh                # 仅检查（不下载）
./scripts/check_update.sh --download     # 检查 + 下载新版本
./scripts/check_update.sh -d -f          # 强制下载最新版（无视 has_update）
```

下载完成后 `adb install -r <path>` 安装。脚本自动校验 APK magic（`PK\x03\x04`），失败时打印前 200 字节排查。

---

## 四、`update_rules.sh` — 推送通知规则

云控通知记账的关键词/排除词/商家映射。客户端冷启动自动拉取（带 ETag/304），失败 fallback 硬编码默认值。

```bash
export AIBILL_ADMIN_PASS=xxx
./scripts/update_rules.sh scripts/rules.json
```

---

## 五、何时发版？

- `feat/*` 合到 main 后：正常发版
- 紧急 bug 修复：提 PR + 直接从 main 发

## 六、前置清单

1. ✅ `app/build.gradle.kts`：`versionCode` +1、`versionName` 已 bump
2. ✅ 单元测试 `./gradlew testDebugUnitTest` 通过
3. ✅ APK 在真机/模拟器手动验证核心路径（AI记账 / 通知记账 / 同步）
4. ✅ `git status` 干净，所有改动已 commit

## 七、决策：发布到哪？

| 场景 | 命令 |
|---|---|
| **普通版本**（内网优先） | `./scripts/upload_apk.sh`（仅 billserver） |
| **双发布**（GitHub 公开 + billserver 内网） | `./scripts/upload_apk.sh --github` |
| **紧急回滚**（不想动 GitHub 公开记录） | `./scripts/upload_apk.sh` |
| **仅 GitHub**（billserver 临时不可用） | `./scripts/upload_apk.sh --github-only` |

## 八、流程示意

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

## 九、发布后验证

```bash
# 1. 验证 billserver 接口（不需要 admin token）
AIBILL_SERVER_URL=https://api.xxx.com ./scripts/check_update.sh

# 2. 真机/模拟器：Settings → 检查更新 → 应该看到新版本
```

## 十、回滚

| 渠道 | 回滚方式 |
|---|---|
| **billserver** | 重新上传旧版本 APK（versionCode 必须小于当前激活版本，或删除 app_updates 表对应行） |
| **GitHub** | `git tag -d vX.Y.Z && git push origin :refs/tags/vX.Y.Z && gh release delete vX.Y.Z` |

⚠️ 客户端会优先信任 billserver：billserver 收到的最新版是回滚目标，GitHub 才会兜底成功。

---

## 十一、强制升级

`upload_apk.sh` 默认发布"温和更新"（用户可点稍后）。当服务端需要强制升级时（如安全修复）：

```bash
AIBILL_FORCE_UPDATE=true ./scripts/upload_apk.sh
```

客户端行为（[UpdateNotifier](../app/src/main/java/com/aibill/android/util/UpdateNotifier.kt)）：
- 通知：隐藏「稍后」按钮，设为 Ongoing，标题改「必须升级到 X.Y.Z」
- Settings 页 AlertDialog：强制升级时隐藏「稍后」按钮，禁止 dismiss
- UpdateInstallReceiver：若强制升级则忽略用户的 ACTION_DISMISS

## 十二、签名密钥

仓库根目录准备 `keystore.properties`（已在 `.gitignore`）：

```properties
storeFile=/absolute/path/to/release.keystore
storePassword=<store-pass>
keyAlias=<alias>
keyPassword=<key-pass>
```

生成签名：

```bash
keytool -genkey -v \
  -keystore release.keystore \
  -alias aibill \
  -keyalg RSA -keysize 4096 -validity 10000
```

⚠️ **请妥善备份 `release.keystore`，丢失则无法升级覆盖旧版本。**

---

## 十三、AI 代理发布 Prompt

把下面 prompt 发给 pi / 类似 agent，让它按流程发布：

```text
请按 docs/RELEASE.md 发布新版本：

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
