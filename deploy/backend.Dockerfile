# Java 控制面镜像（多阶段构建）
#
# 设计取舍：
#   * 构建阶段用官方 maven 镜像（自带 Maven + JDK 21），运行阶段只留 JRE —— 镜像小得多；
#   * 先只拷 pom.xml 预热依赖，只要 pom 不变，改 Java 代码时这一层仍然命中缓存；
#   * 镜像构建里 **不跑测试**：构建要快且可重复，测试交给 CI 与本地（见 Makefile 的 test 目标）；
#   * 用非 root 用户运行：容器被攻破时降低影响面，这是这类"安全网关"项目的基本要求。

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY backend/pom.xml ./pom.xml
RUN mvn -B -q dependency:go-offline

COPY backend/src ./src
RUN mvn -B -q -DskipTests package \
    && cp target/trustagent-backend-*.jar /build/app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app

# curl 只用于容器健康检查（compose 的 healthcheck 要打 /health）。
# temurin:jre 基础镜像不带任何 HTTP 客户端，没有它就只能把健康检查写成
# "进程存在即算健康"，而那会把"启动了但连不上数据库"误判为健康。
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 1001 trustagent \
    && useradd --system --uid 1001 --gid trustagent --no-create-home trustagent

COPY --from=build /build/app.jar /app/app.jar
USER trustagent

EXPOSE 8080

# JVM 参数通过 JAVA_OPTS 注入，**不写死在 ENTRYPOINT 里**。
#
# 为什么必须这样（这是实际踩出来的坑）：
#   JAVA_TOOL_OPTIONS 里的参数在命令行参数**之前**生效。如果这里硬编码
#   `-XX:MaxRAMPercentage=75`，那么低配 compose 里通过 JAVA_TOOL_OPTIONS
#   传的 `-XX:MaxRAMPercentage=50` 会被这个硬编码值覆盖掉——
#   表现为"按文档调了内存，实际堆还是按 75% 分配，然后被 OOMKilled"，
#   而且从 compose 文件上完全看不出问题。
#   改成只有一个来源（JAVA_OPTS），调优才真正生效。
#
# 默认值维持 75%，这对常规（非 1 核 2 GB）部署是合适的；
# 低配场景由 docker-compose.lowmem.yml 显式覆盖。
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

# 用 sh -c + exec：exec 让 java 成为 PID 1，SIGTERM 能直接送达，
# 从而走 Spring Boot 的 graceful shutdown（server.shutdown=graceful）。
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
