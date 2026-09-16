# 启动 Java 控制面（dev profile）
#
# dev profile = H2 文件库 + 内存限流 + 内存评测队列：
#   * 不需要 MySQL、不需要 Redis，克隆下来就能跑；
#   * 内存评测队列不会跨进程被 Python Worker 消费 —— 异步批量评测必须用
#     Redis（无 --spring.profiles.active=dev 的默认配置）。
#
# 想连真实 MySQL/Redis：直接 `mvnw spring-boot:run`（默认 profile），先起好 docker compose。

#Requires -Version 5.1
[CmdletBinding()]
param(
    [int]$Port = 8080
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_env.ps1')

$Root = Get-TrustAgentRoot
if (-not (Import-TrustAgentEnv -Root $Root)) { exit 1 }

$backendDir = Join-Path $Root 'backend'
$mvnw = Get-MavenWrapper -BackendDir $backendDir

Set-Item -Path 'Env:TRUSTAGENT_SERVER_PORT' -Value "$Port"

# 用环境变量而不是命令行 -D 传 profile：
# PowerShell 会把 -Dspring-boot.run.profiles=dev 这类含点号的参数拆坏（已踩过）。
Set-Item -Path 'Env:SPRING_PROFILES_ACTIVE' -Value 'dev'

Write-Host '[backend] profile=dev（H2 文件库 + 内存限流/队列）' -ForegroundColor DarkGray
if (-not $env:MAVEN_USER_HOME) {
    Write-Host '[backend] 提示：设置 MAVEN_USER_HOME 可让 Maven 与 Wrapper 缓存也不落到 C 盘：' -ForegroundColor DarkGray
    Write-Host '         setx MAVEN_USER_HOME "E:\dev-cache\maven-home"' -ForegroundColor DarkGray
}
Write-Host "[backend] 监听 http://localhost:$Port   （Ctrl+C 停止）" -ForegroundColor Green
Write-Host "[backend] 接口文档 http://localhost:$Port/swagger-ui.html" -ForegroundColor DarkGray

Push-Location $backendDir
try {
    & $mvnw spring-boot:run
} finally {
    Pop-Location
}
