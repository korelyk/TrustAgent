#!/usr/bin/env bash
# ============================================================================
# 回滚
#
# ⚠️ 最重要的一句话：**回滚代码 ≠ 回滚数据库。**
#   本脚本只把镜像 tag 换回上一版，然后重新发布。
#   Flyway 的迁移是单向的（没有 undo 脚本），所以如果上一版启动过、
#   执行了迁移，数据库 schema 仍然停在"新"的形态上，而旧版代码是按旧 schema 写的。
#   结果可能是：旧版启动时报"迁移校验失败"，或者启动了但查询报列不存在。
#
#   真正的完整回滚需要两步：
#     1) ./scripts/server/rollback.sh --to <旧tag>       （换代码）
#     2) ./scripts/server/restore.sh --from <迁移前的备份> --to-live --yes-i-know
#        （把数据也退回去；这需要发布前那次自动备份，见 deploy.sh 的第 2 步）
#
#   这也是 deploy.sh 为什么坚持"迁移前先备份"：没有那份备份，
#   第二步就做不了，回滚只能停在"半吊子"状态。
#
# 用法：
#   ./scripts/server/rollback.sh --list           # 看有哪些可用版本
#   ./scripts/server/rollback.sh --to <tag>       # 切到指定版本并重新发布
#   ./scripts/server/rollback.sh --previous       # 切到上一个版本
# ============================================================================

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

RELEASE_LOG="$STATE_DIR/release.log"
ACTION=""
TARGET=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --list)     ACTION="list"; shift ;;
    --to)       ACTION="to"; TARGET="$2"; shift 2 ;;
    --previous) ACTION="previous"; shift ;;
    *) die "未知参数: $1" ;;
  esac
done

[[ -n "$ACTION" ]] || die "请指定 --list / --to <tag> / --previous"

require_docker

# 从发布记录里按时间倒序取出所有 tag（去重）
available_tags() {
  [[ -f "$RELEASE_LOG" ]] || return 0
  grep -oE 'tag=[^ ]+' "$RELEASE_LOG" | sed 's/^tag=//' | tac | awk '!seen[$0]++'
}

CURRENT_TAG="$(get_env TRUSTAGENT_IMAGE_TAG '未设置')"

if [[ "$ACTION" == "list" ]]; then
  echo "============================================================"
  echo " 可用版本（来自 .local/server/release.log）"
  echo "============================================================"
  echo "  当前使用: ${CURRENT_TAG}"
  echo
  if [[ ! -f "$RELEASE_LOG" ]]; then
    warn "还没有发布记录（release.log 不存在）。首次部署后才会产生。"
  else
    echo "  历史版本（新 -> 旧）："
    available_tags | sed 's/^/    /'
    echo
    echo "  本机已加载的镜像："
    docker images --format '{{.Repository}}:{{.Tag}}' 2>/dev/null \
      | grep -E '^trustagent-' | sort -u | sed 's/^/    /' || true
  fi
  exit 0
fi

if [[ "$ACTION" == "previous" ]]; then
  # 取"当前版本之后的第一个"，也就是上一次发布的版本
  TARGET="$(available_tags | awk -v cur="$CURRENT_TAG" 'found {print; exit} $0==cur {found=1}')"
  if [[ -z "$TARGET" ]]; then
    # 当前 tag 不在记录里（例如手工改过），退化为取第一个不等于当前的
    TARGET="$(available_tags | awk -v cur="$CURRENT_TAG" '$0!=cur {print; exit}')"
  fi
  [[ -n "$TARGET" ]] || die "找不到可回滚的上一版本。请用 --list 查看，或手工指定 --to <tag>。"
fi

echo "============================================================"
echo " 回滚：${CURRENT_TAG}  ->  ${TARGET}"
echo "============================================================"

# ---------------------------------------------------------------------------
echo
echo "[1/4] 检查目标版本镜像是否存在"
# ---------------------------------------------------------------------------
MISSING=0
for name in trustagent-backend trustagent-ai-service trustagent-web; do
  if docker image inspect "${name}:${TARGET}" >/dev/null 2>&1; then
    ok "${name}:${TARGET}"
  else
    bad "${name}:${TARGET} 不在本机"
    MISSING=$(( MISSING + 1 ))
  fi
done
if (( MISSING > 0 )); then
  echo
  echo "  目标版本的镜像已被清理。可选做法："
  echo "   * 从本地重新推送该版本镜像后重试；"
  echo "   * 或选择 --list 里仍然存在的版本。"
  echo "  **不会**尝试用当前版本冒充目标版本继续执行——那会得到一个"
  echo "   '看起来回滚成功、实际跑的还是新版' 的最坏结果。"
  exit 1
fi

# ---------------------------------------------------------------------------
echo
echo "[2/4] 回滚前的数据库备份"
# ---------------------------------------------------------------------------
# 回滚同样要备份：回滚本身也可能失败，而"失败后想再回滚回去"需要此刻的数据。
if docker inspect trustagent-mysql >/dev/null 2>&1 && \
   [[ "$(docker inspect -f '{{.State.Status}}' trustagent-mysql)" == "running" ]]; then
  "$SCRIPT_DIR/backup.sh" --tag "pre-rollback-to-${TARGET}" \
    || die "备份失败，已中止回滚"
else
  warn "MySQL 未运行，跳过备份"
fi

# ---------------------------------------------------------------------------
echo
echo "[3/4] 切换镜像版本"
# ---------------------------------------------------------------------------
set_env_var TRUSTAGENT_IMAGE_TAG "$TARGET"
ok "TRUSTAGENT_IMAGE_TAG=${TARGET} 已写入 .env"

# ---------------------------------------------------------------------------
echo
echo "[4/4] 重新发布"
# ---------------------------------------------------------------------------
# --skip-backup：上一步刚备份过，deploy.sh 里那次就不必重复做
"$SCRIPT_DIR/deploy.sh" --skip-backup || {
  bad "回滚发布失败。当前 .env 里的 tag 是 ${TARGET}，"
  echo "         如需退回原版本：./scripts/server/rollback.sh --to ${CURRENT_TAG}"
  exit 1
}

echo
echo "============================================================"
echo " 回滚完成：现在运行的是 ${TARGET}"
echo
echo " 提醒（这不是可以略过的注意事项）："
echo "   * 数据库**没有**回滚。若旧版代码与新 schema 不兼容，"
echo "     需要用迁移前的那份备份做 restore.sh --to-live。"
echo "   * Redis 队列里的评测任务**没有**回滚。"
echo "     旧版 Worker 可能不认识新版写入的消息字段，"
echo "     这些任务会失败并进死信（这是可接受的失败方式：不会静默丢）。"
echo
echo " 验证：./scripts/server/smoke.sh"
echo "============================================================"
