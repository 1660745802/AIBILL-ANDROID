#!/bin/bash
# ===========================================
# AIBILL Android - 检查服务端更新 + 可选下载
# ===========================================
# 用途: 在 app 项目内手动验证 billserver 更新接口，
#       可选把服务端最新的 APK 拉到本地（不安装）。
#
# 这是 scripts/upload_apk.sh 的"反向"对偶脚本：
#   upload_apk.sh  → 构建 + 上传 APK → 激活
#   check_update.sh → 模拟客户端 → 查 / 下载
#
# 前置条件:
#   1. billserver 已部署（提供 GET /api/app/update）
#   2. 安装了 python3（用于 JSON 解析）
#   3. 可选: wget 或 curl（下载 APK 用）
#
# 用法:
#   ./scripts/check_update.sh                       # 仅检查，不下载
#   ./scripts/check_update.sh --download            # 检查 + 下载新版本
#   ./scripts/check_update.sh -d -f                 # 强制下载最新版（忽略 hasUpdate）
#   AIBILL_SERVER_URL=https://api.xxx.com ./check_update.sh

set -e

# ===== 配置 =====
SERVER_URL="${AIBILL_SERVER_URL:-http://localhost:3000}"
PLATFORM="android"
GRADLE_FILE="app/build.gradle.kts"
DOWNLOAD_DIR="app/build/outputs/apk/updates"

DO_DOWNLOAD=false
FORCE_DOWNLOAD=false
for arg in "$@"; do
  case "$arg" in
    --download|-d) DO_DOWNLOAD=true ;;
    --force|-f)    FORCE_DOWNLOAD=true ;;
    --help|-h)
      sed -n '2,20p' "$0"
      exit 0
      ;;
    *) echo "❌ 未知参数: $arg（用 --help 看用法）"; exit 1 ;;
  esac
done

# ===== 校验 gradle 文件 =====
if [ ! -f "$GRADLE_FILE" ]; then
  echo "❌ 找不到 $GRADLE_FILE，请在项目根目录运行"
  exit 1
fi

# ===== 从 build.gradle.kts 读取版本号 =====
VERSION_NAME=$(grep -E 'versionName\s*=\s*"' "$GRADLE_FILE" | head -1 | sed -E 's/.*"([^"]+)".*/\1/')
VERSION_CODE=$(grep -E 'versionCode\s*=\s*[0-9]+' "$GRADLE_FILE" | head -1 | sed -E 's/.*=\s*([0-9]+).*/\1/')

if [ -z "$VERSION_NAME" ] || [ -z "$VERSION_CODE" ]; then
  echo "❌ 无法从 $GRADLE_FILE 解析版本号"
  exit 1
fi

echo "=== 检查更新 ==="
echo "  当前版本:   $VERSION_NAME (code=$VERSION_CODE)"
echo "  服务器:      $SERVER_URL"
echo "  平台:        $PLATFORM"
echo "  下载模式:    $([ "$DO_DOWNLOAD" = true ] && echo "是" || echo "否")$([ "$FORCE_DOWNLOAD" = true ] && echo "（强制）")"
echo ""

# ===== 调用 /api/app/update（模拟客户端）=====
echo "=== 1. GET /api/app/update ==="
RESP=$(curl -s -w "\n__HTTP_STATUS__:%{http_code}" \
  "$SERVER_URL/api/app/update?versionName=${VERSION_NAME}&versionCode=${VERSION_CODE}&platform=${PLATFORM}")

HTTP_STATUS=$(echo "$RESP" | grep "__HTTP_STATUS__:" | cut -d: -f2)
BODY=$(echo "$RESP" | grep -v "__HTTP_STATUS__:")

if [ "$HTTP_STATUS" != "200" ]; then
  echo "❌ HTTP $HTTP_STATUS"
  echo "$BODY" | head -20
  echo ""
  echo "可能原因:"
  echo "  - billserver 未启动（默认 http://localhost:3000）"
  echo "  - /api/app/update 端点未实现（参见 docs/billserver-update-api.md）"
  echo "  - CORS / 防火墙拦截"
  exit 1
fi

# ===== 解析响应 =====
read_has_update=$(echo "$BODY"       | python3 -c "import sys,json;d=json.load(sys.stdin).get('data',{});print(d.get('has_update', False))" 2>/dev/null || echo "False")
read_latest_v=$(echo "$BODY"         | python3 -c "import sys,json;d=json.load(sys.stdin).get('data',{});print(d.get('latest_version', '') or '-')" 2>/dev/null || echo "-")
read_latest_c=$(echo "$BODY"         | python3 -c "import sys,json;d=json.load(sys.stdin).get('data',{});print(d.get('latest_version_code', '-'))" 2>/dev/null || echo "-")
read_force=$(echo "$BODY"            | python3 -c "import sys,json;d=json.load(sys.stdin).get('data',{});print(d.get('force_update', False))" 2>/dev/null || echo "False")
read_changelog=$(echo "$BODY"        | python3 -c "import sys,json;d=json.load(sys.stdin).get('data',{});print((d.get('changelog','') or '')[:120])" 2>/dev/null || echo "")
read_apk_url=$(echo "$BODY"          | python3 -c "import sys,json;d=json.load(sys.stdin).get('data',{});print(d.get('apk_url','') or '-')" 2>/dev/null || echo "-")
read_apk_size=$(echo "$BODY"         | python3 -c "import sys,json;d=json.load(sys.stdin).get('data',{});s=d.get('apk_size',0);print(f'{s/1024/1024:.1f} MB' if s else '-')" 2>/dev/null || echo "-")

echo "=== 2. 响应 ==="
printf "  %-15s %s\n" "hasUpdate:"     "$read_has_update"
printf "  %-15s %s\n" "latestVersion:" "$read_latest_v"
printf "  %-15s %s\n" "versionCode:"   "$read_latest_c"
printf "  %-15s %s\n" "forceUpdate:"   "$read_force"
printf "  %-15s %s\n" "apkSize:"       "$read_apk_size"
printf "  %-15s %s\n" "apkUrl:"        "$read_apk_url"
if [ -n "$read_changelog" ]; then
  printf "  %-15s %s\n" "changelog:"     "${read_changelog:0:80}..."
fi
echo ""

# ===== 决策 =====
SHOULD_DOWNLOAD=false
DOWNLOAD_REASON=""

if [ "$DO_DOWNLOAD" = false ]; then
  echo "ℹ️  未传 --download，跳过下载"
  echo "   用 ./scripts/check_update.sh --download 触发下载"
  exit 0
fi

if [ "$read_has_update" = "True" ]; then
  SHOULD_DOWNLOAD=true
  DOWNLOAD_REASON="服务端报告有新版本"
elif [ "$FORCE_DOWNLOAD" = true ]; then
  SHOULD_DOWNLOAD=true
  DOWNLOAD_REASON="--force 强制下载"
else
  echo "✅ 已是最新版本 v${VERSION_NAME}（server latest=v${read_latest_v}）"
  echo "   想强制下载最新版本（无视版本对比）用 -f"
  exit 0
fi

if [ "$read_apk_url" = "-" ] || [ -z "$read_apk_url" ]; then
  echo "❌ $DOWNLOAD_REASON，但 apk_url 为空，无法下载"
  exit 1
fi

# ===== 下载 =====
mkdir -p "$DOWNLOAD_DIR"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
TARGET_FILE="${DOWNLOAD_DIR}/aibill-${read_latest_v}-${TIMESTAMP}.apk"

echo "=== 3. 下载 APK ==="
echo "  原因: $DOWNLOAD_REASON"
echo "  URL:  $read_apk_url"
echo "  目标: $TARGET_FILE"
echo ""

if command -v wget >/dev/null 2>&1; then
  wget -q --show-progress -O "$TARGET_FILE" "$read_apk_url"
elif command -v curl >/dev/null 2>&1; then
  curl -fL -o "$TARGET_FILE" "$read_apk_url"
else
  echo "❌ 需要 wget 或 curl"
  exit 1
fi

# ===== 校验 =====
APK_SIZE_BYTES=$(stat -c '%s' "$TARGET_FILE" 2>/dev/null || stat -f '%z' "$TARGET_FILE")
APK_SIZE_MB=$(echo "scale=2; $APK_SIZE_BYTES / 1024 / 1024" | bc 2>/dev/null || echo "?")

# 简单 magic 校验：APK = ZIP，文件头 PK\x03\x04
MAGIC=$(head -c 4 "$TARGET_FILE" | xxd -p 2>/dev/null || head -c 4 "$TARGET_FILE" | od -An -tx1 | tr -d ' \n')
if [ "$MAGIC" != "504b0304" ]; then
  echo "❌ 下载文件不是有效 APK（magic=$MAGIC），可能 URL 返回了 HTML/JSON 错误页"
  echo "   前 200 字节:"
  head -c 200 "$TARGET_FILE"
  echo ""
  exit 1
fi

echo ""
echo "✅ 下载完成"
echo "   路径: $TARGET_FILE"
echo "   大小: ${APK_SIZE_MB} MB"
echo ""
echo "下一步:"
echo "   adb install -r $TARGET_FILE           # 安装到设备"
echo "   # 或"
echo "   unzip -l $TARGET_FILE | head          # 检查 APK 内容"