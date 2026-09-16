#!/usr/bin/env bash
# ============================================================================
# 停止服务
#
# 默认只"停容器"，不删容器、**绝不删数据卷**。
# 数据卷（mysql-data / redis-data）里是全部业务数据，删掉不可恢复。
#
# 用法：
#   ./scripts/server/stop.sh            # 停止（容器保留，随时可 start 回来）
#   ./scripts/server/stop.sh --down     # 停止并移除容器（数据卷仍然保留）
#
# 刻意不提供的选项：
#   * docker compose down -v     —— 会连数据卷一起删。本脚本拒绝实现它。
#   * docker system prune        —— 会删掉这台机器上**其他项目**的镜像与网络。
#     这台服务器上的东西不止本项目，全局清理是不可接受的。
# ============================================================================

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

MODE="stop"
[[ "${1:-}" == "--down" ]] && MODE="down"

require_docker

echo "============================================================"
echo " 停止 TrustAgent（模式: ${MODE}）"
echo "============================================================"

# 先把当前状态记下来，方便确认"停之前确实是好的"
RUNNING="$(docker ps --filter 'name=trustagent-' --format '{{.Names}}' | tr '\n' ' ')"
if [[ -z "$RUNNING" ]]; then
  ok "当前没有正在运行的 TrustAgent 容器"
else
  ok "正在运行: ${RUNNING}"
fi

if [[ "$MODE" == "down" ]]; then
  # down 会移除容器与网络，保留命名卷。
  # --remove-orphans 清理掉改过服务名后残留的容器，避免它们占着内存端口。
  compose down --remove-orphans
  ok "容器已移除"
else
  # stop 走优雅停机：compose 发 SIGTERM，Spring Boot 的 graceful shutdown
  # 会把手上的请求与事务做完。直接 kill 可能让正在写的
  # "评测结果 + 审计 + 幂等"小事务半途中断。
  compose stop
  ok "容器已停止（未删除）"
fi

echo
echo "[数据卷检查]"
# 明确列出来，让"数据还在"这件事是可验证的，而不是靠信任脚本
for vol in trustagent-demo_mysql-data trustagent-demo_redis-data; do
  if docker volume inspect "$vol" >/dev/null 2>&1; then
    ok "数据卷保留: $vol"
  else
    warn "数据卷 $vol 不存在（可能从未创建过）"
  fi
done

echo
echo "============================================================"
echo " 停止完成。数据卷未被删除。"
echo
echo " 重新启动：./scripts/server/deploy.sh"
echo " 彻底清理（不可逆，需你手动执行）："
echo "   docker compose -f docker-compose.lowmem.yml down -v"
echo "============================================================"
