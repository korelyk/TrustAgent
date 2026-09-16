#!/usr/bin/env bash
# ============================================================================
# 备份：MySQL 逻辑备份 + Redis AOF + 环境变量文件
#
# 备份到哪里：默认 .local/backups/<时间戳>[-tag]/
# 目录权限 0700，其中 env.bak 权限 0600（它含全部密钥）。
#
# 为什么 Redis 也要备份：
#   Redis 里存着 eval.jobs（评测任务队列）。当前实现**没有**"从 MySQL 的
#   PENDING 状态重新入队"的补偿路径，所以队列丢了这个任务就再也不会被执行。
#   备份 Redis 是保住这条链路的直接手段。
#
# 用法：
#   ./scripts/server/backup.sh                  # 备份
#   ./scripts/server/backup.sh --tag pre-v2     # 带标签，便于回滚时辨认
#   ./scripts/server/backup.sh --keep 10        # 备份后只保留最近 10 份
# ============================================================================

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

TAG=""
KEEP=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag)  TAG="$2"; shift 2 ;;
    --keep) KEEP="$2"; shift 2 ;;
    *) die "未知参数: $1" ;;
  esac
done

require_docker
require_env_file

STAMP="$(date +%Y%m%d-%H%M%S)"
DIR_NAME="trustagent-${STAMP}${TAG:+-${TAG}}"
DEST="$BACKUP_DIR/$DIR_NAME"

mkdir -p "$DEST"
chmod 700 "$BACKUP_DIR" "$DEST"

echo "============================================================"
echo " 备份到 ${DEST#$PROJECT_ROOT/}"
echo "============================================================"

DB_NAME="$(get_env TRUSTAGENT_DB_NAME trustagent)"
DB_USER="$(get_env TRUSTAGENT_DB_USERNAME trustagent)"

# ---------------------------------------------------------------------------
echo
echo "[1/4] MySQL 逻辑备份"
# ---------------------------------------------------------------------------
mysql_running() {
  docker inspect trustagent-mysql >/dev/null 2>&1 && \
  [[ "$(docker inspect -f '{{.State.Status}}' trustagent-mysql)" == "running" ]]
}

if mysql_running; then
  log "mysqldump ${DB_NAME} ..."
  # 口令在容器内部通过环境变量取用：
  #   * 不写进命令行（命令行会出现在宿主的 ps 输出里）
  #   * 不写进脚本、不写进日志
  # 用 --single-transaction 保证 InnoDB 表的一致性快照，
  # 不需要锁表（那是 MyISAM 时代的做法，会阻塞线上写入）。
  # 刻意**不备份 mysql 系统库**：那是 MySQL 自身的元数据，
  # 恢复它需要 root 且容易把目标实例的账号体系搞坏；
  # 应用只需要自己那个库，账号由 compose 的 MYSQL_USER/MYSQL_PASSWORD 重建。
  if docker exec -i trustagent-mysql sh -c \
      'exec mysqldump --single-transaction --quick --routines --events \
         -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE"' \
      > "$DEST/db.sql" 2>"$DEST/db.dump.err"; then
    gzip -f "$DEST/db.sql"
    ok "db.sql.gz ($(du -h "$DEST/db.sql.gz" | cut -f1))"
    rm -f "$DEST/db.dump.err"
  else
    bad "mysqldump 失败，详见 $DEST/db.dump.err"
    head -n 5 "$DEST/db.dump.err" 2>/dev/null | sed 's/^/       /' || true
    exit 1
  fi
else
  warn "MySQL 未运行，跳过数据库备份（不是错误：首次部署前本来就没有数据）"
  echo "skipped: mysql not running" > "$DEST/db.SKIPPED"
fi

# ---------------------------------------------------------------------------
echo
echo "[2/4] Redis 备份"
# ---------------------------------------------------------------------------
if docker inspect trustagent-redis >/dev/null 2>&1 && \
   [[ "$(docker inspect -f '{{.State.Status}}' trustagent-redis)" == "running" ]]; then

  # 先触发 AOF 重写并等它结束，再复制文件。
  # 为什么不直接 docker cp：AOF 的增量文件正在被追加写，
  # 直接复制可能拿到"清单文件指向了一个还没写完的增量"，
  # 这种备份在恢复时才会报错，属于最坏的一类问题。
  log "触发 AOF 重写并等待完成 ..."
  docker exec trustagent-redis sh -c \
    'redis-cli --no-auth-warning -a "$REDIS_PASSWORD" BGREWRITEAOF' >/dev/null 2>&1 || true

  waited=0
  while (( waited < 60 )); do
    in_progress="$(docker exec trustagent-redis sh -c \
      'redis-cli --no-auth-warning -a "$REDIS_PASSWORD" INFO persistence' 2>/dev/null \
      | grep -E '^aof_rewrite_in_progress:' | tr -d '\r' | cut -d: -f2 || echo 0)"
    [[ "$in_progress" == "0" ]] && break
    sleep 2; waited=$(( waited + 2 ))
  done
  if (( waited >= 60 )); then
    warn "AOF 重写 60s 未完成，仍继续复制（备份可能是重写中途的状态）"
  else
    ok "AOF 重写已完成"
  fi

  # 记录队列水位：恢复后要拿这个数字对照，才能知道有没有丢消息
  STREAM_LEN="$(docker exec trustagent-redis sh -c \
    'redis-cli --no-auth-warning -a "$REDIS_PASSWORD" XLEN eval.jobs' 2>/dev/null || echo '?')"
  DLQ_LEN="$(docker exec trustagent-redis sh -c \
    'redis-cli --no-auth-warning -a "$REDIS_PASSWORD" XLEN eval.jobs.dlq' 2>/dev/null || echo '?')"

  # Redis 7 的 AOF 放在 appendonlydir/ 下（多段式 AOF）
  if docker exec trustagent-redis test -d /data/appendonlydir 2>/dev/null; then
    docker exec trustagent-redis tar -C /data -czf - appendonlydir > "$DEST/redis-appendonly.tar.gz" 2>/dev/null \
      && ok "redis-appendonly.tar.gz ($(du -h "$DEST/redis-appendonly.tar.gz" | cut -f1))" \
      || { bad "打包 Redis AOF 失败"; exit 1; }
  else
    warn "未找到 /data/appendonlydir，跳过（可能 AOF 尚未生成）"
  fi

  {
    echo "eval.jobs 长度: ${STREAM_LEN}"
    echo "eval.jobs.dlq 长度: ${DLQ_LEN}"
  } > "$DEST/redis-queue-watermark.txt"
  ok "队列水位已记录（eval.jobs=${STREAM_LEN}, dlq=${DLQ_LEN}）"
else
  warn "Redis 未运行，跳过"
  echo "skipped: redis not running" > "$DEST/redis.SKIPPED"
fi

# ---------------------------------------------------------------------------
echo
echo "[3/4] 环境变量文件"
# ---------------------------------------------------------------------------
# 备份 .env 的理由：JWT 密钥变了会导致所有已签发 Token 失效；
# 数据库口令变了需要同步改 MySQL 账号。只恢复数据不恢复密钥，服务起不来。
# 代价是这个文件含全部密钥，所以：
#   * 目录 0700、文件 0600
#   * **绝不放进 Git**（.local/ 已在 .gitignore 里）
if [[ -f "$ENV_FILE" ]]; then
  install -m 600 "$ENV_FILE" "$DEST/env.bak"
  ok "env.bak（权限 600；含密钥，请按密钥对待）"
else
  warn "未找到 .env，跳过"
fi

# ---------------------------------------------------------------------------
echo
echo "[4/4] 元信息与校验值"
# ---------------------------------------------------------------------------
{
  echo "备份时间: $(date -Iseconds)"
  echo "镜像 tag: $(get_env TRUSTAGENT_IMAGE_TAG '未设置')"
  echo "宿主机: $(uname -n) $(uname -m)"
  echo "数据库名: ${DB_NAME}"
  echo
  echo "--- 文件校验值 (sha256) ---"
  (cd "$DEST" && for f in *; do
     [[ -f "$f" ]] && printf '%s  %s\n' "$(sha256sum "$f" | cut -d' ' -f1)" "$f"
   done)
} > "$DEST/META.txt"
ok "META.txt 已生成"

# ---------------------------------------------------------------------------
echo
echo "[清理] 保留策略"
# ---------------------------------------------------------------------------
if (( KEEP > 0 )); then
  # 只删本目录下、符合命名规则的旧备份；不做任何全局清理。
  mapfile -t OLD < <(ls -1dt "$BACKUP_DIR"/trustagent-* 2>/dev/null | tail -n "+$(( KEEP + 1 ))")
  if (( ${#OLD[@]} > 0 )); then
    for d in "${OLD[@]}"; do
      log "删除旧备份 $(basename "$d")"
      rm -rf "$d"
    done
  fi
  ok "已保留最近 ${KEEP} 份备份"
else
  log "未指定 --keep，保留全部备份"
fi

echo
echo "============================================================"
echo " 备份完成：${DEST#$PROJECT_ROOT/}"
echo
echo " 校验备份可用性（不会覆盖现有数据）："
echo "   ./scripts/server/restore.sh --from ${DIR_NAME} --to-test"
echo "============================================================"
