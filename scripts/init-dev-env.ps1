# 生成本机开发用的 .env（含随机密钥），并打印管理员口令
#
# 设计要点：
#   * **密钥随机生成**，不写死在仓库里 —— 满足"不得硬编码 API Key/密码/Token"的约束；
#   * .env 已被 .gitignore 忽略，不会进入版本库；
#   * 幂等：已存在 .env 时默认不覆盖，避免把你手动改过的配置冲掉（要重建请加 -Force）。
#
# 用法：
#   pwsh scripts/init-dev-env.ps1
#   pwsh scripts/init-dev-env.ps1 -Force     # 重新生成（会更换密钥与管理员口令）

#Requires -Version 5.1
[CmdletBinding()]
param(
    [switch]$Force,
    [string]$AdminUsername = 'admin'
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_env.ps1')

$Root = Get-TrustAgentRoot
$envPath = Join-Path $Root '.env'
$templatePath = Join-Path $Root '.env.example'

if (-not (Test-Path $templatePath)) {
    throw "缺少模板文件 .env.example：$templatePath"
}

if ((Test-Path $envPath) -and (-not $Force)) {
    Write-Host "[init] .env 已存在，未做修改。" -ForegroundColor Yellow
    Write-Host "       如需重新生成（会更换密钥与管理员口令）：pwsh scripts/init-dev-env.ps1 -Force" -ForegroundColor Yellow
    exit 0
}

function New-HexSecret {
    param([int]$ByteCount)
    $buffer = New-Object byte[] $ByteCount
    # RandomNumberGenerator：密码学安全随机源，不适合用 Get-Random
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($buffer)
    return ($buffer | ForEach-Object { $_.ToString('x2') }) -join ''
}

$jwtSecret = New-HexSecret -ByteCount 48          # 96 字符，远超 32 字节下限
$internalToken = New-HexSecret -ByteCount 24      # 48 字符
$adminPassword = 'Ta-' + (New-HexSecret -ByteCount 10)   # 满足长度要求且便于复制

$content = [System.IO.File]::ReadAllText($templatePath, [System.Text.Encoding]::UTF8)

function Set-EnvLine {
    param([string]$Text, [string]$Key, [string]$Value)
    $pattern = "(?m)^$([regex]::Escape($Key))=.*$"
    $replacement = "$Key=$Value"
    if ([regex]::IsMatch($Text, $pattern)) {
        return [regex]::Replace($Text, $pattern, $replacement)
    }
    return $Text.TrimEnd() + "`n$replacement`n"
}

# docker compose 会用到这些（dev profile 走 H2，不需要它们，但一并生成可避免 compose 启动即失败）
$content = Set-EnvLine -Text $content -Key 'TRUSTAGENT_DB_ROOT_PASSWORD' -Value (New-HexSecret -ByteCount 12)
$content = Set-EnvLine -Text $content -Key 'TRUSTAGENT_DB_PASSWORD' -Value (New-HexSecret -ByteCount 12)
$content = Set-EnvLine -Text $content -Key 'TRUSTAGENT_REDIS_PASSWORD' -Value (New-HexSecret -ByteCount 12)

$content = Set-EnvLine -Text $content -Key 'TRUSTAGENT_JWT_SECRET' -Value $jwtSecret
$content = Set-EnvLine -Text $content -Key 'TRUSTAGENT_INTERNAL_TOKEN' -Value $internalToken
$content = Set-EnvLine -Text $content -Key 'TRUSTAGENT_BOOTSTRAP_ADMIN_USERNAME' -Value $AdminUsername
$content = Set-EnvLine -Text $content -Key 'TRUSTAGENT_BOOTSTRAP_ADMIN_PASSWORD' -Value $adminPassword
# 开发联调固定为确定性 MockLLM：不需要任何 API Key，指标也不会因模型随机性抖动
$content = Set-EnvLine -Text $content -Key 'TRUSTAGENT_LLM_PROVIDER' -Value 'mock'

$banner = @"
# ============================================================================
# 本文件由 scripts/init-dev-env.ps1 生成，含随机开发密钥，已被 .gitignore 忽略。
# 不要把它提交、不要复制到其他环境。
# 重新生成： pwsh scripts/init-dev-env.ps1 -Force
# ============================================================================

"@

$final = $banner + $content
# 无 BOM 的 UTF-8：Spring Boot 与 pydantic-settings 读取 .env 时对 BOM 都不友好
[System.IO.File]::WriteAllText($envPath, $final, (New-Object System.Text.UTF8Encoding($false)))

Write-Host ""
Write-Host "[init] 已生成 $envPath" -ForegroundColor Green
Write-Host ""
Write-Host "  管理员账号 : $AdminUsername"
Write-Host "  管理员口令 : $adminPassword" -ForegroundColor Cyan
Write-Host ""
Write-Host "  请把上面这行口令记下来（脚本不会把它存到别处；重跑 -Force 会更换）。" -ForegroundColor DarkGray
Write-Host "  下一步：" -ForegroundColor DarkGray
Write-Host "    终端 1:  pwsh scripts/start-ai-service.ps1" -ForegroundColor DarkGray
Write-Host "    终端 2:  pwsh scripts/start-backend-dev.ps1" -ForegroundColor DarkGray
Write-Host "    终端 3:  pwsh scripts/demo-smoke.ps1" -ForegroundColor DarkGray
