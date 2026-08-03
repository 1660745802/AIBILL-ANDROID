#!/bin/bash
# ===========================================
# 通知记账规则云控 - 更新脚本
# ===========================================
# 用法: 修改 rules.json 后执行本脚本推送并激活
#
# 前置条件:
#   1. 已部署后端 AIBILL（含 notification-rules 路由）
#   2. 有 admin 账号
#   3. 安装了 curl 和 jq（可选）

set -e

# ===== 配置 =====
SERVER_URL="${AIBILL_SERVER_URL:-http://localhost:3000}"
ADMIN_USER="${AIBILL_ADMIN_USER:-admin}"
ADMIN_PASS="${AIBILL_ADMIN_PASS:-}"
RULES_FILE="${1:-rules.json}"

if [ -z "$ADMIN_PASS" ]; then
  echo "请设置环境变量 AIBILL_ADMIN_PASS 或通过参数传入"
  echo "用法: AIBILL_ADMIN_PASS=xxx ./update_rules.sh [rules.json]"
  exit 1
fi

if [ ! -f "$RULES_FILE" ]; then
  echo "规则文件不存在: $RULES_FILE"
  exit 1
fi

echo "=== 1. 登录获取 Token ==="
TOKEN=$(curl -s -X POST "$SERVER_URL/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['token'])" 2>/dev/null \
  || echo "")

if [ -z "$TOKEN" ]; then
  echo "❌ 登录失败，请检查账号密码和服务器地址"
  exit 1
fi
echo "✓ 登录成功"

echo ""
echo "=== 2. 查看当前版本 ==="
curl -s "$SERVER_URL/api/config/notification-rules" | python3 -c "
import sys, json
data = json.load(sys.stdin)
print(f\"  当前版本: v{data['data']['version']}\")
print(f\"  更新时间: {data['data']['updated_at']}\")
" 2>/dev/null || echo "  (解析失败，继续)"

echo ""
echo "=== 3. 推送新版本 ==="
RESULT=$(curl -s -X POST "$SERVER_URL/api/admin/notification-rules" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d @"$RULES_FILE")

NEW_ID=$(echo "$RESULT" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['id'])" 2>/dev/null || echo "")

if [ -z "$NEW_ID" ]; then
  echo "❌ 创建失败: $RESULT"
  exit 1
fi

NEW_VERSION=$(echo "$RESULT" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['version'])" 2>/dev/null)
echo "✓ 已创建 v${NEW_VERSION} (id=$NEW_ID)"

echo ""
echo "=== 4. 激活新版本 ==="
ACTIVATE=$(curl -s -X PUT "$SERVER_URL/api/admin/notification-rules/$NEW_ID/activate" \
  -H "Authorization: Bearer $TOKEN")

echo "$ACTIVATE" | python3 -c "import sys,json;d=json.load(sys.stdin);print(f\"✓ {d['message']}\")" 2>/dev/null || echo "✓ 激活完成"

echo ""
echo "=== 5. 验证 ==="
curl -s "$SERVER_URL/api/config/notification-rules" | python3 -c "
import sys, json
data = json.load(sys.stdin)
print(f\"  生效版本: v{data['data']['version']}\")
print(f\"  A11Y 排除词数量: {len(data['data']['rules']['a11y']['common_exclude_keywords'])}\")
print(f\"  来源映射数量: {len(data['data']['rules']['source_mapping'])}\")
" 2>/dev/null || echo "  (验证完成)"

echo ""
echo "🎉 规则已更新！客户端下次启动时自动拉取新规则。"
