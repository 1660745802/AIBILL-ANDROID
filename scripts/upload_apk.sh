#!/bin/bash
# ===========================================
# AIBILL Android APK 上传 + 注册更新版本
# ===========================================
# 流程:
#   1. ./gradlew assembleRelease 构建 release APK
#   2. POST /api/admin/updates 上传 APK + 版本元数据
#   3. billserver 存储 APK（OSS/NAS）并设为 active
#   4. 客户端 GET /api/app/update 拉到新版本
#
# 前置条件:
#   1. billserver 已实现 GET /api/app/update + POST /api/admin/updates
#   2. 有 admin 账号（环境变量 AIBILL_ADMIN_PASS）
#   3. 安装了 curl 和 python3

set -e

# ===== 配置（全部默认指向 localhost）=====
SERVER_URL="${AIBILL_SERVER_URL:-http://localhost:3000}"
ADMIN_USER="${AIBILL_ADMIN_USER:-admin}"
ADMIN_PASS="${AIBILL_ADMIN_PASS:-}"
APK_PATH="${AIBILL_APK_PATH:-app/build/outputs/apk/release/app-release.apk}"

# ===== 校验 =====
if [ -z "$ADMIN_PASS" ]; then
  echo "❌ 请设置环境变量 AIBILL_ADMIN_PASS"
  echo "用法: AIBILL_ADMIN_PASS=xxx ./upload_apk.sh"
  exit 1
fi

if [ ! -f "$APK_PATH" ]; then
  echo "❌ APK 不存在: $APK_PATH"
  echo "提示: 先执行 ./gradlew assembleRelease 或设置 AIBILL_APK_PATH=/path/to/app-release.apk"
  exit 1
fi

# ===== 从 build.gradle.kts 读取版本号 =====
GRADLE_FILE="app/build.gradle.kts"
if [ ! -f "$GRADLE_FILE" ]; then
  echo "❌ 找不到 $GRADLE_FILE，请在项目根目录运行"
  exit 1
fi

VERSION_NAME=$(grep -E 'versionName\s*=\s*"' "$GRADLE_FILE" | head -1 | sed -E 's/.*"([^"]+)".*/\1/')
VERSION_CODE=$(grep -E 'versionCode\s*=\s*[0-9]+' "$GRADLE_FILE" | head -1 | sed -E 's/.*=\s*([0-9]+).*/\1/')
APK_SIZE=$(stat -c '%s' "$APK_PATH" 2>/dev/null || stat -f '%z' "$APK_PATH")

if [ -z "$VERSION_NAME" ] || [ -z "$VERSION_CODE" ]; then
  echo "❌ 无法从 $GRADLE_FILE 解析版本号"
  exit 1
fi

echo "=== 1. 上传 APK 元信息 ==="
echo "  版本: $VERSION_NAME (code=$VERSION_CODE)"
echo "  大小: $(($APK_SIZE / 1024 / 1024)) MB"
echo "  服务器: $SERVER_URL"
echo ""

# ===== 1. 登录 =====
echo "=== 2. 登录 admin 账号 ==="
TOKEN=$(curl -s -X POST "$SERVER_URL/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['token'])" 2>/dev/null \
  || echo "")

if [ -z "$TOKEN" ]; then
  echo "❌ 登录失败，请检查账号密码和服务器地址（默认 http://localhost:3000）"
  exit 1
fi
echo "✓ 登录成功"

# ===== 2. 上传 APK + 元数据 =====
echo ""
echo "=== 3. 上传 APK 到 billserver ==="
# multipart/form-data 字段:
#   - apk_file: 二进制 APK
#   - version_name / version_code / changelog / force_update: 表单字段
CHANGELOG_FILE=".changelog.tmp"
echo "请输入更新说明（changelog），可直接回车留空："
read -r CHANGELOG_INPUT
echo "$CHANGELOG_INPUT" > "$CHANGELOG_FILE"

FORCE_UPDATE_FLAG="${AIBILL_FORCE_UPDATE:-false}"

UPLOAD_RESULT=$(curl -s -X POST "$SERVER_URL/api/admin/updates" \
  -H "Authorization: Bearer $TOKEN" \
  -F "apk_file=@${APK_PATH};type=application/vnd.android.package-archive" \
  -F "version_name=${VERSION_NAME}" \
  -F "version_code=${VERSION_CODE}" \
  -F "changelog=<${CHANGELOG_FILE}" \
  -F "force_update=${FORCE_UPDATE_FLAG}")

rm -f "$CHANGELOG_FILE"

# 解析响应
NEW_UPDATE_ID=$(echo "$UPLOAD_RESULT" | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    print(d.get('data', {}).get('id', ''))
except Exception:
    print('')
" 2>/dev/null || echo "")

APK_URL=$(echo "$UPLOAD_RESULT" | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    print(d.get('data', {}).get('apk_url', ''))
except Exception:
    print('')
" 2>/dev/null || echo "")

if [ -z "$NEW_UPDATE_ID" ] || [ -z "$APK_URL" ]; then
  echo "❌ 上传失败:"
  echo "$UPLOAD_RESULT" | python3 -m json.tool 2>/dev/null || echo "$UPLOAD_RESULT"
  exit 1
fi
echo "✓ 已上传 (id=$NEW_UPDATE_ID, apk_url=$APK_URL)"

# ===== 3. 激活新版本（billserver 端在 POST 时自动设 active）=====
echo ""
echo "=== 4. 验证激活 ==="
ACTIVE_RESULT=$(curl -s "$SERVER_URL/api/app/update?versionName=${VERSION_NAME}&versionCode=${VERSION_CODE}&platform=android")

echo "$ACTIVE_RESULT" | python3 -c "
import sys, json
d = json.load(sys.stdin)
data = d.get('data', {})
print(f\"  hasUpdate:    {data.get('hasUpdate')}\")
print(f\"  latestVersion: {data.get('latestVersion')}\")
print(f\"  apkUrl:        {data.get('apkUrl')}\")
print(f\"  changelog:     {data.get('changelog', '')[:80]}...\")
" 2>/dev/null || echo "  (解析失败，手动验证)"

echo ""
echo "🎉 APK 已上传到 $SERVER_URL 并激活"
echo "  客户端下次 Settings → 检查更新 或定时检查（24h）即可看到 v${VERSION_NAME}"
