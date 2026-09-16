# ============================================================================
# 本地/CI 构建发布产物（服务器上不构建）
#
# 目标机器是 1 核 2 GB。在上面跑 `mvn package`（峰值 1~1.5 GB）或
# `vue-tsc + vite build`（峰值 0.8~1.5 GB）会和 MySQL、JVM 抢内存，
# 结果通常是构建被 OOMKilled，或者把整机拖死。
# 所以：**构建在本地或 CI 完成，服务器只负责运行和加载镜像。**
#
# 本脚本的特征：
#   * 严格串行：前端构建 → Maven 打包 → 同步 jar 到 deploy/artifacts/ → 镜像构建 → 导出。
#     任何一步失败即停。
#   * 显式限制 JVM 与 Node 的堆上限，避免"构建机器自己被自己的构建打爆"。
#   * 产物带明确版本号（不允许 latest），并记录 SHA256。
#   * 不推送仓库、不发布镜像、不部署到任何服务器——这些都需你显式执行。
#
# 用法：
#   pwsh scripts/build-release.ps1                       # 版本号自动按日期生成
#   pwsh scripts/build-release.ps1 -Tag v0.1.0-demo      # 指定版本号
#   pwsh scripts/build-release.ps1 -SkipImages           # 只构建 jar 与前端产物
#
# 产物：
#   web/dist/                                    前端静态文件
#   backend/target/trustagent-backend-0.1.0.jar  Java 可执行 jar
#   deploy/artifacts/trustagent-backend-*.jar    jar 的**同步副本**：
#       deploy/backend-runtime.Dockerfile 就是 COPY 这个路径的（服务器不跑 Maven，
#       只组装），因此它必须与 backend/target/ 下的本次产物是同一份文件。
#       注意它是本地构建产物、不入库（见 .gitignore），且**必须留在 docker 上下文里**
#       （见 .dockerignore 的说明）。
#   deploy/images/trustagent-*-<Tag>.tar         Docker 镜像（供服务器 docker load）
#   deploy/images/SHA256SUMS-<Tag>.txt           校验值
# ============================================================================

#Requires -Version 5.1
[CmdletBinding()]
param(
    [string]$Tag = '',
    [switch]$SkipImages,
    # Maven 堆上限。1 GB 足够打这个规模的项目，且不会把开发机吃满。
    [int]$MavenHeapMb = 1024,
    # Node 堆上限。Element Plus + vue-tsc 需要较多内存，但 2 GB 已很宽裕。
    [int]$NodeHeapMb = 2048
)

$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $PSScriptRoot
$BackendDir = Join-Path $Root 'backend'
$WebDir = Join-Path $Root 'web'
$ImagesDir = Join-Path $Root 'deploy\images'
$ArtifactsDir = Join-Path $Root 'deploy\artifacts'

if (-not $Tag) {
    $Tag = 'v0.1.0-' + (Get-Date -Format 'yyyyMMdd-HHmm')
}
if ($Tag -eq 'latest') {
    throw 'Tag 不允许为 latest：出问题时无法判断线上跑的是哪一版。'
}

# 本机 Maven 仓库与 Wrapper 缓存都指向 E 盘（见 docs/DEPLOYMENT.md 的磁盘说明）
if (-not $env:MAVEN_USER_HOME) { $env:MAVEN_USER_HOME = 'E:\dev-cache\maven-home' }

function Step([string]$Text) {
    Write-Host ''
    Write-Host "==== $Text ====" -ForegroundColor Cyan
}

Write-Host "TrustAgent 构建产物" -ForegroundColor Green
Write-Host "  版本号 : $Tag"
Write-Host "  Maven堆: ${MavenHeapMb} MiB     Node堆: ${NodeHeapMb} MiB"
Write-Host "  注意   : 各步骤串行执行，任一步失败即中止"

# ---------------------------------------------------------------------------
Step '1/5 前端构建（先做，因为它最吃内存）'
# ---------------------------------------------------------------------------
Push-Location $WebDir
try {
    if (-not (Test-Path 'node_modules')) {
        Write-Host '  安装前端依赖（npm ci）...'
        npm ci --no-audit --no-fund
        if ($LASTEXITCODE -ne 0) { throw 'npm ci 失败' }
    } else {
        Write-Host '  已存在 node_modules，跳过安装（如需强制重装请手动删除该目录）'
    }

    $env:NODE_OPTIONS = "--max-old-space-size=$NodeHeapMb"
    Write-Host "  npm run build（NODE_OPTIONS=$($env:NODE_OPTIONS)）..."
    npm run build
    if ($LASTEXITCODE -ne 0) { throw 'npm run build 失败' }

    if (-not (Test-Path 'dist\index.html')) { throw '构建后没有 web/dist/index.html' }
    $distSize = [math]::Round(((Get-ChildItem 'dist' -Recurse -File | Measure-Object Length -Sum).Sum / 1MB), 2)
    Write-Host "  前端产物: web/dist (${distSize} MB)" -ForegroundColor Green
} finally {
    Remove-Item Env:\NODE_OPTIONS -ErrorAction SilentlyContinue
    Pop-Location
}

# ---------------------------------------------------------------------------
Step '2/5 Java 打包'
# ---------------------------------------------------------------------------
Push-Location $BackendDir
try {
    # 刻意跳过测试：测试在 scripts 里单独跑（make test / mvn test）。
    # 构建产物应当可重复、快；测试失败应该由测试环节拦住，而不是混在这里。
    $env:MAVEN_OPTS = "-Xmx${MavenHeapMb}m -Duser.language=en -Duser.country=US"
    Write-Host "  mvnw package -DskipTests（MAVEN_OPTS=$($env:MAVEN_OPTS)）..."
    & .\mvnw.cmd -B -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'Maven 打包失败' }

    $jar = Get-ChildItem 'target\trustagent-backend-*.jar' |
           Where-Object { $_.Name -notmatch 'original' } | Select-Object -First 1
    if (-not $jar) { throw '未找到打包产物 jar' }
    Write-Host ("  Java 产物: {0} ({1} MB)" -f $jar.Name, [math]::Round($jar.Length / 1MB, 1)) -ForegroundColor Green
} finally {
    Remove-Item Env:\MAVEN_OPTS -ErrorAction SilentlyContinue
    Pop-Location
}

# ---------------------------------------------------------------------------
Step '3/5 同步 jar 到 deploy/artifacts/'
# ---------------------------------------------------------------------------
# 为什么必须有这一步：
#   docker-compose.lowmem.yml 的 backend 用的是 deploy/backend-runtime.Dockerfile，
#   而那个 Dockerfile 是 `COPY deploy/artifacts/trustagent-backend-0.1.0.jar`。
#   也就是说 **镜像里的代码来自 deploy/artifacts/，而不是 backend/target/**。
#   本脚本以前只把 jar 留在 backend/target/，从不刷新 deploy/artifacts/，
#   于是那一份会一直是"某次手工复制进去的旧产物"：构建一路绿灯、
#   镜像也构建成功，但服务器上跑的是旧代码 —— 这是最难排查的一类错误
#   （版本号、日志、接口都对得上，只有行为不对）。
#
#   先删旧的再复制，是为了让"这里只有本次构建的产物"这件事可被验证：
#   如果复制失败，目录会是空的，Dockerfile 的 COPY 会立刻失败并明确报错，
#   而不是悄悄用上一版 jar 构建出一个"能启动但代码不对"的镜像。
# ---------------------------------------------------------------------------
if (-not (Test-Path $ArtifactsDir)) {
    New-Item -ItemType Directory -Force -Path $ArtifactsDir | Out-Null
}

# 断言：产物文件名必须与 backend-runtime.Dockerfile 里 COPY 的路径一致。
# 两边不一致时 docker build 会因 COPY 失败而报错（fail loud），但那个报错
# 出现在"构建镜像"这一步、指向 Dockerfile，很容易被误读成 Dockerfile 写错。
# 在这里先判一次，把问题钉在真正的原因上。
$expectedJarName = 'trustagent-backend-0.1.0.jar'
if ($jar.Name -ne $expectedJarName) {
    throw "打包产物是 $($jar.Name)，但 deploy/backend-runtime.Dockerfile 里 COPY 的是 $expectedJarName。" +
          '请同步修改 Dockerfile 的 COPY 行（或还原 backend/pom.xml 的版本号），否则镜像里装的不会是这个 jar。'
}

$staleJars = @(Get-ChildItem -Path $ArtifactsDir -Filter 'trustagent-backend-*.jar' -File -ErrorAction SilentlyContinue)
foreach ($stale in $staleJars) {
    Remove-Item $stale.FullName -Force
    Write-Host "  已删除旧产物: deploy/artifacts/$($stale.Name)" -ForegroundColor Yellow
}

$destJar = Join-Path $ArtifactsDir $jar.Name
Copy-Item $jar.FullName $destJar -Force

# 复制后回读一次大小与 SHA256：只打印"已复制"不算验证，
# 读回来的哈希一致才能证明 deploy/artifacts 里的就是本次构建的产物。
$srcHash = (Get-FileHash $jar.FullName -Algorithm SHA256).Hash.ToLower()
$dstHash = (Get-FileHash $destJar    -Algorithm SHA256).Hash.ToLower()
if ($srcHash -ne $dstHash) {
    throw "jar 复制后校验值不一致：源 $srcHash / 目标 $dstHash"
}
$script:JarSha256 = $srcHash
Write-Host '  jar 已同步（脚本不再依赖手工复制这一份产物）' -ForegroundColor Green
Write-Host "    源  : backend/target/$($jar.Name)  ($([math]::Round($jar.Length / 1MB, 1)) MB)"
Write-Host "    目标: deploy/artifacts/$($jar.Name)"
Write-Host "    SHA256: $srcHash"

# ---------------------------------------------------------------------------
Step '4/5 构建镜像'
# ---------------------------------------------------------------------------
if ($SkipImages) {
    Write-Host '  已指定 -SkipImages，跳过镜像构建' -ForegroundColor Yellow
} else {
    # 先确认 docker 可用：不可用时明确报出来，而不是让后面的命令各自报奇怪的错
    $null = docker info 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Host '  [FAIL] Docker 守护进程不可用，无法构建镜像。' -ForegroundColor Red
        Write-Host '         jar 与前端产物已经构建完成，可在 Docker 恢复后单独重跑本脚本。'
        throw 'Docker 不可用'
    }

    $dockerfileMap = @(
        # backend 用「只组装、不编译」的 Dockerfile：镜像里的代码必须来自
        # 上一步同步好的 deploy/artifacts/trustagent-backend-0.1.0.jar。
        # 若这里换成 deploy/backend.Dockerfile（多阶段自编译），步骤 3 同步的 jar
        # 就完全不会被用到 —— 镜像会用构建期的源码重新编译，与本脚本的产物脱钩。
        # docker-compose.lowmem.yml（服务器部署用）读的也正是下面这一份。
        @{ Name = 'trustagent-backend';    File = 'deploy/backend-runtime.Dockerfile' },
        @{ Name = 'trustagent-ai-service'; File = 'deploy/ai-service.Dockerfile' },
        @{ Name = 'trustagent-web';        File = 'deploy/web.Dockerfile' }
    )

    foreach ($item in $dockerfileMap) {
        Write-Host ("  构建 {0}:{1} ..." -f $item.Name, $Tag)
        docker build -f $item.File -t "$($item.Name):$Tag" .
        if ($LASTEXITCODE -ne 0) { throw "构建 $($item.Name) 失败" }
    }
    Write-Host '  三个镜像构建完成' -ForegroundColor Green
}

# ---------------------------------------------------------------------------
Step '5/5 导出镜像与校验值'
# ---------------------------------------------------------------------------
if ($SkipImages) {
    Write-Host '  跳过'
} else {
    New-Item -ItemType Directory -Force -Path $ImagesDir | Out-Null

    foreach ($name in @('trustagent-backend', 'trustagent-ai-service', 'trustagent-web')) {
        $tar = Join-Path $ImagesDir "$name-$Tag.tar"
        Write-Host "  导出 $name`:$Tag -> $([System.IO.Path]::GetFileName($tar))"
        docker save -o $tar "$name`:$Tag"
        if ($LASTEXITCODE -ne 0) { throw "导出 $name 失败" }
    }

    # 记录镜像内的层 ID 与 tar 文件的 SHA256。
    # 服务器的 deploy.sh 会把镜像 ID 追加到 release.log，
    # 两边对得上才能证明"服务器上跑的确实是这次构建的产物"。
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add("# TrustAgent 发布产物 $Tag")
    $lines.Add("# 生成时间: $(Get-Date -Format 'yyyy-MM-ddTHH:mm:ssK')")
    $lines.Add('')
    foreach ($name in @('trustagent-backend', 'trustagent-ai-service', 'trustagent-web')) {
        $imgId = (docker image inspect "$name`:$Tag" --format '{{.Id}}' 2>$null | Select-Object -First 1)
        $lines.Add("$name`:$Tag imageId=$imgId")
    }
    # 同时记录 jar（deploy/artifacts/ 里那份）的哈希。
    # 服务器上跑的是用这个 jar 组装的 backend 镜像，所以"镜像 ID 对得上"还不够——
    # 还要能证明"这个 jar 是本次构建出来的那一份"（见 deploy/backend-runtime.Dockerfile）。
    $lines.Add('')
    $lines.Add('--- backend jar 校验值 (sha256) ---')
    if ($script:JarSha256) {
        $lines.Add("$($script:JarSha256)  deploy/artifacts/$expectedJarName")
    } else {
        $lines.Add('(未构建 jar：本次带了 -SkipImages 之外的跳过逻辑，或 jar 步骤未执行)')
    }
    $lines.Add('')
    $lines.Add('--- tar 文件校验值 (sha256) ---')
    Get-ChildItem $ImagesDir -Filter "*-$Tag.tar" | ForEach-Object {
        $hash = (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower()
        $lines.Add("$hash  $($_.Name)")
    }

    $sumFile = Join-Path $ImagesDir "SHA256SUMS-$Tag.txt"
    [System.IO.File]::WriteAllLines($sumFile, $lines, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "  校验值文件: deploy/images/SHA256SUMS-$Tag.txt" -ForegroundColor Green

    $totalMb = [math]::Round(((Get-ChildItem $ImagesDir -Filter "*-$Tag.tar" | Measure-Object Length -Sum).Sum / 1MB), 0)
    Write-Host "  镜像包总大小: ${totalMb} MB（scp 上传时需要这个量级的空间）"
}

# ---------------------------------------------------------------------------
Write-Host ''
Write-Host '==== 完成 ====' -ForegroundColor Cyan
Write-Host "版本号: $Tag"
Write-Host ''
Write-Host '下一步（都需要你显式执行，本脚本不会自动做任何一件）:'
Write-Host "  1) 上传到服务器："
Write-Host "       scp -r deploy/images/*-$Tag.tar deploy/ docker-compose.lowmem.yml scripts/ \"
Write-Host "           <user>@<server>:/opt/trustagent/"
Write-Host "  2) 服务器上：先在 .env 里设置 TRUSTAGENT_IMAGE_TAG=$Tag"
Write-Host "  3) 服务器上：./scripts/server/preflight.sh && ./scripts/server/deploy.sh"
Write-Host ''
Write-Host '注意：本脚本**不**推送 Git、**不**发布镜像到任何仓库、**不**连接任何服务器。'
