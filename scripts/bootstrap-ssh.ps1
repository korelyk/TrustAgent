# ============================================================================
# SSH 一次性引导：把本机公钥装到服务器上，之后即可免密非交互操作
#
# 这个脚本解决两个实测踩到的问题：
#
#   1) 本机 ~/.ssh/config 的 ACL 里残留了未知 SID
#      （UNKNOWN\UNKNOWN，通常是重装系统/换账号后留下的），
#      Windows OpenSSH 会因此拒绝读取该文件并直接失败：
#          Bad permissions. Try removing permissions for user: UNKNOWN\UNKNOWN ...
#      这里统一用 `ssh -F none`（官方文档：设为 none 则不读取任何配置文件），
#      **不去改动你的任何文件**，也不需要动 ACL。
#      注意：-F none 只跳过配置文件，known_hosts 的主机身份检查**照常生效**。
#
#   2) ssh 会把 "Permission denied"、"密码错误" 之类信息写到 stderr。
#      在 $ErrorActionPreference='Stop' 下，PowerShell 会把它升级成终止性错误，
#      脚本会在第一次调用 ssh 时就静默退出——表现为"什么都没做就回到提示符"。
#      所以本脚本刻意使用 'Continue'，并显式检查每个原生命令的退出码。
#
# 安全设计：
#   * 密码**只输入到 ssh 的交互提示**：不回显、不落盘、不进命令行、不进日志。
#   * **不关闭**主机身份检查：脚本会先打印已记录的主机指纹，让你与 VPS 控制台核对，
#     核对通过才继续。
#   * 幂等：已装好公钥时会自动跳过安装；可反复执行。
#   * 不会覆盖任何已有的 SSH 密钥，也不修改你的 ~/.ssh/config。
#
# 用法（在你自己的 PowerShell 窗口里执行）：
#   powershell -ExecutionPolicy Bypass -File "<repo>\scripts\bootstrap-ssh.ps1"
# ============================================================================

#Requires -Version 5.1
[CmdletBinding()]
param(
    # 与 tunnel.ps1 同一套约定：不把服务器地址写死在脚本里。
    # 取值：命令行 > 环境变量 TRUSTAGENT_SSH_HOST/USER > .env 同名键。
    [string]$Server = '',
    [string]$User = '',
    [string]$IdentityFile = "$env:USERPROFILE\.ssh\trustagent_deploy"
)

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
    if (-not $User) { $User = 'root' }
}
if (-not $Server) {
    Write-Host '[FAIL] 未指定服务器地址。请用 -Server <ip> / 环境变量 TRUSTAGENT_SSH_HOST / .env 提供。' -ForegroundColor Red
    exit 1
}

# 刻意不用 'Stop' —— 原因见文件头第 2 条
$ErrorActionPreference = 'Continue'

$PublicKeyFile = "$IdentityFile.pub"
$KnownHosts = "$env:USERPROFILE\.ssh\known_hosts"
$Target = "$User@$Server"

Write-Host ''
Write-Host '============================================================' -ForegroundColor Cyan
Write-Host " TrustAgent SSH 引导 -> $Target" -ForegroundColor Cyan
Write-Host '============================================================' -ForegroundColor Cyan

# ---------------------------------------------------------------------------
# 统一的 ssh 调用：一律 -F none，并显式返回退出码
# ---------------------------------------------------------------------------
function Invoke-Remote {
    param(
        [Parameter(Mandatory = $true)][string]$Command,
        [switch]$Batch
    )
    $sshArgs = @('-F', 'none', '-i', $IdentityFile, '-o', 'ConnectTimeout=15',
                 '-o', 'StrictHostKeyChecking=accept-new')
    if ($Batch) { $sshArgs += @('-o', 'BatchMode=yes') }
    $sshArgs += $Target
    $sshArgs += $Command
    $text = (& ssh @sshArgs 2>&1 | Out-String)
    return [pscustomobject]@{ Text = $text; Code = $LASTEXITCODE }
}

# ---------------------------------------------------------------------------
# 0. 前置检查
# ---------------------------------------------------------------------------
Write-Host ''
Write-Host '[0/4] 前置检查' -ForegroundColor White

if (-not (Test-Path $IdentityFile)) {
    Write-Host "  [FAIL] 找不到私钥: $IdentityFile" -ForegroundColor Red
    exit 1
}
if (-not (Test-Path $PublicKeyFile)) {
    Write-Host "  [FAIL] 找不到公钥: $PublicKeyFile" -ForegroundColor Red
    exit 1
}

# 带口令的私钥无法用于非交互自动化，必须在开始前拦住
$probe = (& ssh-keygen -y -f $IdentityFile 2>&1 | Out-String)
if ($probe -notmatch '^ssh-ed25519 ') {
    Write-Host '  [FAIL] 该私钥带口令，无法用于非交互自动化。' -ForegroundColor Red
    Write-Host "         请另生成一把无口令的专用密钥（不要给已有密钥去口令）："
    Write-Host "           ssh-keygen -t ed25519 -f `"$IdentityFile`" -C trustagent-deploy -N '`"`"'"
    exit 1
}
Write-Host "  [OK] 私钥可用且无口令" -ForegroundColor Green
Write-Host ('  [OK] 公钥指纹: ' + ((& ssh-keygen -lf $PublicKeyFile) -split '\s+')[1])

# ---------------------------------------------------------------------------
# 1. 主机身份核对（不跳过这一关）
# ---------------------------------------------------------------------------
Write-Host ''
Write-Host '[1/4] 主机身份核对' -ForegroundColor White

$lookup = (& ssh-keygen -F $Server -f $KnownHosts 2>&1 | Out-String)
if ($lookup -notmatch 'found') {
    Write-Host "  [FAIL] known_hosts 中没有 $Server 的记录，无法核对身份。" -ForegroundColor Red
    Write-Host '         请先手动连接一次以记录主机密钥：'
    Write-Host "           ssh -i `"$IdentityFile`" $Target"
    Write-Host '         看到指纹提示时，与 VPS 控制台核对一致后再输入 yes。'
    exit 1
}

$fpFile = Join-Path $env:TEMP ("ta_fp_" + [guid]::NewGuid().ToString('N'))
($lookup -split "`n") | Where-Object { $_.Trim() -and $_ -notmatch '^\s*#' -and $_ -notmatch 'found' } |
    Out-File -FilePath $fpFile -Encoding ascii

Write-Host '  已记录的主机密钥指纹：' -ForegroundColor Yellow
(& ssh-keygen -E sha256 -lf $fpFile 2>&1) | ForEach-Object { Write-Host "    $_" -ForegroundColor Yellow }
(& ssh-keygen -E md5 -lf $fpFile 2>&1) | ForEach-Object { Write-Host "    $_" -ForegroundColor Yellow }
Remove-Item $fpFile -Force -ErrorAction SilentlyContinue

Write-Host ''
$answer = Read-Host '  上面这串指纹与你的 VPS 控制台显示的一致吗？输入 yes 继续（其他任何输入都会中止）'
if ($answer -ne 'yes') {
    Write-Host '  已中止。请核实主机身份后再运行本脚本。' -ForegroundColor Red
    exit 1
}

# ---------------------------------------------------------------------------
# 2. 是否已经免密可用（幂等：已装好就不再动）
# ---------------------------------------------------------------------------
Write-Host ''
Write-Host '[2/4] 检查是否已经可以免密登录' -ForegroundColor White

$already = Invoke-Remote -Command 'echo ALREADY_OK' -Batch
if ($already.Text -match 'ALREADY_OK') {
    Write-Host '  [OK] 免密登录已经可用，跳过安装公钥。' -ForegroundColor Green
} else {
    # -----------------------------------------------------------------------
    # 3. 安装公钥（需要你在 ssh 提示里输一次密码）
    # -----------------------------------------------------------------------
    Write-Host ''
    Write-Host '[3/4] 安装公钥到服务器' -ForegroundColor White
    Write-Host ''
    Write-Host '      接下来会出现密码提示：' -ForegroundColor Yellow
    Write-Host "        $Target's password:" -ForegroundColor Yellow
    Write-Host '      在这里输入服务器密码。不会回显，也不会进入任何日志。' -ForegroundColor Yellow
    Write-Host ''

    $PublicKey = (Get-Content $PublicKeyFile -Raw).Trim()
    # 幂等写入：grep -qF 先判断，避免 authorized_keys 出现重复行
    $remoteCmd = "mkdir -p ~/.ssh && chmod 700 ~/.ssh && " +
                 "(grep -qF '$PublicKey' ~/.ssh/authorized_keys 2>/dev/null || " +
                 "echo '$PublicKey' >> ~/.ssh/authorized_keys) && " +
                 "chmod 600 ~/.ssh/authorized_keys && echo KEY_INSTALLED"

    $install = Invoke-Remote -Command $remoteCmd
    if ($install.Text -match 'KEY_INSTALLED') {
        Write-Host '  [OK] 公钥已安装' -ForegroundColor Green
    } else {
        Write-Host '  [FAIL] 安装未成功。ssh 输出：' -ForegroundColor Red
        ($install.Text -split "`n" | Where-Object { $_.Trim() } |
            Where-Object { $_ -notmatch 'CategoryInfo|FullyQualified|^\s*\+|所在位置' } |
            Select-Object -First 6) | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }
        Write-Host ''
        Write-Host '  常见原因：' -ForegroundColor DarkGray
        Write-Host '    * 密码输错 —— 重跑本脚本再试一次' -ForegroundColor DarkGray
        Write-Host '    * 服务器禁止 root 密码登录 —— 需要用别的账号，或在控制台先配置' -ForegroundColor DarkGray
        Write-Host '    * 服务器磁盘满 / ~/.ssh 不可写' -ForegroundColor DarkGray
        exit 1
    }
}

# ---------------------------------------------------------------------------
# 4. 验证免密确实生效
# ---------------------------------------------------------------------------
Write-Host ''
Write-Host '[4/4] 验证免密登录' -ForegroundColor White

$verify = Invoke-Remote -Command 'echo KEY_AUTH_OK; whoami; hostname; nproc' -Batch
if ($verify.Text -match 'KEY_AUTH_OK') {
    Write-Host '  [OK] 免密登录已生效：' -ForegroundColor Green
    ($verify.Text -split "`n" | Where-Object { $_.Trim() } |
        Where-Object { $_ -notmatch 'CategoryInfo|FullyQualified|^\s*\+|所在位置|^\s*$' } |
        Select-Object -First 4) | ForEach-Object { Write-Host "       $($_.Trim())" }
    Write-Host ''
    Write-Host '============================================================' -ForegroundColor Green
    Write-Host ' 完成。回到对话里说一声，由我接手后续部署。' -ForegroundColor Green
    Write-Host '============================================================' -ForegroundColor Green
    exit 0
} else {
    Write-Host '  [FAIL] 免密仍未生效。ssh 输出：' -ForegroundColor Red
    ($verify.Text -split "`n" | Where-Object { $_.Trim() } |
        Where-Object { $_ -notmatch 'CategoryInfo|FullyQualified|^\s*\+|所在位置' } |
        Select-Object -First 6) | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }
    Write-Host ''
    Write-Host '  请把上面的输出发给我（其中不含密钥或口令）。' -ForegroundColor Yellow
    exit 1
}
