# TrustAgent 常用命令
#
# 说明：Windows 默认没有 make，等价命令都放在 scripts/ 下（PowerShell）。
# 两种入口都保留，是为了让"本机开发"和"Linux/CI"各用顺手的一套。
#   Windows : pwsh scripts/start-ai-service.ps1 / scripts/demo-smoke.ps1 ...
#   Make    : make dev-ai / make smoke / make test ...

.DEFAULT_GOAL := help
.PHONY: help init-env dev-ai dev-backend smoke test test-backend test-ai \
        up down logs ps build clean \
        web-install web-build release \
        server-preflight server-deploy server-status server-smoke server-stop \
        server-backup server-restore server-rollback server-verify server-retention

PS ?= pwsh
COMPOSE ?= docker compose
BACKEND := backend
AI_SERVICE := ai-service

help: ## 显示所有可用目标
	@echo "TrustAgent 常用目标："
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2}'

init-env: ## 生成本机开发用 .env（含随机密钥，不覆盖已有文件）
	$(PS) scripts/init-dev-env.ps1

dev-ai: ## 启动 Python 执行面（MockLLM，无需外部依赖）
	$(PS) scripts/start-ai-service.ps1

dev-backend: ## 启动 Java 控制面（dev profile：H2 + 内存限流/队列）
	$(PS) scripts/start-backend-dev.ps1

smoke: ## 端到端冒烟验证（演示场景 A/B/C/D/F，需两个服务已启动）
	$(PS) scripts/demo-smoke.ps1

test: test-backend test-ai ## 跑全部测试（Java + Python）

test-backend: ## Java 控制面测试（H2 内存库，无需 MySQL/Redis）
	cd $(BACKEND) && ./mvnw test

# 虚拟环境里的 Python：Windows 是 .venv\Scripts\python.exe，Linux/macOS 是 .venv/bin/python。
# 两种布局都探测，避免 make test-ai 变成"只能在 WSL/Linux 里跑"。
VENV_PY := $(wildcard $(AI_SERVICE)/.venv/Scripts/python.exe) $(wildcard $(AI_SERVICE)/.venv/bin/python)
PY := $(if $(strip $(VENV_PY)),$(firstword $(VENV_PY)),python)

test-ai: ## Python 执行面测试（确定性 MockLLM）
	cd $(AI_SERVICE) && $(abspath $(PY)) -m pytest -q

up: ## docker compose 启动全栈（首次会拉取数 GB 镜像）
	$(COMPOSE) up -d --build

down: ## 停止并移除容器（保留数据卷）
	$(COMPOSE) down

ps: ## 查看容器状态
	$(COMPOSE) ps

logs: ## 跟踪控制面日志
	$(COMPOSE) logs -f backend

build: ## 构建后端可执行 jar（跳过测试）
	cd $(BACKEND) && ./mvnw -DskipTests package

clean: ## 清理构建产物与本地运行数据
	cd $(BACKEND) && ./mvnw -q clean
	rm -rf $(AI_SERVICE)/.pytest_cache
	@echo "已清理构建产物（.env 与 .local/ 缓存均保留）"

# ---------------------------------------------------------------------------
# 前端与发布产物
# ---------------------------------------------------------------------------

web-install: ## 安装前端依赖
	cd web && npm ci --no-audit --no-fund

web-build: ## 构建前端静态产物（到 web/dist）
	cd web && NODE_OPTIONS=--max-old-space-size=2048 npm run build

release: ## 本地构建发布产物（前端 + jar + 镜像 tar），需 pwsh
	$(PS) scripts/build-release.ps1

# ---------------------------------------------------------------------------
# 服务器演示部署（见 docs/DEPLOYMENT.md）
# 这些目标在 **服务器上** 执行；本机（Windows）请用 scripts/server/*.sh 的等价说明。
# ---------------------------------------------------------------------------

server-preflight: ## [服务器] 部署前资源与配置检查（只读）
	bash scripts/server/preflight.sh

server-deploy: ## [服务器] 发布/升级（自动先备份数据库）
	bash scripts/server/deploy.sh

server-status: ## [服务器] 查看状态：内存/CPU/重启/端口暴露/队列积压
	bash scripts/server/status.sh

server-smoke: ## [服务器] 端到端冒烟（场景 A/B/C/D/F，经反向代理）
	bash scripts/server/smoke.sh

server-stop: ## [服务器] 停止服务（保留数据卷）
	bash scripts/server/stop.sh

server-backup: ## [服务器] 备份 MySQL + Redis + .env
	bash scripts/server/backup.sh

server-restore: ## [服务器] 恢复（默认恢复到临时测试库，不碰线上）
	@echo "用法: bash scripts/server/restore.sh --from <备份目录名> --to-test"
	@ls -1t .local/backups 2>/dev/null | head -n 10 || echo "(还没有备份)"

server-rollback: ## [服务器] 回滚镜像版本（数据库不会一起回滚）
	bash scripts/server/rollback.sh --list

server-verify: ## [服务器] 真实 MySQL + Redis Streams 验证
	bash scripts/server/verify-stack.sh

server-retention: ## [服务器] 队列保留策略（dry-run，不修改数据）
	bash scripts/server/queue-retention.sh
