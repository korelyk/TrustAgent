#!/usr/bin/env bash
# ============================================================================
# 服务器端脚本的公共库（被 source，不单独执行）
#
# 约定：
#   * 所有脚本 set -euo pipefail，出错即停，不"继续尝试"。
#   * **绝不把 .env 的值打印到终端或日志**。需要判断"是否已配置"时，
#     只输出"已配置/未配置"，不输出值本身。
#   * .env 用逐行解析而不是 source：
#       .env 里的 JDBC URL 形如 "...?a=1&b=2"，未加引号时 `source` 会把 & 当成
#       后台执行符，轻则变量被截断，重则执行出意料之外的命令。
#     docker compose 自己会读 --env-file，脚本里只在需要判断时才取值。
#   * 文件编码必须是 UTF-8 **不带 BOM**、行尾必须是 LF。
#     带 BOM 时内核找不到 `#!/usr/bin/env bash` 中的 bash，报 "No such file or directory"，
#     而报错信息完全指不到 BOM 这个原因。
# ============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
COMPOSE_FILE="$PROJECT_ROOT/docker-compose.lowmem.yml"
ENV_FILE="$PROJECT_ROOT/.env"
STATE_DIR="$PROJECT_ROOT/.local/server"
BACKUP_DIR="${TRUSTAGENT_BACKUP_DIR:-$PROJECT_ROOT/.local/backups}"
COMPOSE_PROJECT="trustagent-demo"

# ---------------------------------------------------------------------------
# 输出。刻意不用颜色：服务器上常把输出重定向到文件，ANSI 码会让日志变成乱码。
# ---------------------------------------------------------------------------
log()  { printf '[%s] %s\n' "$(date '+%H:%M:%S')" "$*"; }
ok()   { printf '  [OK]   %s\n' "$*"; }
bad()  { printf '  [FAIL] %s\n' "$*" >&2; }
warn() { printf '  [WARN] %s\n' "$*" >&2; }
die()  { printf '  [FATAL] %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# .env 读取
# ---------------------------------------------------------------------------

# 从 .env 取一个键的值。**只用于判断与拼命令，不要 echo 出来。**
get_env() {
  local key="$1" fallback="${2:-}"
  [[ -f "$ENV_FILE" ]] || { printf '%s' "$fallback"; return 0; }
  local line
  line="$(grep -E "^${key}=" "$ENV_FILE" | tail -n 1 || true)"
  [[ -n "$line" ]] || { printf '%s' "$fallback"; return 0; }
  local value="${line#*=}"
  # 去掉可能存在的成对引号（compose 的 env_file 会剥掉，脚本里也要一致）
  value="${value%\"}"; value="${value#\"}"
  value="${value%\'}"; value="${value#\'}"
  printf '%s' "$value"
}

# 键是否已配置且不是示例值/占位值
is_configured() {
  local value
  value="$(get_env "$1" "")"
  [[ -n "$value" ]] || return 1
  case "$value" in
    changeme|CHANGEME|change-me|example|EXAMPLE|placeholder|your-*) return 1 ;;
  esac
  return 0
}

require_env_file() {
  [[ -f "$ENV_FILE" ]] || die "未找到 $ENV_FILE。请先按 docs/DEPLOYMENT.md 生成服务器 .env（不要复用本机开发用的那一份）。"
  # .env 必须是 0600：里面有 JWT 密钥、数据库口令、内部 Token
  local perms
  perms="$(stat -c '%a' "$ENV_FILE" 2>/dev/null || echo '')"
  if [[ -n "$perms" && "$perms" != "600" ]]; then
    warn "$ENV_FILE 权限为 $perms，建议 chmod 600（当前同机其他用户可读）"
  fi
}

# ---------------------------------------------------------------------------
# docker compose 封装
# ---------------------------------------------------------------------------
compose() {
  docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" "$@"
}

require_docker() {
  command -v docker >/dev/null 2>&1 || die "未安装 docker"
  docker info >/dev/null 2>&1 || die "docker 守护进程不可用（docker info 失败）。请先确认 Docker 已启动且当前用户有权限。"
  docker compose version >/dev/null 2>&1 || die "docker compose 插件不可用"
}

# 版本号注入：镜像 tag 必须是明确值，不允许 latest。
require_image_tag() {
  local tag
  tag="$(get_env TRUSTAGENT_IMAGE_TAG "")"
  [[ -n "$tag" ]] || die "未设置 TRUSTAGENT_IMAGE_TAG。镜像必须使用明确版本，不能依赖 latest（见 docs/DEPLOYMENT.md）。"
  [[ "$tag" != "latest" ]] || die "TRUSTAGENT_IMAGE_TAG 不允许为 latest：出问题时无法判断线上跑的是哪一版。"
  printf '%s' "$tag"
}

# 等待某个容器进入 healthy（或直接 running，用于无健康检查的服务）
wait_healthy() {
  local service="$1" timeout="${2:-180}" elapsed=0 status
  while (( elapsed < timeout )); do
    status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
      "trustagent-${service}" 2>/dev/null || echo 'missing')"
    case "$status" in
      healthy|running) ok "$service: $status"; return 0 ;;
      missing) ;;
    esac
    sleep 3; elapsed=$(( elapsed + 3 ))
  done
  bad "$service 在 ${timeout}s 内未就绪（最后状态: ${status:-unknown}）"
  return 1
}

# ---------------------------------------------------------------------------
# 就地修改 .env 中的某个键（用于切换镜像 tag）
#
# 为什么不用 `source` + 重写：.env 里有注释、空行、以及含 & 的 JDBC URL，
# 重写整份文件很容易把格式搞坏（而 .env 坏了整栈都起不来）。
# 这里只做"整行替换"，文件其余部分原样保留。
#
# 保持原文件权限：.env 是 600，用 mktemp 生成的新文件默认也是 600，
# 但仍显式对齐一次，避免 umask 把权限放宽。
# ---------------------------------------------------------------------------
set_env_var() {
  local key="$1" value="$2" tmp
  [[ -f "$ENV_FILE" ]] || die "未找到 $ENV_FILE"
  tmp="$(mktemp)"
  if grep -qE "^${key}=" "$ENV_FILE"; then
    # 用 | 作分隔符，避免值里出现 / 时出错
    sed -E "s|^${key}=.*|${key}=${value}|" "$ENV_FILE" > "$tmp"
  else
    cp "$ENV_FILE" "$tmp"
    printf '%s=%s\n' "$key" "$value" >> "$tmp"
  fi
  chmod 600 "$tmp"
  mv "$tmp" "$ENV_FILE"
}

mkdir -p "$STATE_DIR" "$BACKUP_DIR" 2>/dev/null || true
