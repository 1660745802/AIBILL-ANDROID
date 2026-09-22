#!/bin/bash
# ===========================================
# AIBILL Android APK 发布（billserver + GitHub 可选）
# ===========================================
# 默认：仅发 billserver（UpdateManager 优先源）
# 加 --github：billserver + GitHub Release 同时发（双发布）
# 加 --github-only：仅发 GitHub（跳过 billserver）
#
# 流程（默认 + --github）:
#   1. ./gradlew assembleRelease 构建 release APK
#   2. POST /api/admin/updates 上传 APK + 元数据到 billserver
#   3. billserver 自动激活
#   4. 验证 GET /api/app/update
#   5. --github：git tag v{x.y.z} + gh release create
#
# 前置条件:
#   - billserver 已实现 GET /api/app/update + POST /api/admin/updates
#   - 安装了 curl 和 python3
#   - --github：额外需要 gh CLI（https://cli.github.com/）且已登录

set -e

# ===== 配置（全部默认指向 localhost）=====
SERVER_URL="${AIBILL_SERVER_URL:-http://localhost:3000}"
ADMIN_USER="${AIBILL_ADMIN_USER:-admin}"
ADMIN_PASS="${AIBILL_ADMIN_PASS:-}"
APK_PATH="${AIBILL_APK_PATH:-app/build/outputs/apk/release/app-release.apk}"

# ===== flag 解析 =====
DO_GITHUB=false
GITHUB_ONLY=false
DRY_RUN=false
for arg in "$@"; do
  case "$arg" in
    --github)       DO_GITHUB=true ;;
    --github-only)  GITHUB_ONLY=true; DO_GITHUB=true ;;
    --dry-run)      DRY_RUN=true ;;
    --help|-h)
      sed -n '2,22p' "$0"
      exit 0
      ;;
    *) echo "❌ 未知参数: $arg（用 --help 看用法）"; exit 1 ;;
  esac
done

if [ "$GITHUB_ONLY" = true ]; then
  echo "ℹ️  --github-only：跳过 billserver"
fi
if [ "$DRY_RUN" = true ]; then
  echo "ℹ️  --dry-run：不实际执行网络/上传操作"
fi

# ===== 校验 =====
if [ "$GITHUB_ONLY" = false ] && [ -z "$ADMIN_PASS" ]; then
  echo "❌ 请设置环境变量 AIBILL_ADMIN_PASS"
  echo "用法: AIBILL_ADMIN_PASS=xxx ./upload_apk.sh [--github]"
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

# ===== 收集 changelog（billserver + GitHub 共用）=====
CHANGELOG_FILE=".changelog.tmp"
echo "请输入更新说明（changelog，会同时发给 billserver 和 GitHub）："
echo "  （可粘贴多行；输入 Ctrl-D / Ctrl-Z 结束）"
CHANGELOG_INPUT=""
while IFS= read -r line; do
  CHANGELOG_INPUT+="$line"$'\n'
done
echo "$CHANGELOG_INPUT" > "$CHANGELOG_FILE"
echo ""

echo "=== 发布概览 ==="
echo "  版本:      $VERSION_NAME (code=$VERSION_CODE)"
echo "  APK 大小:  $(($APK_SIZE / 1024 / 1024)) MB"
echo "  渠道:      $([ "$GITHUB_ONLY" = true ] && echo "GitHub only" || ([ "$DO_GITHUB" = true ] && echo "billserver + GitHub" || echo "billserver only"))"
echo "  服务器:    $([ "$GITHUB_ONLY" = true ] && echo "（跳过）" || echo "$SERVER_URL")"
echo ""

# ===========================================
# 阶段 A：发 billserver（默认 + --github 模式）
# ===========================================
BILLSERVER_OK=false
if [ "$GITHUB_ONLY" = false ]; then
  echo "=== A1. 登录 admin ==="
  if [ "$DRY_RUN" = true ]; then
    echo "  [dry-run] 跳过登录"
    TOKEN="dummy"
  else
    TOKEN=$(curl -s -X POST "$SERVER_URL/api/auth/login" \
      -H "Content-Type: application/json" \
      -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" \
      | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['token'])" 2>/dev/null \
      || echo "")
    if [ -z "$TOKEN" ]; then
      echo "❌ 登录失败，请检查账号密码和服务器地址（默认 http://localhost:3000）"
      rm -f "$CHANGELOG_FILE"
      exit 1
    fi
    echo "✓ 登录成功"
  fi

  echo ""
  echo "=== A2. 上传 APK 到 billserver ==="
  FORCE_UPDATE_FLAG="${AIBILL_FORCE_UPDATE:-false}"

  if [ "$DRY_RUN" = true ]; then
    echo "  [dry-run] curl POST $SERVER_URL/api/admin/updates"
    APK_URL="https://example.com/dry-run.apk"
    NEW_UPDATE_ID="0"
    BILLSERVER_OK=true
  else
    UPLOAD_RESULT=$(curl -s -X POST "$SERVER_URL/api/admin/updates" \
      -H "Authorization: Bearer $TOKEN" \
      -F "apk_file=@${APK_PATH};type=application/vnd.android.package-archive" \
      -F "version_name=${VERSION_NAME}" \
      -F "version_code=${VERSION_CODE}" \
      -F "changelog=<${CHANGELOG_FILE}" \
      -F "force_update=${FORCE_UPDATE_FLAG}")

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
      rm -f "$CHANGELOG_FILE"
      exit 1
    fi
    BILLSERVER_OK=true
  fi
  echo "✓ billserver 上传完成 (id=$NEW_UPDATE_ID, apk_url=$APK_URL)"

  echo ""
  echo "=== A3. 验证 billserver 激活 ==="
  if [ "$DRY_RUN" = true ]; then
    echo "  [dry-run] GET $SERVER_URL/api/app/update?..."
  else
    curl -s "$SERVER_URL/api/app/update?versionName=${VERSION_NAME}&versionCode=${VERSION_CODE}&platform=android" | python3 -c "
import sys, json
d = json.load(sys.stdin)
data = d.get('data', {})
print(f\"  hasUpdate:      {data.get('has_update')}\")
print(f\"  latestVersion:  {data.get('latest_version')}\")
print(f\"  versionCode:    {data.get('latest_version_code')}\")
print(f\"  forceUpdate:    {data.get('force_update')}\")
print(f\"  apkUrl:         {data.get('apk_url')}\")
print(f\"  apkSize:        {data.get('apk_size')}\")
changelog = (data.get('changelog', '') or '')[:80]
print(f\"  changelog:      {changelog}{'...' if changelog else ''}\")
" 2>/dev/null || echo "  (解析失败，手动验证)"
  fi
  echo ""
fi

# ===========================================
# 阶段 B：发 GitHub Release（--github 或 --github-only 触发）
# ===========================================
if [ "$DO_GITHUB" = true ]; then
  # B0. billserver 必须先成功（除非 --github-only）
  if [ "$GITHUB_ONLY" = false ] && [ "$BILLSERVER_OK" = false ]; then
    echo "❌ billserver 上传失败，中止 GitHub 发布（用 --github-only 可仅发 GitHub）"
    rm -f "$CHANGELOG_FILE"
    exit 1
  fi

  TAG="v${VERSION_NAME}"

  echo "=== B1. 检测 gh CLI ==="
  if ! command -v gh >/dev/null 2>&1; then
    echo "❌ 需要 gh CLI（https://cli.github.com/）"
    echo "   或用 --github-only 跳过"
    rm -f "$CHANGELOG_FILE"
    exit 1
  fi
  echo "✓ gh $(gh --version | head -1)"

  echo ""
  echo "=== B2. 检测 gh 登录 ==="
  if ! gh auth status >/dev/null 2>&1; then
    echo "❌ gh 未登录：执行 gh auth login"
    rm -f "$CHANGELOG_FILE"
    exit 1
  fi
  GH_USER=$(gh api user --jq '.login' 2>/dev/null || echo "?")
  echo "✓ 已登录: $GH_USER"

  echo ""
  echo "=== B3. 检查 tag ==="
  if git rev-parse "$TAG" >/dev/null 2>&1; then
    if [ "${AIBILL_FORCE_GITHUB:-false}" = "true" ]; then
      echo "⚠️  tag $TAG 已存在，AIBILL_FORCE_GITHUB=true → 删除重建"
      if [ "$DRY_RUN" = false ]; then
        git tag -d "$TAG" 2>/dev/null || true
        git push origin ":refs/tags/$TAG" 2>/dev/null || true
      fi
    else
      echo "❌ tag $TAG 已存在，跳过 GitHub 发布"
      echo "   选项："
      echo "     - git tag -d $TAG && git push origin :refs/tags/$TAG    # 手动删"
      echo "     - AIBILL_FORCE_GITHUB=true $0 --github                 # 自动覆盖"
      rm -f "$CHANGELOG_FILE"
      exit 1
    fi
  fi
  echo "✓ tag $TAG 未占用"

  echo ""
  echo "=== B4. 创建 + 推送 tag ==="
  if [ "$DRY_RUN" = false ]; then
    git tag -a "$TAG" -m "v${VERSION_NAME}"
    git push origin "$TAG"
  else
    echo "  [dry-run] git tag -a $TAG && git push origin $TAG"
  fi
  echo "✓ tag 已推送"

  echo ""
  echo "=== B5. 创建 GitHub Release ==="
  if [ "$DRY_RUN" = false ]; then
    gh release create "$TAG" "$APK_PATH" \
      --title "v${VERSION_NAME}" \
      --notes-file "$CHANGELOG_FILE"

    GH_REPO=$(gh repo view --json nameWithOwner --jq '.nameWithOwner' 2>/dev/null || echo "?")
    echo "✓ Release 已创建：https://github.com/${GH_REPO}/releases/tag/${TAG}"
  else
    echo "  [dry-run] gh release create $TAG $APK_PATH"
  fi
  echo ""
fi

rm -f "$CHANGELOG_FILE"

# ===== 总结 =====
echo "🎉 发布完成"
if [ "$GITHUB_ONLY" = false ] && [ "$BILLSERVER_OK" = true ]; then
  echo "   ✓ billserver: APK 已激活，客户端下次检测可见 v${VERSION_NAME}"
fi
if [ "$DO_GITHUB" = true ]; then
  echo "   ✓ GitHub:    tag v${VERSION_NAME} + Release 已创建"
  echo "                作为 billserver 不可达时的 fallback 源"
fi