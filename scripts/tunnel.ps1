# ============================================================================
# TrustAgent 演示隧道：把服务器上的 127.0.0.1:8090 映射到本机同名端口
#
# 为什么需要它：
#   私有模式下，服务器上的入口只监听 127.0.0.1:8090 —— 从公网**故意**访问不到。
#   这是部署的安全属性，不是配置错误。要访问就得走 SSH 隧道。
#
# 为什么必须带 -F none：
#   本机 ~/.ssh/config 的 ACL 里残留了未知 SID（UNKNOWN\UNKNOWN），
#   Windows OpenSSH 会因此拒绝读取该文件并直接失败：
#       Bad permissions. Try removing permissions for user: UNKNOWN\UNKNOWN ...
#   -F none 表示"不读任何配置文件"，绕过它。**不要去改那个文件的权限**，
#   除非你确认里面没有别的项目在依赖它。
#
# 用法：
#   pwsh scripts/tunnel.ps1            # 前台运行（推荐：能直接看到连接状态，Ctrl+C 停止）
#   pwsh scripts/tunnel.ps1 -Check     # 只检查隧道是否通，不做别的
# ============================================================================

#Requires -Version 5.1
[CmdletBinding()]
param(
    # 服务器地址与登录用户**不写死在脚本里**：
    #   1) 换服务器/换账号时不需要改代码（减少"改了没提交"的分叉）；
    #   2) 硬编码生产入口会随仓库一起传播——这不是密钥，但也不是该进版本库的东西。
    # 取值顺序：命令行参数 > 环境变量 > 本机 .env。
    [string]$Server = '',
    [string]$User = '',
    [string]$IdentityFile = "$env:USERPROFILE\.ssh\trustagent_deploy",
    [int]$Port = 8090,
    [switch]$Check
)

# 从 .env / 环境变量补齐缺失的参数（命令行显式传入时以命令行为准）。
if (-not $Server -or -not $User) {
    $repoRoot = Split-Path -Parent $PSScriptRoot
    $envFile = Join-Path $repoRoot '.env'
    $fromFile = @{}
    if (Test-Path $envFile) {
        foreach ($line in [System.IO.File]::ReadAllLines($envFile, [System.Text.Encoding]::UTF8)) {
            $t = $line.Trim()
            if ($t -eq '' -or $t.StartsWith('#')) { continue }
            $i = $t.IndexOf('=')
            if ($i -lt 1) { continue }
            $fromFile[$t.Substring(0, $i).Trim()] = $t.Substring($i + 1).Trim()
        }
    }
    if (-not $Server) { $Server = $env:TRUSTAGENT_SSH_HOST; if (-not $Server) { $Server = $fromFile['TRUSTAGENT_SSH_HOST'] } }
    if (-not $User)   { $User   = $env:TRUSTAGENT_SSH_USER; if (-not $User)   { $User   = $fromFile['TRUSTAGENT_SSH_USER'] } }
    if (-not $User) { $User = 'root' }  # 默认值放在最后，仅作为兜底
}
if (-not $Server) {
    Write-Host '[FAIL] 未指定服务器地址。三种方式任选其一：' -ForegroundColor Red
    Write-Host '         pwsh scripts/tunnel.ps1 -Server <ip> -User <user>' -ForegroundColor Yellow
    Write-Host '         设置环境变量 TRUSTAGENT_SSH_HOST / TRUSTAGENT_SSH_USER' -ForegroundColor Yellow
    Write-Host '         在 .env 中写 TRUSTAGENT_SSH_HOST=... （推荐，见 .env.example）' -ForegroundColor Yellow
    exit 1
}

$ErrorActionPreference = 'Continue'

function Test-Tunnel {
    param([int]$P)
    $listening = [bool](Get-NetTCPConnection -LocalPort $P -State Listen -ErrorAction SilentlyContinue)
    if (-not $listening) { return $false }
    try {
        $r = Invoke-WebRequest "http://127.0.0.1:$P/healthz" -UseBasicParsing -TimeoutSec 5
        return ($r.StatusCode -eq 200)
    } catch {
        return $false
    }
}

# ---------------------------------------------------------------------------
# -Check：只报告状态，不做任何改动
# ---------------------------------------------------------------------------
if ($Check) {
    if (Test-Tunnel -P $Port) {
        Write-Host "[OK] 隧道通畅：http://localhost:$Port 可以访问" -ForegroundColor Green
        exit 0
    }
    $listening = [bool](Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
    if ($listening) {
        Write-Host "[WARN] 端口 $Port 在监听，但 /healthz 不返回 200 —— 隧道可能半死（进程还在但转发已断）" -ForegroundColor Yellow
        Write-Host "       处理方式：结束占用该端口的进程后重跑本脚本" -ForegroundColor DarkGray
    } else {
        Write-Host "[FAIL] 端口 $Port 无人监听 —— 隧道没开" -ForegroundColor Red
        Write-Host "       运行： pwsh scripts/tunnel.ps1" -ForegroundColor Yellow
    }
    exit 1
}

# ---------------------------------------------------------------------------
# 前置检查
# ---------------------------------------------------------------------------
if (-not (Test-Path $IdentityFile)) {
    Write-Host "[FAIL] 找不到密钥：$IdentityFile" -ForegroundColor Red
    exit 1
}

if (Test-Tunnel -P $Port) {
    Write-Host "[OK] 隧道已经在跑，无需重复启动：http://localhost:$Port" -ForegroundColor Green
    exit 0
}

# 端口被别的进程占着（但不是可用隧道）时，说清楚再退出，不要盲目再起一个
$holder = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($holder) {
    $proc = Get-Process -Id $holder.OwningProcess -ErrorAction SilentlyContinue
    Write-Host "[FAIL] 端口 $Port 已被占用，但它不是可用隧道。" -ForegroundColor Red
    Write-Host "       占用进程：$($proc.ProcessName) (pid=$($holder.OwningProcess))" -ForegroundColor DarkGray
    Write-Host "       处理：确认它不是有用的连接后结束它，或换端口 (-Port 8091) 并同步改访问地址" -ForegroundColor Yellow
    exit 1
}

# ---------------------------------------------------------------------------
# 建立隧道
# ---------------------------------------------------------------------------
$target = "$User@$Server"
Write-Host ''
Write-Host '============================================================' -ForegroundColor Cyan
Write-Host " TrustAgent 演示隧道  ->  http://localhost:$Port" -ForegroundColor Cyan
Write-Host '============================================================' -ForegroundColor Cyan
Write-Host " 转发：本机 $Port  ->  $target 上的 127.0.0.1:$Port" -ForegroundColor DarkGray
Write-Host ' 这个窗口需要保持打开；按 Ctrl+C 断开。' -ForegroundColor Yellow
Write-Host ''

# 前台运行：让用户看得见连接状态，也保证 Ctrl+C 能干净退出。
# -N 不执行远程命令；ExitOnForwardFailure 让端口冲突立刻报错而不是静默失败；
# ServerAlive* 让空闲连接不被中途的设备/NAT 悄悄掐断。
& ssh -F none -i $IdentityFile -N -L "${Port}:127.0.0.1:${Port}" `
    -o BatchMode=yes `
    -o ExitOnForwardFailure=yes `
    -o ServerAliveInterval=30 `
    -o ServerAliveCountMax=3 `
    $target

Write-Host ''
Write-Host "隧道已断开（ssh 退出码 $LASTEXITCODE）。重新运行本脚本即可恢复。" -ForegroundColor Yellow
