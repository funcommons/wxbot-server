#!/usr/bin/env bash
# T10-9 部署 + 真机验收辅助脚本（wechat-sync server）
#
# 分阶段执行（服务启停由主 agent 用后台任务管，本脚本不碰进程）：
#   package  停 8080 占用 → mvn package（jar ≥ 60MB 健康）→ 打印 jar 大小
#   backup   备份 wx_account 到 wx_account_bak_t10 → 清空 wx_account（触发 bootstrap 前提）
#   restore  删掉 bootstrap 新增行 → 从备份还原 → drop 备份 → 核对行数
#   accept   跑 t10_acceptance.py（HTTP 全链路真机验收）
#   cleanup  物理清理验收残留 + 核对 wx_account/种子完好性
#   counts   只打各表行数
#
# 约束：
#   - 日志一律 >> 追加（禁 > 覆盖）
#   - 令牌明文不打印（只落文件）
#   - wx_account 真实行与 wx_task_schedule 种子 id 1/2/3 绝不删（备份/还原式操作）
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1
JAR="target/wechat-sync-server-0.1.0-SNAPSHOT.jar"
PSQL="docker exec wxuw-pg psql -U postgres -d wechat -t -A -c"
PY="/c/workspace/wechatauto-replica/.venv/Scripts/python.exe"
# mvn 不在 PATH（本机 Git Bash 环境），优先全路径，其次 PATH
if [ -x "C:/workspace/tools/apache-maven-3.9.9/bin/mvn" ]; then
  MVN="C:/workspace/tools/apache-maven-3.9.9/bin/mvn"
else
  MVN="mvn"
fi
ACCOUNT="${ACCOUNT:-wxid_c00pwixax7xv22_8316}"
ADMIN_TOKEN_FILE="${ADMIN_TOKEN_FILE:-.t10_admin_access.txt}"
BAK_TABLE="wx_account_bak_t10"
PHASE="${1:-}"

say() { echo ""; echo "=== $* ==="; }
fail() { echo "!!! $*"; exit 1; }

case "$PHASE" in

package)
  say "停 8080 占用（mvn package 前必须解锁 jar）"
  PID=$(netstat -ano | grep ":8080" | grep LISTENING | head -1 | awk '{print $NF}')
  if [ -n "${PID:-}" ]; then
    echo "停 PID $PID"
    taskkill //F //PID "$PID" >/dev/null 2>&1
    sleep 4
  else
    echo "8080 无监听"
  fi
  netstat -ano | grep ":8080" | grep LISTENING >/dev/null && fail "8080 仍被占用"
  say "mvn package"
  "$MVN" -q package -DskipTests || fail "mvn package 失败"
  [ -f "$JAR" ] || fail "jar 缺失"
  SIZE=$(stat -c %s "$JAR")
  echo "jar 大小 $SIZE"
  [ "$SIZE" -ge 60000000 ] || fail "jar 体积异常（$SIZE）"
  ;;

backup)
  say "备份 wx_account 并清空（bootstrap 前提：表空）"
  $PSQL "DROP TABLE IF EXISTS $BAK_TABLE" >/dev/null
  $PSQL "CREATE TABLE $BAK_TABLE AS SELECT * FROM wx_account" >/dev/null || fail "备份失败"
  ORIG=$($PSQL "SELECT count(*) FROM $BAK_TABLE")
  echo "备份行数 $ORIG"
  [ "$ORIG" -ge 1 ] || fail "wx_account 本来就空，备份无意义"
  $PSQL "DELETE FROM wx_account" >/dev/null || fail "清空失败"
  echo "清空后 wx_account 行数 $($PSQL "SELECT count(*) FROM wx_account")"
  ;;

restore)
  say "删 bootstrap 新增行并还原 wx_account"
  $PSQL "DELETE FROM wx_account WHERE id NOT IN (SELECT id FROM $BAK_TABLE)" >/dev/null
  $PSQL "INSERT INTO wx_account SELECT * FROM $BAK_TABLE" >/dev/null || fail "还原失败"
  RESTORED=$($PSQL "SELECT count(*) FROM wx_account")
  ORIG=$($PSQL "SELECT count(*) FROM $BAK_TABLE")
  $PSQL "DROP TABLE $BAK_TABLE" >/dev/null
  echo "还原后 $RESTORED（备份 $ORIG）"
  [ "$RESTORED" = "$ORIG" ] || fail "行数不一致"
  ;;

accept)
  say "真机验收"
  CLIENT_TOKEN_FILE=".t9_client_smoke.txt"
  [ -f ".t10_client_token.txt" ] && CLIENT_TOKEN_FILE=".t10_client_token.txt"
  echo "client 令牌文件 $CLIENT_TOKEN_FILE"
  PYTHONIOENCODING=utf-8 "$PY" scripts/t10_acceptance.py \
    --base http://127.0.0.1:8080 \
    --admin-token-file "$ADMIN_TOKEN_FILE" \
    --client-token-file "$CLIENT_TOKEN_FILE" \
    --account "$ACCOUNT" \
    --summary-out scripts/t10_acceptance_summary.json
  rc=$?
  echo "验收脚本退出码 $rc"
  exit $rc
  ;;

mint-client)
  # 应急：旧 client 令牌失效（Redis 会话已死）时重签。副作用：旧令牌即时吊销。
  say "签发新 client 令牌（会吊销旧令牌）"
  RESP=$(curl -s --max-time 15 -X POST "http://127.0.0.1:8080/v1/accounts/$ACCOUNT/tokens" \
      -H "Authorization: Bearer $(cat "$ADMIN_TOKEN_FILE")" -H "Content-Type: application/json" -d '{}')
  echo "$RESP" | head -c 200; echo
  printf '%s' "$RESP" | "$PY" -c "
import json,sys
env=json.load(sys.stdin)
tok=(env.get('data') or {}).get('access_token')
if not tok:
    print('签发失败：', env.get('code'), env.get('message')); sys.exit(1)
open('.t10_client_token.txt','w').write(tok)
print('新 client 令牌已写 .t10_client_token.txt（长度 %d）' % len(tok))
" || fail "client 令牌签发失败"
  ;;

cleanup)
  say "物理清理验收残留"
  $PSQL "DELETE FROM wx_subscription_delivery WHERE subscription_id IN (SELECT id FROM wx_subscription WHERE name LIKE 't10_accept_%')" >/dev/null
  $PSQL "DELETE FROM wx_subscription WHERE name LIKE 't10_accept_%'" >/dev/null
  $PSQL "DELETE FROM wx_consumer WHERE name LIKE 't10_accept_%'" >/dev/null
  $PSQL "DELETE FROM wx_message_source_rel WHERE chat_username LIKE 't10_accept_chat%'" >/dev/null
  $PSQL "DELETE FROM wx_merge_log WHERE dedup_key IN (SELECT 'srv:' || COALESCE(server_id_raw, server_id::text) FROM wx_message WHERE chat_username LIKE 't10_accept_chat%')" >/dev/null
  $PSQL "DELETE FROM wx_message_attachment WHERE message_id NOT IN (SELECT id FROM wx_message)" >/dev/null
  $PSQL "DELETE FROM wx_message WHERE chat_username LIKE 't10_accept_chat%'" >/dev/null
  $PSQL "DELETE FROM wx_conversation WHERE username LIKE 't10_accept_chat%'" >/dev/null
  $PSQL "DELETE FROM wx_ingest_batch_log WHERE batch_ref LIKE 't10_accept_%'" >/dev/null
  say "残留核对（期望：v6 四表 0 行；wx_account 6；种子 3）"
  echo "wx_consumer:          $($PSQL "SELECT count(*) FROM wx_consumer")"
  echo "wx_subscription:      $($PSQL "SELECT count(*) FROM wx_subscription")"
  echo "wx_subscription_delivery: $($PSQL "SELECT count(*) FROM wx_subscription_delivery")"
  echo "wx_sub_delivery_cursor:   $($PSQL "SELECT count(*) FROM wx_sub_delivery_cursor")"
  echo "wx_account:           $($PSQL "SELECT count(*) FROM wx_account")"
  echo "wx_task_schedule 种子 1/2/3: $($PSQL "SELECT count(*) FROM wx_task_schedule WHERE id IN (1,2,3)")"
  echo "wx_message 总数:      $($PSQL "SELECT count(*) FROM wx_message")"
  echo "t10_accept 残留:      $($PSQL "SELECT count(*) FROM wx_message WHERE chat_username LIKE 't10_accept_chat%'")"
  ;;

counts)
  echo "wx_account:           $($PSQL "SELECT count(*) FROM wx_account")"
  echo "wx_consumer:          $($PSQL "SELECT count(*) FROM wx_consumer")"
  echo "wx_subscription:      $($PSQL "SELECT count(*) FROM wx_subscription")"
  echo "wx_subscription_delivery: $($PSQL "SELECT count(*) FROM wx_subscription_delivery")"
  echo "wx_sub_delivery_cursor:   $($PSQL "SELECT count(*) FROM wx_sub_delivery_cursor")"
  echo "wx_message:           $($PSQL "SELECT count(*) FROM wx_message")"
  ;;

*)
  echo "用法: $0 {package|backup|restore|accept|cleanup|counts}"
  exit 2
  ;;
esac
