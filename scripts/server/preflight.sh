#!/usr/bin/env bash
# ============================================================================
# 部署前检查：在**改动任何东西之前**判断这台机器能不能跑这套部署
#
# 只读操作：不装软件、不改配置、不建 swap、不动防火墙。
# 任何一项不满足都只报告，由人决定怎么处理。
#
# 用法：./scripts/server/preflight.sh
# ============================================================================

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

FAILED=0
note_fail() { FAILED=$(( FAILED + 1 )); bad "$*"; }

echo "============================================================"
echo " TrustAgent 部署前检查"
echo "============================================================"

# ---------------------------------------------------------------------------
echo
echo "[1/9] 系统与架构"
# ---------------------------------------------------------------------------
if [[ -r /etc/os-release ]]; then
  # shellcheck disable=SC1091
  . /etc/os-release
  ok "系统: ${PRETTY_NAME:-unknown}"
else
  warn "无法读取 /etc/os-release"
fi
ARCH="$(uname -m)"
ok "架构: $ARCH"
# 为什么必须查架构：本机开发用的是 Windows 上的 Python 虚拟环境，
# 那是 Windows 二进制，拷到 Linux 上完全不可用。
# 同理，在 arm64 机器上跑 amd64 镜像要么失败、要么走 QEMU 慢到不可用。
case "$ARCH" in
  x86_64|aarch64|arm64) ok "架构受支持" ;;
  *) note_fail "架构 $ARCH 未经验证，镜像可能无法运行" ;;
esac
ok "内核: $(uname -r)"

# ---------------------------------------------------------------------------
echo
echo "[2/9] CPU"
# ---------------------------------------------------------------------------
CPU_COUNT="$(nproc 2>/dev/null || echo 0)"
ok "可见 CPU 核数: $CPU_COUNT"
if (( CPU_COUNT < 1 )); then
  note_fail "无法识别 CPU 核数"
elif (( CPU_COUNT < 2 )); then
  warn "单核部署：Java + MySQL + Python 会互相争抢 CPU，评测耗时会明显变长（这是预期行为，不是故障）"
fi

# ---------------------------------------------------------------------------
echo
echo "[3/9] 内存（这是 1 核 2 GB 场景最可能卡住的地方）"
# ---------------------------------------------------------------------------
MEM_TOTAL_MB="$(awk '/MemTotal/ {printf "%d", $2/1024}' /proc/meminfo 2>/dev/null || echo 0)"
MEM_AVAIL_MB="$(awk '/MemAvailable/ {printf "%d", $2/1024}' /proc/meminfo 2>/dev/null || echo 0)"
SWAP_TOTAL_MB="$(awk '/SwapTotal/ {printf "%d", $2/1024}' /proc/meminfo 2>/dev/null || echo 0)"
ok "MemTotal:     ${MEM_TOTAL_MB} MiB"
ok "MemAvailable: ${MEM_AVAIL_MB} MiB"
ok "SwapTotal:    ${SWAP_TOTAL_MB} MiB"

# 容器内存上限合计（与 docker-compose.lowmem.yml 保持一致；改一处必须改另一处）
BUDGET_MB=$(( 512 + 512 + 192 + 160 + 96 + 64 ))
ok "容器上限合计: ${BUDGET_MB} MiB（backend 512 + mysql 512 + ai 192 + worker 160 + redis 96 + web 64）"

# 系统与 Docker 守护进程本身还需要约 400~500 MiB。
# 这部分不是"估计得保守一点"，而是这块盘上真实存在的开销：
# dockerd、containerd、以及 Linux 自身的页缓存管理。
RESERVED_MB=450
if (( MEM_TOTAL_MB < BUDGET_MB + RESERVED_MB )); then
  note_fail "内存不足：需要至少 $(( BUDGET_MB + RESERVED_MB )) MiB，实际 ${MEM_TOTAL_MB} MiB。"
  echo "         可选做法（都需要你确认，脚本不会自动执行）："
  echo "           a) 换 4 GB 机器——最省事，也是我实际推荐的；"
  echo "           b) 下调 mem_limit（优先 mysql->384m、backend->448m）并接受更紧的余量；"
  echo "           c) 只跑 MySQL+backend+web，不跑评测 Worker（放弃评测功能）。"
else
  ok "内存满足预算（含 ${RESERVED_MB} MiB 系统余量）"
fi
# 静态总量（上面的 MEM_TOTAL_MB）不满足时是**硬阻塞**：那是硬件事实，改不了。
# 但 MemAvailable 是**瞬时值**：受页缓存、别的容器恰好处于峰值、甚至一次 apt 升级影响。
# 把它写成硬 FAIL 会在"机器上跑着别的服务"时随机变红（实测踩到过），
# 而随机变红的门禁最后一定会被无视 —— 所以这里只警告，由人判断。
AVAIL_WARN_MB=500
if (( MEM_AVAIL_MB < AVAIL_WARN_MB )); then
  warn "当前 MemAvailable 只有 ${MEM_AVAIL_MB} MiB（< ${AVAIL_WARN_MB} MiB）—— 机器上已有其他进程在占用内存。"
  echo "         本部署 6 个容器的内存上限合计 1536 MiB，但这个数字**不是可用总量**："
  echo "         它要与系统、Docker 守护进程以及机器上已有的其他服务共享同一块物理内存。"
  echo "         实测参考：TrustAgent 六容器空闲时约 518 MiB。"
  echo "         请先用 docker ps / top 确认占用者；若占用者是本机其他长期服务，"
  echo "         可先停掉它再部署，或参考上面的 b) 下调 mem_limit。"
fi
if (( SWAP_TOTAL_MB == 0 )); then
  warn "没有 swap。这本身不是问题（本部署不依赖 swap），但意味着内存超限时是直接 OOMKill 而不是变慢。"
  echo "         **本脚本不会替你创建 swap**（不在授权范围内）。"
fi

# ---------------------------------------------------------------------------
echo
echo "[4/9] 磁盘"
# ---------------------------------------------------------------------------
DISK_AVAIL_GB="$(df -BG --output=avail "$PROJECT_ROOT" 2>/dev/null | tail -n 1 | tr -dc '0-9' || echo 0)"
ok "项目所在分区可用: ${DISK_AVAIL_GB} GB"
# 需要多少：镜像约 2.1 GB（含 maven 构建层，可回收约 900 MB）
#          + 数据卷（演示规模 <500 MB，随评测次数增长）
#          + 容器日志（已限 10 MB × 3 × 6 个容器 = 上限 180 MB）
#          + 备份（每次约 10~50 MB）
# 20 GB 是能启动的下限，40 GB 才有余量做备份与镜像更新。
if (( DISK_AVAIL_GB < 20 )); then
  note_fail "磁盘不足：至少需要 20 GB 可用，实际 ${DISK_AVAIL_GB} GB。"
else
  ok "磁盘满足最低要求（20 GB）"
fi

# ---------------------------------------------------------------------------
echo
echo "[5/9] Docker"
# ---------------------------------------------------------------------------
if command -v docker >/dev/null 2>&1; then
  ok "docker: $(docker --version 2>&1 | head -n 1)"
  if docker info >/dev/null 2>&1; then
    ok "docker 守护进程可用"
    ok "存储驱动: $(docker info --format '{{.Driver}}' 2>/dev/null)"
    ok "Docker 根目录: $(docker info --format '{{.DockerRootDir}}' 2>/dev/null)"
  else
    note_fail "docker 守护进程不可用（docker info 失败）。后续所有步骤都无法进行。"
  fi
  if docker compose version >/dev/null 2>&1; then
    ok "compose: $(docker compose version 2>&1 | head -n 1)"
  else
    note_fail "docker compose 插件缺失"
  fi
else
  note_fail "未安装 docker。安装系统软件不在本次授权范围内，需要你确认后再执行。"
fi

# ---------------------------------------------------------------------------
echo
echo "[6/9] 端口占用"
# ---------------------------------------------------------------------------
WEB_PORT="$(get_env TRUSTAGENT_WEB_PORT 8090)"
# 只需要一个宿主端口。检查它是否已被别人占用，
# 否则 compose 启动会失败在"端口已被分配"，而且报错发生在最后一步。
if command -v ss >/dev/null 2>&1; then
  if ss -ltn "( sport = :${WEB_PORT} )" 2>/dev/null | grep -q ":${WEB_PORT}"; then
    note_fail "端口 ${WEB_PORT} 已被占用。改 .env 里的 TRUSTAGENT_WEB_PORT，或先停掉占用它的服务。"
    echo "         占用者："
    ss -ltnp "( sport = :${WEB_PORT} )" 2>/dev/null | sed 's/^/           /' || true
  else
    ok "端口 ${WEB_PORT} 空闲"
  fi
else
  warn "未找到 ss 命令，跳过端口占用检查"
fi

# 明确检查"没有被别人先占用"的数据库端口。
# 本部署不会在宿主上开 3306/6379，但如果宿主上已经有别的 MySQL/Redis 在跑，
# 说明这台机器上还有其他项目 —— 那会影响内存预算，需要人工确认。
for p in 3306 6379; do
  if command -v ss >/dev/null 2>&1 && ss -ltn "( sport = :${p} )" 2>/dev/null | grep -q ":${p}"; then
    warn "宿主上已有服务监听 ${p} —— 这台机器上可能还有别的项目在跑。"
    echo "         本部署不会占用该端口，但请把它的内存占用计入可用内存评估。"
  fi
done

# ---------------------------------------------------------------------------
echo
echo "[7/9] 部署目录"
# ---------------------------------------------------------------------------
# 默认 /opt/trustagent。若已存在且不是本项目的旧版本，本次不覆盖，先报告。
DEPLOY_DIR="${TRUSTAGENT_DEPLOY_DIR:-/opt/trustagent}"
if [[ -e "$DEPLOY_DIR" ]]; then
  if [[ -f "$DEPLOY_DIR/docker-compose.lowmem.yml" ]]; then
    ok "$DEPLOY_DIR 已存在，且看起来是本项目的上一版部署（可原地升级）"
  else
    note_fail "$DEPLOY_DIR 已存在，但不是 TrustAgent 部署目录。**不会覆盖**，请人工确认。"
    echo "         目录内容（前 20 项）："
    ls -la "$DEPLOY_DIR" 2>/dev/null | head -n 20 | sed 's/^/           /' || true
  fi
else
  ok "$DEPLOY_DIR 尚不存在（部署时会创建）"
fi

# ---------------------------------------------------------------------------
echo
echo "[8/9] 环境变量文件"
# ---------------------------------------------------------------------------
if [[ -f "$ENV_FILE" ]]; then
  ok "$ENV_FILE 存在"
  MISSING=0
  for key in TRUSTAGENT_DB_ROOT_PASSWORD TRUSTAGENT_DB_USERNAME TRUSTAGENT_DB_PASSWORD \
             TRUSTAGENT_REDIS_PASSWORD TRUSTAGENT_JWT_SECRET TRUSTAGENT_INTERNAL_TOKEN \
             TRUSTAGENT_IMAGE_TAG; do
    if is_configured "$key"; then
      ok "$key 已配置（值不显示）"
    else
      bad "$key 未配置或仍是示例值"
      MISSING=$(( MISSING + 1 ))
    fi
  done
  (( MISSING == 0 )) || note_fail "有 ${MISSING} 个必需变量未配置"

  # 数据库应用账号不得是 root
  DB_USER="$(get_env TRUSTAGENT_DB_USERNAME "")"
  if [[ "$DB_USER" == "root" ]]; then
    note_fail "TRUSTAGENT_DB_USERNAME 不能是 root：应用账号应只有自己那个库的权限"
  fi
  # JWT 密钥长度：HS256 的密钥短于 32 字节可被离线爆破，
  # 而 Java 侧虽然也会拒绝启动，但在这里先报出来能省一轮"起来了又挂掉"的排查。
  if is_configured TRUSTAGENT_JWT_SECRET; then
    JWT_VAL="$(get_env TRUSTAGENT_JWT_SECRET "")"
    if (( ${#JWT_VAL} < 32 )); then
      note_fail "TRUSTAGENT_JWT_SECRET 长度不足 32 字节（HS256 弱密钥可离线爆破）"
    else
      ok "TRUSTAGENT_JWT_SECRET 长度满足要求"
    fi
  fi
else
  warn "$ENV_FILE 尚不存在。部署前必须生成（见 docs/DEPLOYMENT.md）。"
fi

# ---------------------------------------------------------------------------
echo
echo "[9/9] 部署产物（服务器上不构建，只运行预构建产物）"
# ---------------------------------------------------------------------------
# 镜像 tag 必须明确；构建在本地/CI 完成。
TAG="$(get_env TRUSTAGENT_IMAGE_TAG "")"
if [[ -n "$TAG" ]]; then
  for img in "trustagent-backend:${TAG}" "trustagent-ai-service:${TAG}" "trustagent-web:${TAG}"; do
    if docker image inspect "$img" >/dev/null 2>&1; then
      SIZE="$(docker image inspect "$img" --format '{{.Size}}' 2>/dev/null | awk '{printf "%.0f", $1/1024/1024}')"
      ok "已加载镜像 $img (${SIZE} MiB)"
    else
      warn "尚未加载镜像 $img（deploy.sh 会尝试从 deploy/images/ 导入）"
    fi
  done
else
  warn "未设置 TRUSTAGENT_IMAGE_TAG，跳过镜像检查"
fi
if [[ -d "$PROJECT_ROOT/web/dist" ]]; then
  ok "前端构建产物存在（web/dist）"
else
  warn "缺少 web/dist：web 镜像无法构建/加载"
fi

# ---------------------------------------------------------------------------
echo
echo "============================================================"
if (( FAILED == 0 )); then
  echo " 结论：未发现阻塞项，可以继续 deploy.sh"
  echo " 注意：本脚本只做检查，不代表部署已经验证通过。"
else
  echo " 结论：发现 ${FAILED} 个阻塞项，请先处理（见上面的 [FAIL] 行）"
fi
echo "============================================================"
exit 0
