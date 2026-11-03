#Requires -Version 5.1
<#
.SYNOPSIS
    本地出包：用发布用的同一个 keystore 构建 release APK，供装机验证。

.DESCRIPTION
    为什么要这个脚本：CI 从 push 到 Release 上线端到端约 4 分钟，本地增量构建约 15 秒。
    日常「改代码 → 装机看日志」这一段走本地；单测、验签、发布仍交给 CI（只在打 tag 时触发）。

    产物命名带 `-local` 后缀，明确它是验证包、不是发布包：
    两者签名相同（同一个 keystore），所以能互相覆盖安装，但字节不保证一致
    （CI 用的 build-tools 版本可能与本机不同），交付一律用 Release 里那个。

    keystore 会被临时复制到仓库根的 ci-keystore.jks——build.gradle 固定从那里读。
    复制前若已存在会先备份，脚本结束时无论成败都恢复/清理，不留副本。

.PARAMETER OutDir
    产物输出目录，默认 G:\teleagent工作区\.temp\local-build

.PARAMETER Task
    Gradle 任务，默认 assembleRelease。想看 debug 包就传 assembleDebug。

.PARAMETER Keystore
    发布 keystore 路径

.PARAMETER PasswordFile
    keystore 密码文件（单行）

.EXAMPLE
    .\tools\build-local.ps1

.EXAMPLE
    .\tools\build-local.ps1 -OutDir D:\tmp\apk -Task assembleDebug
#>
[CmdletBinding()]
param(
    [string]$OutDir = 'G:\teleagent工作区\.temp\local-build',
    [string]$Task = 'assembleRelease',
    [string]$Keystore = 'H:\SkipAd-signing\skipad-release.jks',
    [string]$PasswordFile = 'H:\SkipAd-signing\pwd.txt'
)

$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent $PSScriptRoot
$ciJks = Join-Path $repo 'ci-keystore.jks'
$ciJksBak = "$ciJks.bak"
$restoreNeeded = $false

function Fail([string]$msg) {
    Write-Host "失败：$msg" -ForegroundColor Red
    exit 1
}

# —— 前置检查 ——
if (-not (Test-Path -LiteralPath $Keystore)) { Fail "找不到 keystore：$Keystore" }
if (-not (Test-Path -LiteralPath $PasswordFile)) { Fail "找不到密码文件：$PasswordFile" }
if (-not (Test-Path -LiteralPath (Join-Path $repo 'gradlew.bat'))) { Fail "找不到 gradlew.bat，确认脚本放在仓库的 tools\ 下" }

$plainPwd = (Get-Content -LiteralPath $PasswordFile -Raw).Trim()
if ([string]::IsNullOrWhiteSpace($plainPwd)) { Fail "密码文件是空的：$PasswordFile" }

# JDK：优先用环境里已经配好的，没有就退回本机那个固定路径
if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME) -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $fallbackJdk = 'G:\teleagent工作区\.temp\jdk\jdk-21.0.13+9'
    if (-not (Test-Path -LiteralPath (Join-Path $fallbackJdk 'bin\java.exe'))) {
        Fail "没有可用的 JDK 21：请设置 JAVA_HOME，或把 JDK 放到 $fallbackJdk"
    }
    $env:JAVA_HOME = $fallbackJdk
    Write-Host "JAVA_HOME 未设置，改用 $fallbackJdk"
}
$env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH

# 版本号从 build.gradle 读，避免产物名和实际版本对不上
$gradleFile = Join-Path $repo 'app\build.gradle'
$version = 'unknown'
if (Test-Path -LiteralPath $gradleFile) {
    $m = Select-String -LiteralPath $gradleFile -Pattern 'versionName\s+"([^"]+)"' -Encoding UTF8 | Select-Object -First 1
    if ($m) { $version = $m.Matches[0].Groups[1].Value }
}

# —— 临时铺 keystore ——
try {
    if (Test-Path -LiteralPath $ciJks) {
        Move-Item -LiteralPath $ciJks -Destination $ciJksBak -Force
        $restoreNeeded = $true
    }
    Copy-Item -LiteralPath $Keystore -Destination $ciJks -Force

    $env:KEYSTORE_B64 = 'local'          # build.gradle 只把它当开关用，值不参与签名
    $env:KEYSTORE_PASSWORD = $plainPwd

    Write-Host "构建 $Task（版本 $version）..." -ForegroundColor Cyan
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    # gradlew.bat 按「当前目录」找 settings.gradle，脚本可能从任何地方被调用，
    # 必须切到仓库根再跑，否则会报 "does not contain a Gradle build"
    Push-Location -LiteralPath $repo
    try {
        & (Join-Path $repo 'gradlew.bat') --no-daemon -q $Task
        $code = $LASTEXITCODE
    }
    finally {
        Pop-Location
    }
    $sw.Stop()

    if ($code -ne 0) { Fail "Gradle 返回 $code，构建没有完成" }

    # 产物目录随任务不同
    if ($Task -match 'Debug') {
        $apkDir = Join-Path $repo 'app\build\outputs\apk\debug'
    } else {
        $apkDir = Join-Path $repo 'app\build\outputs\apk\release'
    }
    $apk = Get-ChildItem -LiteralPath $apkDir -Filter '*.apk' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $apk) { Fail "构建成功但没找到 APK：$apkDir" }

    if (-not (Test-Path -LiteralPath $OutDir)) {
        New-Item -ItemType Directory -Path $OutDir -Force | Out-Null
    }
    $suffix = if ($Task -match 'Debug') { 'debug' } else { 'local' }
    $dest = Join-Path $OutDir ("daxiaojie-v{0}-{1}.apk" -f $version, $suffix)
    Copy-Item -LiteralPath $apk.FullName -Destination $dest -Force

    $size = [math]::Round((Get-Item -LiteralPath $dest).Length / 1MB, 2)
    Write-Host ("完成：{0}（{1} MB，{2} 秒）" -f $dest, $size, [math]::Round($sw.Elapsed.TotalSeconds, 1)) -ForegroundColor Green
    Write-Host "提示：这是验证包，交付仍以 GitHub Release 的产物为准。"
}
finally {
    # 无论成败都要把 keystore 收干净：仓库里留一份私有密钥副本没有任何好处
    if (Test-Path -LiteralPath $ciJks) { Remove-Item -LiteralPath $ciJks -Force }
    if ($restoreNeeded -and (Test-Path -LiteralPath $ciJksBak)) {
        Move-Item -LiteralPath $ciJksBak -Destination $ciJks -Force
    }
    Remove-Item Env:\KEYSTORE_B64 -ErrorAction SilentlyContinue
    Remove-Item Env:\KEYSTORE_PASSWORD -ErrorAction SilentlyContinue
}
