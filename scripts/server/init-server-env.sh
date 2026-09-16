#!/usr/bin/env bash
# ============================================================================
# 在服务器上生成部署用的 .env（含随机强密钥）
#
# 设计要点：
#   * **不覆盖已有 .env**：里面是这套部署的全部密钥。覆盖它 = 数据库连不上、
#     JWT 全部失效、内部接口认证失败，而且这些都不是"重启一下就好了"的问题。
#     要重建请自己先备份并删除，脚本不会替你决定。
#   * **不打印任何密钥**：生成完只报告"哪些键已写入"，不回显值。
#     否则密钥会进入 shell 历史、CI 日志、以及你的聊天记录。
#   * 口令用 openssl rand（密码学安全随机），不是 $RANDOM。
#   * 生成后立刻 chmod 600。
#
# 用法：
#   ./scripts/server/init-server-env.sh
#   ./scripts/server/init-server-env.sh --force   # 显式覆盖（会先备份旧的）
# ============================================================================

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_common.sh"

FORCE=0
[[ "${1:-}" == "--force" ]] && FORCE=1

if [[ -f "$ENV_FILE" && "$FORCE" == "0" ]]; then
  die "$ENV_FILE 已存在。**不会覆盖**（里面有全部密钥）。
       如需重新生成：先备份并删除它，或显式执行 --force（会先自动备份一份）。"
fi

if [[ -f "$ENV_FILE" && "$FORCE" == "1" ]]; then
  BACKUP="$ENV_FILE.bak-$(date +%Y%m%d-%H%M%S)"
  cp -p "$ENV_FILE" "$BACKUP"
  chmod 600 "$BACKUP"
  warn "已备份旧 .env 到 $(basename "$BACKUP")（权限 600）"
fi

command -v openssl >/dev/null 2>&1 || die "缺少 openssl（用于生成安全随机密钥）"

rand_hex()   { openssl rand -hex "$1"; }
rand_b64()   { openssl rand -base64 "$1" | tr -d '\n'; }

echo "============================================================"
echo " 生成服务器部署 .env"
echo "============================================================"

# JWT 密钥：>= 32 字节是 HS256 的硬要求（Java 侧会拒绝启动）
JWT_SECRET="$(openssl rand -base64 48 | tr -d '\n')"
# 内部 Token：Java <-> Python 共享凭证
INTERNAL_TOKEN="$(rand_hex 32)"
# 数据库与 Redis 口令
DB_ROOT_PWD="$(rand_hex 16)"
DB_PWD="$(rand_hex 16)"
REDIS_PWD="$(rand_hex 16)"
# 引导管理员口令：只在库里没有任何用户时创建一次
ADMIN_PWD="$(openssl rand -base64 18 | tr -d '\n/+=' | head -c 20)"

# umask 保证文件从创建那一刻就是 600，而不是"先 644 再 chmod"
OLD_UMASK="$(umask)"
umask 077

cat > "$ENV_FILE" <<EOF
# ============================================================================
# TrustAgent 服务器部署配置
# 由 scripts/server/init-server-env.sh 于 $(date -Iseconds) 生成
#
# 本文件含全部密钥：
#   * 权限已设为 600，请保持
#   * **不要**提交到 Git（.gitignore 已忽略 .env）
#   * 备份时注意它同样是敏感文件（见 scripts/server/backup.sh 的处理）
# ============================================================================

# --- 数据库 ---
TRUSTAGENT_DB_NAME=trustagent
TRUSTAGENT_DB_USERNAME=trustagent
TRUSTAGENT_DB_PASSWORD=${DB_PWD}
TRUSTAGENT_DB_ROOT_PASSWORD=${DB_ROOT_PWD}

# --- Redis ---
TRUSTAGENT_REDIS_PASSWORD=${REDIS_PWD}

# --- 认证密钥 ---
TRUSTAGENT_JWT_SECRET=${JWT_SECRET}
TRUSTAGENT_INTERNAL_TOKEN=${INTERNAL_TOKEN}

# --- 引导管理员（只在库中没有任何用户时创建一次；首次登录后请修改口令）---
TRUSTAGENT_BOOTSTRAP_ADMIN_USERNAME=admin
TRUSTAGENT_BOOTSTRAP_ADMIN_PASSWORD=${ADMIN_PWD}

# --- 部署 ---
# 镜像版本号：上传镜像前把这里改成 scripts/build-release.ps1 输出的版本号
TRUSTAGENT_IMAGE_TAG=
# 唯一对外端口（仅绑 127.0.0.1，通过 SSH 隧道访问）
TRUSTAGENT_WEB_PORT=8090
TRUSTAGENT_LOG_LEVEL=INFO

# --- 模型：默认 Mock，不需要任何 API Key、不产生费用 ---
# 改成 openai_compatible 前请先看 docs/SECURITY_LIMITATIONS.md 的 SSRF 说明：
# 配置任意 Base URL 等于给服务一个"访问任意内网地址"的能力，默认必须保持关闭。
TRUSTAGENT_LLM_PROVIDER=mock
TRUSTAGENT_LLM_MODEL=mock-llm-v1
TRUSTAGENT_OPENAI_BASE_URL=
TRUSTAGENT_OPENAI_API_KEY=
EOF

umask "$OLD_UMASK"
chmod 600 "$ENV_FILE"

echo
ok "$ENV_FILE 已生成，权限 600"
echo
echo "  已写入的键（**值不回显**）："
for k in TRUSTAGENT_DB_NAME TRUSTAGENT_DB_USERNAME TRUSTAGENT_DB_PASSWORD \
         TRUSTAGENT_DB_ROOT_PASSWORD TRUSTAGENT_REDIS_PASSWORD \
         TRUSTAGENT_JWT_SECRET TRUSTAGENT_INTERNAL_TOKEN \
         TRUSTAGENT_BOOTSTRAP_ADMIN_USERNAME TRUSTAGENT_BOOTSTRAP_ADMIN_PASSWORD; do
  echo "    - ${k}"
done
echo
echo "  引导管理员用户名：admin"
echo "  引导管理员口令  ：已写入 .env，请自行查看并妥善保存（本脚本不回显）："
echo "    grep '^TRUSTAGENT_BOOTSTRAP_ADMIN_PASSWORD=' $ENV_FILE"
echo
echo "  下一步："
echo "    1) 把镜像上传到 deploy/images/，然后在 .env 里设置 TRUSTAGENT_IMAGE_TAG"
echo "    2) ./scripts/server/preflight.sh"
echo "    3) ./scripts/server/deploy.sh"
