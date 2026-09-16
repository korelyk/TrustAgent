# ============================================================================
# Java 控制面「只组装、不编译」镜像 —— 用于**不在服务器上跑 Maven** 的场景
#
# 与 deploy/backend.Dockerfile 的区别：
#   backend.Dockerfile         多阶段：用 maven 镜像编译 → 拷进 JRE 镜像
#                              （需要拉取 maven+JDK 镜像约 900MB，构建峰值内存 1~1.5GB）
#   backend-runtime.Dockerfile 单阶段：直接把**本地/CI 已构建好的 jar**拷进 JRE 镜像
#                              （只需要拉取 JRE 镜像约 280MB，构建几乎不吃内存）
#
# 为什么需要这一份：
#   目标服务器是 1 核 2 GB，且上面已经跑着**别的项目**（实测可用内存约 1.4GB）。
#   在那台机器上跑 `mvn package`（峰值 1~1.5GB）会与已运行的服务抢内存，
#   有把别人的服务拖挂的风险 —— 这比"构建慢一点"严重得多。
#   所以在本地/CI 编译好 jar，服务器只负责组装。
#
# 前置条件：构建前 jar 必须已存在于 deploy/artifacts/
#   （见 scripts/build-release.ps1；或手工：
#     mkdir -p deploy/artifacts &&
#     cp backend/target/trustagent-backend-*.jar deploy/artifacts/）
#
# 如果 jar 不存在，COPY 会直接失败并明确报出缺哪个文件 ——
# 这是刻意的：宁可构建失败，也不要产出一个"能启动但里面是旧代码"的镜像。
# ============================================================================

FROM eclipse-temurin:21-jre

WORKDIR /app

# curl 只用于容器健康检查（compose 的 healthcheck 要打 /health）。
# 没有它就只能把健康检查写成"进程存在即算健康"，那会把"启动了但连不上数据库"
# 误判为健康 —— 这正是部署时最需要尽早发现的一类问题。
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 1001 trustagent \
    && useradd --system --uid 1001 --gid trustagent --no-create-home trustagent

# 只拷 jar：本地已构建好的可执行产物
COPY deploy/artifacts/trustagent-backend-0.1.0.jar /app/app.jar

# 与 backend.Dockerfile 保持一致：用非 root 用户运行
USER trustagent

EXPOSE 8080

# JVM 参数**只通过 JAVA_OPTS 注入**，不在 ENTRYPOINT 里硬编码。
# 原因见 deploy/backend.Dockerfile 的详细说明：
# JAVA_TOOL_OPTIONS 在命令行参数之前生效，若这里写死 -XX:MaxRAMPercentage=75，
# 低配 compose 里调成 50 会被它覆盖，表现为"按文档调了内存但实际没生效"。
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

# sh -c + exec：exec 让 java 成为 PID 1，SIGTERM 能直接送达，
# 从而走 Spring Boot 的 graceful shutdown。
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
