#!/usr/bin/env bash
# ============================================================================
# 发布 / 升级
#
# 行为约定（每一条都有原因）：
#   * 服务器上**不构建**：只导入预构建镜像（deploy/images/*.tar）。
#     1 核 2 GB 上跑 Maven 打包或 vue-tsc 会与 MySQL/JVM 抢内存，把整机拖到 OOM。
#   * 发布顺序是"先停旧版，再起新版"，避免两套服务同时占用内存。
#   * 启动新控制面前先备份数据库：Flyway 迁移是单向的，
#     **代码回滚不等于数据库回滚**（见 rollback.sh 的说明）。
#   * 全程不使用 down -v / prune：停止服务不删除数据。
#
# 用法：
#   ./scripts/server/deploy.sh              # 常规发布
#   ./scripts/server/deploy.sh --skip-backup  # 明确知道不需要备份时（首次部署可用）
# ============================================================================

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

SKIP_BACKUP=0
[[ "${1:-}" == "--skip-backup" ]] && SKIP_BACKUP=1

require_env_file
require_docker
TAG="$(require_image_tag)"

echo "============================================================"
echo " TrustAgent 发布（镜像 tag: ${TAG}）"
echo "============================================================"

# ---------------------------------------------------------------------------
echo
echo "[1/6] 导入预构建镜像"
# ---------------------------------------------------------------------------
# 镜像来源二选一：
#   a) 已经在本地 docker 里（例如 CI 直接推送/加载过）
#   b) deploy/images/ 下的 tar 包（本地构建后 scp 上来）
IMAGES_DIR="$PROJECT_ROOT/deploy/images"
LOADED=0
for name in trustagent-backend trustagent-ai-service trustagent-web; do
  if docker image inspect "${name}:${TAG}" >/dev/null 2>&1; then
    ok "${name}:${TAG} 已存在"
  else
    tarball="$IMAGES_DIR/${name}-${TAG}.tar"
    if [[ -f "$tarball" ]]; then
      log "导入 $(basename "$tarball") ..."
      docker load -i "$tarball" >/dev/null || die "导入 $tarball 失败"
      ok "${name}:${TAG} 已导入"
      LOADED=$(( LOADED + 1 ))
    else
      die "缺少镜像 ${name}:${TAG}，且 $tarball 不存在。请先在本地执行 scripts/build-release.ps1。"
    fi
  fi
done
(( LOADED == 0 )) || log "本次新导入 ${LOADED} 个镜像"

# 记录产物校验值：只有记录了"这一版到底是哪一版"，回滚和排查才有依据
{
  echo "# 发布于 $(date -Iseconds)  tag=${TAG}"
  for name in trustagent-backend trustagent-ai-service trustagent-web; do
    docker image inspect "${name}:${TAG}" --format '{{.Id}}' 2>/dev/null \
      | sed "s|^|${name}:${TAG} sha256=|"
  done
} >> "$STATE_DIR/release.log"
ok "产物指纹已记入 .local/server/release.log"

# ---------------------------------------------------------------------------
echo
echo "[2/6] 数据库迁移前备份"
# ---------------------------------------------------------------------------
# 为什么必须在这里备份：Flyway 在控制面启动时执行迁移。
# 一旦迁移执行了（例如加了唯一索引、改了列类型），旧版代码不一定还能对上新 schema。
# "代码回滚"只把 jar 换回去了，数据库还在新版本上——所以要先留一份可恢复的快照。
MYSQL_RUNNING=0
if docker ps --format '{{.Names}}' | grep -qx 'trustagent-mysql'; then
  MYSQL_RUNNING=1
fi

if (( SKIP_BACKUP == 1 )); then
  warn "按参数跳过备份。仅在'库中还没有任何数据'（首次部署）时才应该这样做。"
elif (( MYSQL_RUNNING == 1 )); then
  log "检测到 MySQL 正在运行，先备份当前数据库 ..."
  "$SCRIPT_DIR/backup.sh" --tag "pre-deploy-${TAG}" || die "备份失败。发布已中止——带着一个失败的备份去跑迁移是不必要的风险。"
else
  log "MySQL 未运行（首次部署），无需备份。"
fi

# ---------------------------------------------------------------------------
echo
echo "[3/6] 停止旧版本"
# ---------------------------------------------------------------------------
# down 会移除容器但**保留命名卷**（mysql-data / redis-data）。
# 这里刻意不加 -v：加 -v 会连数据卷一起删，那是不可逆的。
# 也刻意不做全局 docker system prune：那会删掉这台机器上其他项目的镜像。
if (( MYSQL_RUNNING == 1 )) || docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" ps -q 2>/dev/null | grep -q .; then
  log "停止现有容器（保留数据卷）..."
  compose down --remove-orphans
  ok "旧版本已停止"
else
  ok "没有正在运行的旧版本"
fi

# ---------------------------------------------------------------------------
echo
echo "[4/6] 配置校验"
# ---------------------------------------------------------------------------
compose config -q || die "docker-compose.lowmem.yml 配置校验未通过"
ok "编排文件语法与变量替换正确"

# ---------------------------------------------------------------------------
echo
echo "[5/6] 启动新版本"
# ---------------------------------------------------------------------------
# 刻意加 --no-build：compose 里每个服务都带 build: 段（镜像由本地/CI 构建，
# 服务器只负责导入）。若不带这个开关，某次镜像缺失/被清理时，compose 会
# **在这台 1 核 2 GB 的机器上静默触发构建**（pip install / apt-get install），
# 那正是这套部署刻意要避免的事，而且表现为"部署卡住/被 OOMKill"。
# 带上它，缺镜像就是明确报错（上面第 1 步已经保证了镜像存在）。
log "docker compose up -d --no-build ..."
compose up -d --no-build || die "启动失败。请查看：compose logs --tail=80"

# ---------------------------------------------------------------------------
echo
echo "[6/6] 等待各服务就绪"
# ---------------------------------------------------------------------------
# 顺序与依赖一致：MySQL/Redis → 执行面 → 控制面 → 网关
WAIT_FAILED=0
wait_healthy mysql 180       || WAIT_FAILED=1
wait_healthy redis 60        || WAIT_FAILED=1
wait_healthy ai-service 90   || WAIT_FAILED=1
wait_healthy backend 240     || WAIT_FAILED=1
wait_healthy web 60          || WAIT_FAILED=1

# Worker 没有健康检查（它没有端口），单独判断"进程是否稳定存活"
sleep 5
if docker inspect -f '{{.State.Running}}' trustagent-ai-worker 2>/dev/null | grep -q true; then
  ok "ai-worker: running"
else
  bad "ai-worker 未在运行（它没有端口，请用 compose logs ai-worker 看原因）"
  WAIT_FAILED=1
fi

echo
echo "============================================================"
if (( WAIT_FAILED == 0 )); then
  echo " 发布完成：tag=${TAG}"
  echo
  echo " 下一步："
  echo "   1) ./scripts/server/smoke.sh        冒烟验证"
  echo "   2) ./scripts/server/status.sh       查看资源占用与端口绑定"
  echo
  echo " 访问方式（私有模式，本机执行 SSH 隧道）："
  WEB_PORT="$(get_env TRUSTAGENT_WEB_PORT 8090)"
  echo "   ssh -N -L ${WEB_PORT}:127.0.0.1:${WEB_PORT} <user>@<server>"
  echo "   然后浏览器打开 http://localhost:${WEB_PORT}"
else
  echo " 启动完成但**有服务未就绪**。请执行："
  echo "   ./scripts/server/status.sh"
  echo "   docker compose -f docker-compose.lowmem.yml logs --tail=120"
  echo
  echo " 常见原因："
  echo "   * 内存不足导致容器被 OOMKilled（status.sh 会直接标出来）"
  echo "   * MySQL 首次初始化较慢（首次启动需要 1~2 分钟），可再等一会重试"
  exit 1
fi
echo "============================================================"
