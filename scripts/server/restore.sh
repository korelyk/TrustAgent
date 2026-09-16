#!/usr/bin/env bash
# ============================================================================
# 恢复
#
# 默认行为是**恢复到临时测试库**，绝不碰线上数据。
#   为什么把危险操作设为非默认：恢复演练是要经常做的事（每季度都该做一次，
#   否则真出事时才发现备份是坏的）。如果默认就覆盖线上，那就没人敢练，
#   备份也就永远没被验证过——一份没验证过的备份等于没有备份。
#
# 用法：
#   ./scripts/server/restore.sh --from <备份目录名> --to-test
#       恢复到临时库 trustagent_restore_test，校验表数量与关键行数后保留该库供人工核对。
#
#   ./scripts/server/restore.sh --from <备份目录名> --to-test --drop-after
#       同上，但校验完就删掉临时库（用于自动化/CI）。
#
#   ./scripts/server/restore.sh --from <备份目录名> --to-live --yes-i-know
#       覆盖线上库。**必须先停控制面与 Worker**（脚本会检查），且不可逆。
#       注意：这只恢复数据库，不恢复 Redis 队列，也不回滚代码版本。
# ============================================================================

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

FROM=""
MODE=""
DROP_AFTER=0
CONFIRMED=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --from)       FROM="$2"; shift 2 ;;
    --to-test)    MODE="test"; shift ;;
    --to-live)    MODE="live"; shift ;;
    --drop-after) DROP_AFTER=1; shift ;;
    --yes-i-know) CONFIRMED=1; shift ;;
    *) die "未知参数: $1" ;;
  esac
done

[[ -n "$FROM" ]] || die "必须指定 --from <备份目录名>。可用备份：$(ls -1 "$BACKUP_DIR" 2>/dev/null | tr '\n' ' ')"
[[ -n "$MODE" ]] || die "必须明确指定 --to-test 或 --to-live（不接受默认值，避免误操作）"

require_docker
require_env_file

SRC="$BACKUP_DIR/$FROM"
[[ -d "$SRC" ]] || die "备份目录不存在: $SRC"
[[ -f "$SRC/db.sql.gz" ]] || die "$SRC 中没有 db.sql.gz（该备份可能跳过了数据库）"

DB_NAME="$(get_env TRUSTAGENT_DB_NAME trustagent)"
DB_USER="$(get_env TRUSTAGENT_DB_USERNAME trustagent)"
TEST_DB="${DB_NAME}_restore_test"

echo "============================================================"
echo " 恢复：$FROM  ->  $([[ "$MODE" == "test" ]] && echo "临时库 $TEST_DB" || echo "线上库 $DB_NAME")"
echo "============================================================"

# ---------------------------------------------------------------------------
echo
echo "[0] 前置检查"
# ---------------------------------------------------------------------------
docker inspect trustagent-mysql >/dev/null 2>&1 || die "MySQL 容器不存在"
[[ "$(docker inspect -f '{{.State.Status}}' trustagent-mysql)" == "running" ]] \
  || die "MySQL 未运行。请先 ./scripts/server/deploy.sh 或 docker compose up -d mysql"

# 校验备份完整性（备份时写了 sha256）
if [[ -f "$SRC/META.txt" ]]; then
  EXPECTED="$(grep ' db.sql.gz$' "$SRC/META.txt" | awk '{print $1}')"
  ACTUAL="$(sha256sum "$SRC/db.sql.gz" | cut -d' ' -f1)"
  if [[ -n "$EXPECTED" && "$EXPECTED" != "$ACTUAL" ]]; then
    die "db.sql.gz 校验值不匹配（备份已损坏）。期望 ${EXPECTED:0:12}...，实际 ${ACTUAL:0:12}..."
  fi
  ok "备份校验值匹配"
else
  warn "备份中没有 META.txt，跳过完整性校验"
fi

if [[ "$MODE" == "live" ]]; then
  (( CONFIRMED == 1 )) || die "--to-live 需要同时传入 --yes-i-know（这条路径会覆盖线上数据，不可逆）"
  # 控制面/Worker 必须停：它们与恢复过程并发写库会写出不一致的数据
  for svc in backend ai-worker; do
    if docker inspect "trustagent-${svc}" >/dev/null 2>&1 && \
       [[ "$(docker inspect -f '{{.State.Status}}' "trustagent-${svc}")" == "running" ]]; then
      die "容器 trustagent-${svc} 仍在运行。请先 ./scripts/server/stop.sh，再执行恢复。"
    fi
  done
  ok "控制面与 Worker 均已停止"
fi

# ---------------------------------------------------------------------------
TARGET_DB="$TEST_DB"
[[ "$MODE" == "live" ]] && TARGET_DB="$DB_NAME"

echo
echo "[1/4] 准备目标库 ${TARGET_DB}"
# ---------------------------------------------------------------------------
if [[ "$MODE" == "test" ]]; then
  # 临时库每次重建，保证"验证备份"这件事不被上一轮的残留污染
  docker exec trustagent-mysql sh -c \
    "exec mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -e 'DROP DATABASE IF EXISTS \`${TEST_DB}\`; CREATE DATABASE \`${TEST_DB}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;'" \
    >/dev/null || die "创建临时库失败"
  ok "临时库已重建（这与线上库 ${DB_NAME} 完全隔离）"
else
  warn "正在覆盖线上库 ${DB_NAME}——数据将不可恢复地替换"
fi

# ---------------------------------------------------------------------------
echo
echo "[2/4] 导入数据"
# ---------------------------------------------------------------------------
# 刻意用 root 导入：逻辑备份里含 CREATE TABLE、索引、可能还有 DEFINER 子句，
# 普通应用账号不一定有权限重建这些对象。
# 这是**唯一**需要 root 的场景（日常应用连接仍用非 root 账号）。
gunzip -c "$SRC/db.sql.gz" | docker exec -i trustagent-mysql sh -c \
  "exec mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" \"${TARGET_DB}\"" \
  || die "导入失败"
ok "导入完成"

# 若是恢复到线上库，把该库的权限补回给应用账号
# （备份里不含 GRANT，导入后应用账号可能对新建的表没有权限）
if [[ "$MODE" == "live" ]]; then
  docker exec trustagent-mysql sh -c \
    "exec mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -e 'GRANT ALL PRIVILEGES ON \`${DB_NAME}\`.* TO \"${DB_USER}\"@\"%\"; FLUSH PRIVILEGES;'" \
    >/dev/null || warn "补授权失败，请人工检查应用账号对 ${DB_NAME} 的权限"
  ok "应用账号权限已刷新"
fi

# ---------------------------------------------------------------------------
echo
echo "[3/4] 校验恢复结果"
# ---------------------------------------------------------------------------
# 只看几个关键表是否可读、行数是否合理。
# 不做"逐行比对"——那是备份工具的事，这里的目标是尽早发现
# "恢复了但表是空的"这类最致命的错误。
sql() {
  docker exec trustagent-mysql sh -c \
    "exec mysql -N -B -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -e \"$1\"" 2>/dev/null | tr -d '\r'
}

TABLE_COUNT="$(sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${TARGET_DB}';")"
AUDIT_ROWS="$(sql "SELECT COUNT(*) FROM ${TARGET_DB}.audit_log;")"
RUN_ROWS="$(sql "SELECT COUNT(*) FROM ${TARGET_DB}.eval_run;")"
CASE_ROWS="$(sql "SELECT COUNT(*) FROM ${TARGET_DB}.eval_case_result;")"
USER_ROWS="$(sql "SELECT COUNT(*) FROM ${TARGET_DB}.sys_user;")"

echo "  表数量: ${TABLE_COUNT}"
echo "  用户:   ${USER_ROWS}"
echo "  审计:   ${AUDIT_ROWS}"
echo "  评测任务: ${RUN_ROWS}   评测结果: ${CASE_ROWS}"

VERIFY_OK=1
if [[ ! "$TABLE_COUNT" =~ ^[0-9]+$ ]] || (( TABLE_COUNT < 10 )); then
  bad "表数量异常（${TABLE_COUNT}）——恢复可能不完整"
  VERIFY_OK=0
fi
if [[ ! "$USER_ROWS" =~ ^[0-9]+$ ]] || (( USER_ROWS < 1 )); then
  bad "用户表为空 —— 恢复出来的库无法登录，说明导入有问题"
  VERIFY_OK=0
fi
# Flyway 的元数据表必须存在，否则控制面启动时会尝试重新迁移，
# 而目标库已有表 → 迁移失败 → 控制面起不来
if [[ "$(sql "SELECT COUNT(*) FROM ${TARGET_DB}.flyway_schema_history;")" =~ ^[0-9]+$ ]]; then
  ok "flyway_schema_history 存在（控制面可识别 schema 版本）"
else
  bad "缺 flyway_schema_history —— 控制面启动时会重复执行迁移"
  VERIFY_OK=0
fi
(( VERIFY_OK == 1 )) && ok "恢复结果校验通过"

# ---------------------------------------------------------------------------
echo
echo "[4/4] 收尾"
# ---------------------------------------------------------------------------
if [[ "$MODE" == "test" ]] && (( DROP_AFTER == 1 )); then
  docker exec trustagent-mysql sh -c \
    "exec mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -e 'DROP DATABASE \`${TEST_DB}\`;'" >/dev/null \
    && ok "临时库已删除"
else
  if [[ "$MODE" == "test" ]]; then
    echo
    echo "  临时库 ${TEST_DB} 保留，可人工核对："
    echo "    docker compose -f docker-compose.lowmem.yml exec mysql \\"
    echo "      mysql -uroot -p \"${TEST_DB}\" -e 'SELECT COUNT(*) FROM audit_log;'"
    echo "  核对完请删除，避免占用磁盘与内存："
    echo "    ./scripts/server/restore.sh --from ${FROM} --to-test --drop-after"
  else
    echo
    warn "已恢复到线上库。还需要你确认两件事（脚本不做，因为都无法自动决定）："
    echo "   1) Redis 队列**没有**被恢复：如果备份时还有 PENDING 任务，"
    echo "      它们在库里的状态与队列不一致，需要人工决定重跑或标失败。"
    echo "   2) 代码版本**没有**回滚：如果备份来自旧版本，请用 rollback.sh"
    echo "      把镜像 tag 也对齐，否则新代码跑旧 schema 会报错。"
  fi
fi

echo
echo "============================================================"
(( VERIFY_OK == 1 )) && echo " 恢复完成且校验通过" || { echo " 恢复完成但校验有问题（见上面的 [FAIL]）"; exit 1; }
echo "============================================================"
