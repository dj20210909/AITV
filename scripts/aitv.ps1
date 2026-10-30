#Requires -Version 5.1
<#
.SYNOPSIS
    AITV 一体化工具：构建 / 安装 / 同步图标 / 发版到商店（菜单循环，除非手动退出）。

.DESCRIPTION
    双击 scripts\aitv.cmd 使用；也可以带参数一次性执行，例如：
      .\scripts\aitv.ps1 -Action build -App leanback -Install
      .\scripts\aitv.ps1 -Action release -App aitv -VersionCode 580 -VersionName 5.8.0 -NotesFile tmp\notes.txt
      .\scripts\aitv.ps1 -Action install -Serial 192.168.99.137:5151

    菜单循环执行，做完一件事回到菜单，选 [0] 才退出。

    图标两种处理方式（-IconMode asis|resize，交互时会问，默认 asis）：
      asis    原图不缩放、不裁圆角、不裁切，直接放一份到 mipmap-xxxhdpi（TV 横幅放 xxhdpi），
              其余密度不生成，由安卓按密度自动降级取用。
      resize  从原图等比缩放生成 mdpi~xxxhdpi 五套（只缩放，不裁圆角、不裁切）。

    图标源目录：tmp\资源\应用logo（文件名分别带 -phone- / -tv- / 助手）

    版本号、更新说明都有默认值，回车即用默认；更新说明会用记事本打开供修改。

.NOTES
    - 自动处理 JAVA_HOME：系统是 JDK 11 时切到本机 Temurin 21。
    - 构建期间自动移开 gradle\gradle-daemon-jvm.properties（它钉死 JetBrains JDK），结束后还原。
    - 默认 --offline 构建；需要联网拉依赖加 -Online。
    - 安装先试 adb install，被 MIUI 拦下时自动 push + su -c pm install。
    - 设备选择：↑/↓ 移动、空格勾选、回车确认（可多选）；输入被重定向时退回输入序号。
#>
[CmdletBinding()]
param(
    [ValidateSet('', 'menu', 'build', 'install', 'icons', 'release', 'artifacts')]
    [string]$Action = '',
    [ValidateSet('', 'leanback', 'mobile', 'all', 'assistant')]
    [string]$App = '',
    [int]$VersionCode = 0,
    [string]$VersionName = '',
    [ValidateSet('', 'asis', 'resize')]
    [string]$IconMode = '',
    [string]$IconSource = '',
    [string]$Notes = '',
    [string]$NotesFile = '',
    [switch]$Force,
    [switch]$NoForce,
    [string[]]$Serial = @(),
    [switch]$Install,
    [switch]$NoInstall,
    [switch]$SkipBuild,
    [switch]$DryRun,
    [switch]$Online,
    [switch]$Clean,
    [switch]$NoPause,
    [string]$BaseUrl = 'https://nodeapp.uandj.site'
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

$aapt2 = $null
$adb = $null
$failed = $false

# ---------------------------------------------------------------- 输出

function Write-Step([string]$text) { Write-Host ''; Write-Host "==> $text" -ForegroundColor Cyan }
function Write-Ok([string]$text) { Write-Host "    $text" -ForegroundColor Green }
function Write-Warn([string]$text) { Write-Host "    $text" -ForegroundColor Yellow }
function Fail([string]$text) { Write-Host ''; Write-Host "!! $text" -ForegroundColor Red; $script:failed = $true }

function Pause-IfNeeded {
    if ($NoPause) { return }
    Write-Host ''
    Write-Host '按回车继续...' -ForegroundColor DarkGray
    $null = Read-Host
}

# 读取一个序号（防呆：空输入/乱输入不会卡死在循环里）
function Read-Index([string]$prompt, [int]$min, [int]$max) {
    $bad = 0
    while ($true) {
        $answer = Read-Host $prompt
        if ([string]::IsNullOrWhiteSpace($answer)) {
            $bad++
            if ($bad -ge 3) { throw '没有有效输入，已取消' }
            continue
        }
        if ($answer -match '^\d+$' -and [int]$answer -ge $min -and [int]$answer -le $max) { return [int]$answer }
        $bad++
        if ($bad -ge 5) { throw '输入无效，已取消' }
        Write-Warn ("输入 {0}-{1}" -f $min, $max)
    }
}

# ---------------------------------------------------------------- 环境

function Test-JavaHome([string]$path) {
    if ([string]::IsNullOrWhiteSpace($path)) { return $false }
    if (-not (Test-Path (Join-Path $path 'bin\java.exe'))) { return $false }
    $release = Join-Path $path 'release'
    if (Test-Path $release) {
        $hit = Select-String -Path $release -Pattern 'JAVA_VERSION="(\d+)' | Select-Object -First 1
        if ($hit) { return ([int]$hit.Matches[0].Groups[1].Value -ge 21) }
    }
    return $true
}

function Resolve-JavaHome {
    if (Test-JavaHome $env:JAVA_HOME) { return }
    foreach ($path in @('D:\IDE\scoop\apps\temurin21-jdk\current', 'D:\Scoop\apps\temurin21-jdk\current')) {
        if (Test-JavaHome $path) {
            Write-Host "    JAVA_HOME -> $path（原值：$env:JAVA_HOME）" -ForegroundColor DarkGray
            $env:JAVA_HOME = $path
            $env:PATH = (Join-Path $path 'bin') + ';' + $env:PATH
            return
        }
    }
    throw "编译需要 JDK 21，当前 JAVA_HOME='$env:JAVA_HOME'"
}

function Resolve-Adb {
    if ($script:adb) { return $script:adb }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { $script:adb = $cmd.Source; return $script:adb }
    foreach ($path in @('D:\Scoop\apps\android-clt\current\platform-tools\adb.exe', 'D:\IDE\platform-tools\adb.exe')) {
        if (Test-Path $path) { $script:adb = $path; return $script:adb }
    }
    throw '找不到 adb，请把 platform-tools 加到 PATH'
}

function Resolve-Aapt2 {
    if ($script:aapt2) { return $script:aapt2 }
    $roots = @()
    $localProps = Join-Path $repo 'local.properties'
    if (Test-Path $localProps) {
        $sdk = Select-String -Path $localProps -Pattern '^sdk\.dir=(.+)$' | Select-Object -First 1
        if ($sdk) { $roots += $sdk.Matches[0].Groups[1].Value.Trim().Replace('\\', '\') }
    }
    if ($env:ANDROID_HOME) { $roots += $env:ANDROID_HOME }
    if ($env:ANDROID_SDK_ROOT) { $roots += $env:ANDROID_SDK_ROOT }
    $roots += 'D:\IDE\Android\Sdk'
    foreach ($root in $roots) {
        $dir = Join-Path $root 'build-tools'
        if (-not (Test-Path $dir)) { continue }
        $hit = Get-ChildItem $dir -Directory | Sort-Object Name -Descending |
            ForEach-Object { Join-Path $_.FullName 'aapt2.exe' } | Where-Object { Test-Path $_ } | Select-Object -First 1
        if ($hit) { $script:aapt2 = $hit; return $hit }
    }
    throw '找不到 aapt2.exe（需要 Android SDK build-tools）'
}

# ---------------------------------------------------------------- 设备

function Get-AdbDevices {
    $adbPath = Resolve-Adb
    $lines = @(& $adbPath devices -l 2>$null)
    $list = @()
    foreach ($raw in $lines) {
        $line = ($raw -replace "`r", '').Trim()
        if (-not $line) { continue }
        if ($line -like 'List of devices*') { continue }
        if ($line -notmatch '^(?<serial>\S+)\s+(?<state>\S+)') { continue }
        $serial = $matches['serial']
        $state = $matches['state']
        if ($state -notin @('device', 'offline', 'unauthorized')) { continue }
        $model = ''
        if ($line -match 'model:(\S+)') { $model = $matches[1] }
        $list += [pscustomobject]@{ Serial = $serial; State = $state; Model = $model }
    }
    return $list
}

function Connect-DevicePrompt {
    Write-Host ''
    Write-Host '  没有已连接的设备。可以现在连一台：' -ForegroundColor Yellow
    $hostPort = Read-Host '  输入 ip:端口（直接回车跳过）'
    if ([string]::IsNullOrWhiteSpace($hostPort)) { return }
    $adbPath = Resolve-Adb
    & $adbPath connect $hostPort.Trim() | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }
    Start-Sleep -Milliseconds 600
}

function Select-Devices {
    param($Devices)

    # 指定了 -Serial：只校验并只用这些
    if ($Serial.Count -gt 0) {
        $picked = @()
        foreach ($s in $Serial) {
            $hit = $Devices | Where-Object { $_.Serial -eq $s }
            if (-not $hit) { throw "设备未连接：$s" }
            $picked += $hit.Serial
        }
        return $picked
    }

    $usable = @($Devices | Where-Object { $_.State -eq 'device' })
    if ($usable.Count -eq 0) {
        $bad = ($Devices | ForEach-Object { "$($_.Serial)($($_.State))" }) -join ', '
        throw "没有可用设备（device 状态）；当前：$bad"
    }
    if ($usable.Count -eq 1) {
        Write-Host "    设备：$($usable[0].Serial)  $($usable[0].Model)" -ForegroundColor DarkGray
        return @($usable[0].Serial)
    }

    # 多设备：↑/↓ 移动，空格勾选，回车确认
    if ([Console]::IsInputRedirected) {
        Write-Host '    检测到多台设备（输入序号，空格分隔；回车=全部）：'
        for ($i = 0; $i -lt $usable.Count; $i++) { Write-Host ("      [{0}] {1}  {2}" -f ($i + 1), $usable[$i].Serial, $usable[$i].Model) }
        $answer = Read-Host '    序号'
        if ([string]::IsNullOrWhiteSpace($answer)) { return @($usable | ForEach-Object { $_.Serial }) }
        $picks = @()
        foreach ($token in ($answer -split '\s+')) {
            if ($token -match '^\d+$' -and [int]$token -ge 1 -and [int]$token -le $usable.Count) {
                $picks += $usable[[int]$token - 1].Serial
            }
        }
        if ($picks.Count -eq 0) { throw '没有选到设备' }
        return $picks
    }

    Write-Host ''
    Write-Host '    选择设备：↑/↓ 移动，空格选中/取消，A 全选，回车确认' -ForegroundColor Cyan
    $cursor = 0
    $picked = New-Object 'bool[]' $usable.Count
    $top = [Console]::CursorTop
    $width = [Math]::Min(78, [Console]::WindowWidth - 1)

    function Draw-Rows {
        for ($i = 0; $i -lt $usable.Count; $i++) {
            [Console]::SetCursorPosition(0, $top + $i)
            $mark = if ($i -eq $cursor) { '>' } else { ' ' }
            $box = if ($picked[$i]) { '[x]' } else { '[ ]' }
            $line = "{0} {1} {2,-24} {3}" -f $mark, $box, $usable[$i].Serial, $usable[$i].Model
            if ($line.Length -lt $width) { $line = $line.PadRight($width) } else { $line = $line.Substring(0, $width) }
            Write-Host $line -NoNewline
        }
        [Console]::SetCursorPosition(0, $top + $usable.Count)
    }

    try {
        Draw-Rows
        while ($true) {
            $key = [Console]::ReadKey($true)
            switch ($key.Key) {
                'UpArrow' { if ($cursor -gt 0) { $cursor-- } }
                'DownArrow' { if ($cursor -lt $usable.Count - 1) { $cursor++ } }
                'Spacebar' { $picked[$cursor] = -not $picked[$cursor] }
                'A' { $all = -not ($picked -contains $false); for ($i = 0; $i -lt $picked.Count; $i++) { $picked[$i] = -not $all } }
                'Enter' {
                    $result = @()
                    for ($i = 0; $i -lt $usable.Count; $i++) { if ($picked[$i]) { $result += $usable[$i].Serial } }
                    if ($result.Count -eq 0) { $picked[$cursor] = $true; $result = @($usable[$cursor].Serial) }
                    Write-Host ''
                    return $result
                }
                'Escape' { throw '已取消选择设备' }
                default { }
            }
            Draw-Rows
        }
    } finally {
        [Console]::CursorVisible = $true
    }
}

function Get-DeviceAbi([string]$target) {
    $adbPath = Resolve-Adb
    $abi = & $adbPath -s $target shell getprop ro.product.cpu.abi 2>$null
    return (($abi -join '') -replace '\s', '')
}

function Install-Apk {
    param([string]$Target, [string]$ApkPath)

    if (-not (Test-Path -LiteralPath $ApkPath)) { throw "APK 不存在：$ApkPath" }
    $adbPath = Resolve-Adb
    $name = [System.IO.Path]::GetFileName($ApkPath)
    Write-Host "    安装 $name → $Target" -ForegroundColor DarkGray

    $result = & $adbPath -s $Target install -r $ApkPath 2>&1
    if (($result -join "`n") -match 'Success') { return $true }

    Write-Warn 'adb install 失败，改用 root 安装（MIUI 常见）'
    $remote = '/data/local/tmp/aitv-install.apk'
    & $adbPath -s $Target push $ApkPath $remote 2>&1 | Out-Null
    $result = & $adbPath -s $Target shell "su -c 'pm install -r $remote'" 2>&1
    & $adbPath -s $Target shell "su -c 'rm -f $remote'" 2>&1 | Out-Null
    return (($result -join "`n") -match 'Success')
}

function Get-InstalledVersion([string]$target, [string]$package) {
    $adbPath = Resolve-Adb
    $out = & $adbPath -s $target shell "dumpsys package $package | grep -E 'versionName|versionCode'" 2>$null
    return (($out -join ' ').Trim() -replace '\s+', ' ')
}

function Install-ToDevices {
    param([object[]]$Apks, [string]$Flavor, [string]$Package)

    # 参数容错：FileInfo / 路径字符串都接受
    $Apks = @($Apks | ForEach-Object {
            if ($_ -is [System.IO.FileInfo]) { $_ }
            elseif ($_ -is [string] -and (Test-Path -LiteralPath $_)) { Get-Item -LiteralPath $_ }
        } | Where-Object { $_ -and $_.Extension -eq '.apk' })
    if ($Apks.Count -eq 0) { throw '没有可安装的 APK' }

    Write-Step '安装到设备'
    $devices = @(Get-AdbDevices)
    if ($devices.Count -eq 0) {
        Connect-DevicePrompt
        $devices = @(Get-AdbDevices)
    }
    $targets = Select-Devices $devices

    foreach ($target in $targets) {
        $abi = Get-DeviceAbi $target
        Write-Host ''
        Write-Host "  ── $target（$abi）" -ForegroundColor Cyan

        $candidates = $Apks
        if ($Flavor -ne 'assistant') {
            $suffix = switch ($abi) {
                'arm64-v8a' { 'arm64_v8a' }
                'armeabi-v7a' { 'armeabi_v7a' }
                'armeabi' { 'armeabi_v7a' }
                default { '' }
            }
            if (-not $suffix) { Write-Warn "不支持的 ABI：$abi，跳过"; continue }
            $candidates = @($Apks | Where-Object { $_.Name -match "-$suffix\.apk$" })
            if ($candidates.Count -eq 0) { Write-Warn "没有 $suffix 的包，跳过"; continue }
        }

        foreach ($apk in $candidates) {
            if (Install-Apk -Target $target -ApkPath $apk.FullName) {
                Write-Ok "安装成功：$($apk.Name)"
            } else {
                Fail "安装失败：$($apk.Name) → $target"
            }
        }
        $version = Get-InstalledVersion $target $Package
        if ($version) { Write-Ok "设备上的版本：$version" }
    }
}

# ---------------------------------------------------------------- 构建

function Invoke-Gradle {
    param([string[]]$Arguments, [string]$WorkDir = $repo)

    $gradleArgs = @()
    if (-not $Online) { $gradleArgs += '--offline' }
    $initScript = Join-Path $repo 'tmp\mirror.init.gradle'
    if (Test-Path $initScript) { $gradleArgs += @('--init-script', $initScript) }
    $gradleArgs += $Arguments

    Push-Location $WorkDir
    try {
        & (Join-Path $repo 'gradlew.bat') @gradleArgs | Out-Host
        if ($LASTEXITCODE -ne 0) { throw "gradle 构建失败（退出码 $LASTEXITCODE）" }
    } finally {
        Pop-Location
    }
}

function Build-Aitv {
    param([string[]]$Flavors)

    $tasks = @()
    foreach ($flavor in $Flavors) {
        $tasks += ":app:assemble$($flavor.Substring(0, 1).ToUpper())$($flavor.Substring(1))Release"
    }
    if ($Clean) { Invoke-Gradle -Arguments @(':app:clean') }

    Write-Step ("构建 AITV影视：" + ($Flavors -join ' + '))
    $jvmProps = Join-Path $repo 'gradle\gradle-daemon-jvm.properties'
    $hold = Join-Path $repo 'gradle-daemon-jvm.properties.hold'
    $parked = $false
    if (Test-Path $jvmProps) { Move-Item $jvmProps $hold -Force; $parked = $true }
    try {
        Invoke-Gradle -Arguments $tasks
    } finally {
        if ($parked -and (Test-Path $hold)) { Move-Item $hold $jvmProps -Force }
    }

    $apks = @()
    foreach ($flavor in $Flavors) {
        $apks += @(Get-ChildItem (Join-Path $repo 'Release\apk') -Filter "aitv-$flavor-*.apk" -ErrorAction SilentlyContinue)
    }
    if ($apks.Count -eq 0) { throw '没有找到构建产物，检查 Release\apk 目录' }
    foreach ($apk in $apks) {
        Write-Ok ("{0,-34} {1,7:N2} MB  {2}" -f $apk.Name, ($apk.Length / 1MB), $apk.LastWriteTime.ToString('MM-dd HH:mm'))
    }
    return $apks
}

function Build-Assistant {
    if ($Clean) { Invoke-Gradle -Arguments @(':app:clean') -WorkDir (Join-Path $repo 'assistant') }

    Write-Step '构建 AITV 助手'
    Invoke-Gradle -Arguments @(':app:assembleRelease') -WorkDir (Join-Path $repo 'assistant')

    $outDir = Join-Path $repo 'assistant\app\build\outputs\apk\release'
    $apk = Get-ChildItem $outDir -Filter 'aitv-assistant-*-release.apk' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $apk) { throw "没有找到助手构建产物：$outDir" }

    $destDir = Join-Path $repo 'Release\assistant'
    New-Item -ItemType Directory -Force -Path $destDir | Out-Null
    Copy-Item $apk.FullName (Join-Path $destDir $apk.Name) -Force
    $item = Get-Item (Join-Path $destDir $apk.Name)
    Write-Ok ("{0,-42} {1,7:N2} MB  {2}" -f $item.Name, ($item.Length / 1MB), $item.LastWriteTime.ToString('MM-dd HH:mm'))
    return @($item)
}

function Get-AitvApks([string[]]$Flavors) {
    $apks = @()
    foreach ($flavor in $Flavors) {
        $apks += @(Get-ChildItem (Join-Path $repo 'Release\apk') -Filter "aitv-$flavor-*.apk" -ErrorAction SilentlyContinue)
    }
    return $apks
}

function Get-AssistantApk {
    $apk = Get-ChildItem (Join-Path $repo 'Release\assistant') -Filter 'aitv-assistant-*-release.apk' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($apk) { return $apk }
    $apk = Get-ChildItem (Join-Path $repo 'assistant\app\build\outputs\apk\release') -Filter 'aitv-assistant-*-release.apk' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    return $apk
}

# ---------------------------------------------------------------- 图标

$iconBuckets = @('mdpi', 'hdpi', 'xhdpi', 'xxhdpi', 'xxxhdpi')

function Resolve-IconMode([string]$mode) {
    if ($mode) { return $mode }
    Write-Host ''
    Write-Host '  图标处理方式：' -ForegroundColor Cyan
    Write-Host '    [1] 原图直接用（默认）—— 不缩放不裁圆角，只放一张到 xxxhdpi，其余密度交给安卓自动降级'
    Write-Host '    [2] 生成全部密度 —— 从原图等比缩放生成 mdpi~xxxhdpi 五套（不裁圆角）'
    while ($true) {
        $pick = Read-Host '  请选择 [1]'
        switch ($pick) {
            '' { return 'asis' }
            '1' { return 'asis' }
            '2' { return 'resize' }
            default { Write-Warn '输入 1 或 2' }
        }
    }
}

function Clear-IconFiles([string]$resDir, [string]$filter) {
    foreach ($bucket in $iconBuckets) {
        $dir = Join-Path $resDir ("mipmap-" + $bucket)
        if (-not (Test-Path $dir)) { continue }
        Get-ChildItem $dir -File -Filter $filter -ErrorAction SilentlyContinue |
            ForEach-Object { Remove-Item $_.FullName -Force }
    }
}

function Copy-IconAsIs {
    param([string]$ResDir, [string]$Bucket, [string]$Source, [string[]]$Names, [string]$Filter)
    Clear-IconFiles $ResDir $Filter
    $dir = Join-Path $ResDir ("mipmap-" + $Bucket)
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    foreach ($name in $Names) { Copy-Item $Source (Join-Path $dir $name) -Force }
    foreach ($name in $Names) {
        $item = Get-Item (Join-Path $dir $name)
        Write-Ok ("{0,-44} 原图直出 {1:N0} KB" -f ("mipmap-" + $Bucket + "\" + $name), ($item.Length / 1KB))
    }
}

function Sync-Icons {
    param([string]$SourceDir = '', [string]$Mode = 'asis')

    if (-not $SourceDir) { $SourceDir = Join-Path $repo 'tmp\资源\应用logo' }
    if (-not (Test-Path $SourceDir)) { throw "找不到图标源目录：$SourceDir" }

    $files = @(Get-ChildItem $SourceDir -File -Filter '*.png')
    $phone = $files | Where-Object { $_.Name -match '-phone-' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    $tv = $files | Where-Object { $_.Name -match '-tv-' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    $asst = $files | Where-Object { $_.Name -match '助手' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $phone) { throw "源目录里没有 AITV 应用图标（文件名要带 -phone-）：$SourceDir" }
    if (-not $tv) { Write-Warn '没有 TV 横幅源图（文件名要带 -tv-），跳过横幅' }

    if ($Mode -eq 'asis') {
        Write-Step "同步图标（原图直出）：$($phone.Name)"
        Copy-IconAsIs -ResDir (Join-Path $repo 'app\src\main\res') -Bucket 'xxxhdpi' -Source $phone.FullName `
            -Names @('ic_launcher.png', 'ic_launcher_round.png', 'ic_launcher_foreground.png') -Filter 'ic_launcher*.png'

        if ($tv) {
            Write-Step "同步 TV 横幅（原图直出）：$($tv.Name)"
            Copy-IconAsIs -ResDir (Join-Path $repo 'app\src\leanback\res') -Bucket 'xxhdpi' -Source $tv.FullName `
                -Names @('ic_banner.png') -Filter 'ic_banner*.png'
        }
        if ($asst) {
            Write-Step "同步助手图标（原图直出）：$($asst.Name)"
            Copy-IconAsIs -ResDir (Join-Path $repo 'assistant\app\src\main\res') -Bucket 'xxxhdpi' -Source $asst.FullName `
                -Names @('ic_launcher.png', 'ic_launcher_round.png', 'ic_launcher_foreground.png') -Filter 'ic_launcher*.png'
        }
        Write-Host '    其他密度的同名图已清掉，安卓会从 xxxhdpi/xxhdpi 自动降级使用；图标未做任何缩放/裁切。' -ForegroundColor DarkGray
    } else {
        Add-Type -AssemblyName System.Drawing

        function Resize($src, $dst, [int]$w, [int]$h) {
            $img = [System.Drawing.Image]::FromFile($src)
            try {
                $bmp = New-Object System.Drawing.Bitmap($w, $h)
                $g = [System.Drawing.Graphics]::FromImage($bmp)
                $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
                $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
                $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
                $g.DrawImage($img, 0, 0, $w, $h)
                $g.Dispose()
                New-Item -ItemType Directory -Force -Path (Split-Path -Parent $dst) | Out-Null
                $bmp.Save($dst, [System.Drawing.Imaging.ImageFormat]::Png)
                $bmp.Dispose()
            } finally {
                $img.Dispose()
            }
        }

        $densities = @(
            @{ dir = 'mdpi'; icon = 48; fore = 108; bw = 160; bh = 90 },
            @{ dir = 'hdpi'; icon = 72; fore = 162; bw = 240; bh = 135 },
            @{ dir = 'xhdpi'; icon = 96; fore = 216; bw = 320; bh = 180 },
            @{ dir = 'xxhdpi'; icon = 144; fore = 324; bw = 480; bh = 270 },
            @{ dir = 'xxxhdpi'; icon = 192; fore = 432; bw = 640; bh = 360 }
        )

        Write-Step "生成图标（多密度缩放）：$($phone.Name)"
        foreach ($d in $densities) {
            $main = Join-Path $repo ("app\src\main\res\mipmap-" + $d.dir)
            Resize $phone.FullName (Join-Path $main 'ic_launcher.png') $d.icon $d.icon
            Resize $phone.FullName (Join-Path $main 'ic_launcher_round.png') $d.icon $d.icon
            Resize $phone.FullName (Join-Path $main 'ic_launcher_foreground.png') $d.fore $d.fore
        }
        Write-Ok 'AITV 应用图标 ic_launcher / _round / _foreground'

        if ($tv) {
            Write-Step "生成 TV 横幅（多密度缩放）：$($tv.Name)"
            foreach ($d in $densities) {
                $lean = Join-Path $repo ("app\src\leanback\res\mipmap-" + $d.dir)
                Resize $tv.FullName (Join-Path $lean 'ic_banner.png') $d.bw $d.bh
            }
            Write-Ok 'TV 横幅 ic_banner'
        }
        if ($asst) {
            Write-Step "生成助手图标（多密度缩放）：$($asst.Name)"
            foreach ($d in $densities) {
                $dir = Join-Path $repo ("assistant\app\src\main\res\mipmap-" + $d.dir)
                Resize $asst.FullName (Join-Path $dir 'ic_launcher.png') $d.icon $d.icon
                Resize $asst.FullName (Join-Path $dir 'ic_launcher_round.png') $d.icon $d.icon
                Resize $asst.FullName (Join-Path $dir 'ic_launcher_foreground.png') $d.fore $d.fore
            }
            Write-Ok '助手图标 ic_launcher / _round / _foreground'
        }
    }

    Write-Host '    自适应图标底色：app\src\main\res\values\colors.xml（ic_launcher_background）' -ForegroundColor DarkGray
    Write-Host '    启动图背景：leanback\res\values\styles.xml（windowSplashScreenBackground）' -ForegroundColor DarkGray
}

# ---------------------------------------------------------------- 版本 / 发版

function Get-GradleVersion([string]$path) {
    $text = [System.IO.File]::ReadAllText($path)
    $code = [regex]::Match($text, '(?m)^\s*versionCode\s+(\d+)').Groups[1].Value
    $name = [regex]::Match($text, '(?m)^\s*versionName\s+"([^"]*)"').Groups[1].Value
    return @{ Code = [int]$code; Name = $name }
}

function Set-GradleVersion([string]$path, [int]$code, [string]$name) {
    $text = [System.IO.File]::ReadAllText($path)
    $text = [regex]::Replace($text, '(?m)^(\s*versionCode\s+)\d+', ('${1}' + $code))
    $text = [regex]::Replace($text, '(?m)^(\s*versionName\s+)"[^"]*"', ('${1}"' + $name + '"'))
    [System.IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding($false)))
}

function Get-StoreVersion([string]$appKey) {
    try {
        $json = Invoke-RestMethod -Uri "$BaseUrl/api/file-sharing/app-store/$appKey/update.json" -Method Get -TimeoutSec 30
        return @{ Code = [int]$json.versionCode; Name = [string]$json.versionName }
    } catch {
        return @{ Code = 0; Name = '' }
    }
}

function Read-ApkVersion([string]$apk) {
    $aapt = Resolve-Aapt2
    $line = (& $aapt dump badging $apk 2>$null | Select-String -Pattern '^package:' | Select-Object -First 1).Line
    if (-not $line) { return $null }
    return @{
        Code = [int][regex]::Match($line, "versionCode='(\d+)'").Groups[1].Value
        Name = [regex]::Match($line, "versionName='([^']*)'").Groups[1].Value
        Size = (Get-Item $apk).Length
    }
}

function Bump-VersionName([string]$name, [int]$delta) {
    if (-not $name) { return "0.0.$delta" }
    $parts = $name.Split('.')
    $last = $parts[$parts.Length - 1]
    if ($last -match '^\d+$') {
        $parts[$parts.Length - 1] = [string]([int]$last + $delta)
        return ($parts -join '.')
    }
    return "$name.$delta"
}

function Read-Version([string]$label, $local, $store, [int]$fixedCode, [string]$fixedName) {
    $defaultCode = if ($local.Code -gt $store.Code) { $local.Code } else { $store.Code + 1 }
    $defaultName = if ($defaultCode -eq $local.Code -and $local.Name) { $local.Name } else { Bump-VersionName $local.Name ([Math]::Max(1, $defaultCode - $local.Code)) }

    Write-Host ''
    Write-Host "  ── $label ──" -ForegroundColor Cyan
    Write-Host ("     商店：{0} ({1})    本地：{2} ({3})" -f $(if ($store.Name) { $store.Name } else { '未发布' }), $store.Code, $local.Name, $local.Code) -ForegroundColor DarkGray

    $code = $defaultCode
    if ($fixedCode -gt 0) {
        $code = $fixedCode
    } else {
        while ($true) {
            $answer = Read-Host ("     versionCode [{0}]" -f $defaultCode)
            if ([string]::IsNullOrWhiteSpace($answer)) { break }
            if ($answer -match '^\d+$') {
                $code = [int]$answer
                if ($code -le $store.Code) { Write-Warn "必须大于商店当前版本 $($store.Code)"; continue }
                break
            }
            Write-Warn '请输入数字'
        }
        if ($code -le $store.Code) { throw "versionCode 必须大于商店当前的 $($store.Code)" }
    }

    $name = $defaultName
    if ($fixedName) {
        $name = $fixedName
    } else {
        $answer = Read-Host ("     versionName [{0}]" -f $defaultName)
        if (-not [string]::IsNullOrWhiteSpace($answer)) { $name = $answer.Trim() }
    }
    if (-not $name) { throw 'versionName 不能为空' }

    Write-Host ("     → 将发布 {0} ({1})" -f $name, $code) -ForegroundColor Yellow
    return @{ Code = $code; Name = $name }
}

function Resolve-Notes([string]$appLabel) {
    if ($NotesFile) {
        if (-not (Test-Path $NotesFile)) { throw "找不到更新说明文件：$NotesFile" }
        return [System.IO.File]::ReadAllText((Resolve-Path $NotesFile), [System.Text.Encoding]::UTF8).Trim()
    }
    if ($Notes) { return $Notes.Trim() }

    $default = "1. 全新品牌图标`r`n2. 修复已知问题`r`n3. 优化播放体验"
    $path = Join-Path $repo 'tmp\release-notes.txt'
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $path) | Out-Null
    [System.IO.File]::WriteAllText($path, $default, (New-Object System.Text.UTF8Encoding($true)))

    Write-Host ''
    Write-Host '  更新说明：将用记事本打开，改成你要的内容，保存并关闭记事本即可。' -ForegroundColor Cyan
    $null = Read-Host '  按回车打开记事本'
    try { Start-Process -FilePath 'notepad.exe' -ArgumentList "`"$path`"" -Wait }
    catch { Write-Warn "打不开记事本（$($_.Exception.Message)），沿用默认内容" }

    $text = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8).Trim().TrimStart([char]0xFEFF)
    if ([string]::IsNullOrWhiteSpace($text)) { Write-Warn '内容为空，沿用默认'; return ($default -replace "`r`n", "`n") }
    return $text.Trim()
}

function Confirm-Force {
    if ($Force) { return $true }
    if ($NoForce) { return $false }
    $answer = Read-Host '  是否强制更新？[y/N]'
    return ($answer -match '^(y|yes|是)$')
}

function Confirm-Publish([string]$summary) {
    Write-Host ''
    Write-Host '────────── 发布确认 ──────────' -ForegroundColor Yellow
    Write-Host $summary
    Write-Host '──────────────────────────────' -ForegroundColor Yellow
    $answer = Read-Host '确认发布？[Y/n]'
    return -not ($answer -match '^(n|no|否)$')
}

function Publish-Artifact {
    param(
        [string]$ApkPath, [string]$AppKey, [string]$AppName,
        [int]$Code, [string]$Name, [string]$NotesPath, [bool]$ForceUpdate
    )
    if ($DryRun) {
        Write-Host ("    [dry-run] {0,-30} ← {1}" -f $AppKey, [System.IO.Path]::GetFileName($ApkPath)) -ForegroundColor Yellow
        return $null
    }
    $resp = Join-Path $env:TEMP ("aitv-release-" + $AppKey + ".json")
    $curlArgs = @(
        '--silent', '--show-error', '--max-time', '1800',
        '-X', 'POST', "$BaseUrl/api/file-sharing/app-store/upload",
        '-F', "file=@$ApkPath",
        '-F', "appKey=$AppKey",
        '-F', "appName=$AppName",
        '-F', "versionCode=$Code",
        '-F', "versionName=$Name",
        '-F', "force=$([int]$ForceUpdate)",
        '-F', 'generateUpdate=1',
        '-F', "updateContent=<$NotesPath",
        '--output', $resp
    )
    if ($env:FILE_SHARING_UPLOAD_TOKEN) { $curlArgs += @('-H', "x-upload-token: $env:FILE_SHARING_UPLOAD_TOKEN") }
    $null = & curl.exe @curlArgs
    if ($LASTEXITCODE -ne 0) { throw "上传失败（curl $LASTEXITCODE）：$AppKey" }
    $json = [System.IO.File]::ReadAllText($resp, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
    Remove-Item $resp -Force -ErrorAction SilentlyContinue
    if (-not $json.success) { throw "商店拒绝发布 $AppKey：$($json.error)" }
    return $json
}

function Confirm-UpdateJson([string]$appKey, [int]$code, [long]$size) {
    if ($DryRun) { return $true }
    try {
        $json = Invoke-RestMethod -Uri "$BaseUrl/api/file-sharing/app-store/$appKey/update.json" -Method Get -TimeoutSec 60
        $ok = ($json.versionCode -eq $code) -and ([long]$json.size -eq $size)
        if (-not $ok) { Write-Warn "$appKey 校验不一致：期望 $code/$size，实际 $($json.versionCode)/$($json.size)" }
        return $ok
    } catch {
        Write-Warn "$appKey 校验失败：$($_.Exception.Message)"
        return $false
    }
}

$releaseTargets = @{
    'aitv'      = @{ Flavors = @('leanback', 'mobile'); Assistant = $false; Label = 'AITV影视' }
    'leanback'  = @{ Flavors = @('leanback'); Assistant = $false; Label = 'AITV影视（TV）' }
    'mobile'    = @{ Flavors = @('mobile'); Assistant = $false; Label = 'AITV影视（手机）' }
    'assistant' = @{ Flavors = @(); Assistant = $true; Label = 'AITV 助手' }
    'all'       = @{ Flavors = @('leanback', 'mobile'); Assistant = $true; Label = 'AITV影视 + 助手' }
}

$apkAppKeyMap = @{
    'leanback-arm64_v8a'   = @('aitv-leanback-arm64-v8a', 'AITV-TV-arm64-v8a')
    'leanback-armeabi_v7a' = @('aitv-leanback-armeabi-v7a', 'AITV-TV-armeabi-v7a')
    'mobile-arm64_v8a'     = @('aitv-mobile-arm64-v8a', 'AITV-Phone-arm64-v8a')
    'mobile-armeabi_v7a'   = @('aitv-mobile-armeabi-v7a', 'AITV-Phone-armeabi-v7a')
}

function Invoke-Release {
    param([string]$Target)

    $plan = $releaseTargets[$Target]
    $localAitv = Get-GradleVersion (Join-Path $repo 'app\build.gradle')
    $localAsst = Get-GradleVersion (Join-Path $repo 'assistant\app\build.gradle')

    $pubCode = 0; $pubName = ''
    if ($plan.Flavors.Count -gt 0) {
        $ver = Read-Version 'AITV影视' $localAitv (Get-StoreVersion 'aitv-leanback-arm64-v8a') $VersionCode $VersionName
        $pubCode = $ver.Code; $pubName = $ver.Name
    }
    $asstVer = $null
    if ($plan.Assistant) {
        $fixedCode = 0; $fixedName = ''
        if ($plan.Flavors.Count -eq 0) { $fixedCode = $VersionCode; $fixedName = $VersionName }
        $asstVer = Read-Version 'AITV 助手' $localAsst (Get-StoreVersion 'aitv-assistant') $fixedCode $fixedName
    }

    $notesText = Resolve-Notes $plan.Label
    $forceUpdate = Confirm-Force

    $lines = @("  目标：$($plan.Label)")
    if ($plan.Flavors.Count -gt 0) { $lines += ("  AITV影视：{0} ({1})" -f $pubName, $pubCode) }
    if ($asstVer) { $lines += ("  AITV 助手：{0} ({1})" -f $asstVer.Name, $asstVer.Code) }
    $lines += "  强制更新：$(if ($forceUpdate) { '是' } else { '否' })"
    $lines += '  更新说明：'
    foreach ($l in ($notesText -split "`r?`n")) { $lines += "    $l" }
    if ($DryRun) { $lines += '  [DryRun] 只构建，不上传' }
    if (-not (Confirm-Publish ($lines -join "`n"))) { Write-Warn '已取消'; return }

    # 写版本 + 构建
    if ($plan.Flavors.Count -gt 0) {
        if ($localAitv.Code -ne $pubCode -or $localAitv.Name -ne $pubName) {
            Set-GradleVersion (Join-Path $repo 'app\build.gradle') $pubCode $pubName
            Write-Ok "app/build.gradle → $pubName ($pubCode)"
        }
        if (-not $SkipBuild) {
            $buildApp = if ($plan.Flavors.Count -gt 1) { 'all' } else { $plan.Flavors[0] }
            $null = Build-Aitv -Flavors $(if ($buildApp -eq 'all') { @('leanback', 'mobile') } else { @($buildApp) })
        }
    }
    if ($asstVer) {
        if ($localAsst.Code -ne $asstVer.Code -or $localAsst.Name -ne $asstVer.Name) {
            Set-GradleVersion (Join-Path $repo 'assistant\app\build.gradle') $asstVer.Code $asstVer.Name
            Write-Ok "assistant/app/build.gradle → $($asstVer.Name) ($($asstVer.Code))"
        }
        if (-not $SkipBuild) { $null = Build-Assistant }
    }

    # 核对产物版本
    $apks = @()
    foreach ($flavor in $plan.Flavors) { $apks += @(Get-AitvApks @($flavor)) }
    foreach ($apk in $apks) {
        $info = Read-ApkVersion $apk.FullName
        if (-not $info) { throw "读不到 APK 版本：$($apk.Name)" }
        if ($info.Code -ne $pubCode -or $info.Name -ne $pubName) {
            throw ("$($apk.Name) 里是 {0} ({1})，和要发布的 {2} ({3}) 不一致（构建没跑？）" -f $info.Name, $info.Code, $pubName, $pubCode)
        }
    }
    $asstApk = $null
    if ($asstVer) {
        $asstApk = Get-AssistantApk
        if (-not $asstApk) { throw '没有助手构建产物' }
        $info = Read-ApkVersion $asstApk.FullName
        if ($info.Code -ne $asstVer.Code -or $info.Name -ne $asstVer.Name) {
            throw "助手 APK 是 $($info.Name) ($($info.Code))，和要发布的 $($asstVer.Name) ($($asstVer.Code)) 不一致"
        }
    }

    # 上传
    $notesPath = Join-Path $repo 'tmp\release-notes.txt'
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $notesPath) | Out-Null
    [System.IO.File]::WriteAllText($notesPath, $notesText, (New-Object System.Text.UTF8Encoding($false)))

    $results = @()
    if ($plan.Flavors.Count -gt 0) {
        Write-Step '发布 AITV影视'
        foreach ($apk in ($apks | Sort-Object Name)) {
            $key = $apk.BaseName -replace '^aitv-', ''
            if (-not $apkAppKeyMap.ContainsKey($key)) { Write-Warn "跳过未知产物：$($apk.Name)"; continue }
            $appKey = $apkAppKeyMap[$key][0]
            $appName = $apkAppKeyMap[$key][1]
            Write-Host "    上传 $appKey ..." -NoNewline
            $null = Publish-Artifact -ApkPath $apk.FullName -AppKey $appKey -AppName $appName `
                -Code $pubCode -Name $pubName -NotesPath $notesPath -ForceUpdate $forceUpdate
            Write-Host ' ok' -ForegroundColor Green
            $results += [pscustomobject]@{ AppKey = $appKey; Size = $apk.Length }
        }
    }
    if ($asstVer) {
        Write-Step '发布 AITV 助手'
        Write-Host '    上传 aitv-assistant ...' -NoNewline
        $null = Publish-Artifact -ApkPath $asstApk.FullName -AppKey 'aitv-assistant' -AppName 'AITV-Assistant' `
            -Code $asstVer.Code -Name $asstVer.Name -NotesPath $notesPath -ForceUpdate $forceUpdate
        Write-Host ' ok' -ForegroundColor Green
        $results += [pscustomobject]@{ AppKey = 'aitv-assistant'; Size = $asstApk.Length }
    }

    Write-Step '校验 update.json'
    $bad = 0
    foreach ($item in $results) {
        $code = if ($item.AppKey -eq 'aitv-assistant') { $asstVer.Code } else { $pubCode }
        if (Confirm-UpdateJson -appKey $item.AppKey -code $code -size $item.Size) {
            Write-Host ("    {0,-30} OK" -f $item.AppKey) -ForegroundColor Green
        } else {
            Write-Host ("    {0,-30} FAIL" -f $item.AppKey) -ForegroundColor Red
            $bad++
        }
    }
    if (-not $DryRun) {
        Write-Step 'update.json'
        foreach ($item in $results) { Write-Host ("    $BaseUrl/api/file-sharing/app-store/{0}/update.json" -f $item.AppKey) }
    }
    if ($bad -gt 0) { Fail "$bad 个应用校验不一致" }

    # 可选安装
    $doInstall = $Install
    if (-not $Install -and -not $NoInstall -and -not $DryRun) {
        $answer = Read-Host '  是否顺便安装到设备？[y/N]'
        $doInstall = ($answer -match '^(y|yes|是)$')
    }
    if ($doInstall) {
        $flavor = if ($plan.Flavors.Count -eq 1) { $plan.Flavors[0] } elseif ($plan.Flavors.Count -gt 1) { 'leanback' } else { 'assistant' }
        $pkg = if ($flavor -eq 'assistant') { 'com.aitv.assistant' } else { 'com.fongmi.android.tv' }
        $installApks = if ($flavor -eq 'assistant') { @($asstApk) } else { @(Get-AitvApks @($flavor)) }
        Install-ToDevices -Apks $installApks -Flavor $flavor -Package $pkg
    }
}

# ---------------------------------------------------------------- 动作

function Ask-BuildTarget {
    Write-Host ''
    Write-Host '  构建哪个？' -ForegroundColor Cyan
    Write-Host '    [1] AITV影视 TV 版（leanback：TV 盒子 / 手机上装的 TV 版）'
    Write-Host '    [2] AITV影视 手机版（mobile）'
    Write-Host '    [3] AITV影视 全部（TV + 手机）'
    Write-Host '    [4] AITV 助手'
    switch (Read-Index '  请选择' 1 4) {
        1 { return 'leanback' }
        2 { return 'mobile' }
        3 { return 'all' }
        4 { return 'assistant' }
    }
}

function Invoke-BuildAction {
    param([string]$Target)
    if (-not $Target) { $Target = Ask-BuildTarget }

    $doIcons = $false
    $answer = Read-Host '  构建前先按源图同步图标？（tmp\资源\应用logo）[y/N]'
    $doIcons = ($answer -match '^(y|yes|是)$')
    if ($doIcons) {
        Sync-Icons -SourceDir $IconSource -Mode (Resolve-IconMode $IconMode)
    }

    $apks = @()
    $flavor = $Target
    switch ($Target) {
        'leanback' { $apks = @(Build-Aitv -Flavors @('leanback')) }
        'mobile' { $apks = @(Build-Aitv -Flavors @('mobile')) }
        'all' {
            $apks = @(Build-Aitv -Flavors @('leanback', 'mobile'))
            $answer = Read-Host '  安装哪一版？[1] TV 版  [2] 手机版 [直接回车=TV]'
            if ($answer -eq '2') { $flavor = 'mobile' } else { $flavor = 'leanback' }
        }
        'assistant' { $apks = @(Build-Assistant); $flavor = 'assistant' }
    }

    $pkg = if ($flavor -eq 'assistant') { 'com.aitv.assistant' } else { 'com.fongmi.android.tv' }
    $installApks = if ($flavor -eq 'assistant') { $apks } else { @(Get-AitvApks @($flavor)) }

    $doInstall = $Install
    if (-not $Install -and -not $NoInstall) {
        $answer = Read-Host '  是否安装到设备？[y/N]'
        $doInstall = ($answer -match '^(y|yes|是)$')
    }
    if ($doInstall) {
        Install-ToDevices -Apks $installApks -Flavor $flavor -Package $pkg
    } else {
        Write-Ok '已跳过安装'
    }
}

function Invoke-InstallAction {
    Write-Host ''
    Write-Host '  安装哪个？' -ForegroundColor Cyan
    Write-Host '    [1] AITV影视 TV 版（Release\apk\aitv-leanback-*.apk）'
    Write-Host '    [2] AITV影视 手机版（Release\apk\aitv-mobile-*.apk）'
    Write-Host '    [3] AITV 助手（Release\assistant\...apk）'
    $target = switch (Read-Index '  请选择' 1 3) {
        1 { 'leanback' }
        2 { 'mobile' }
        3 { 'assistant' }
    }

    if ($target -eq 'assistant') {
        $apk = Get-AssistantApk
        if (-not $apk) { throw '没有助手 APK，先构建一次' }
        Install-ToDevices -Apks @($apk) -Flavor 'assistant' -Package 'com.aitv.assistant'
    } else {
        $apks = @(Get-AitvApks @($target))
        if ($apks.Count -eq 0) { throw "没有 $target 的 APK，先构建一次" }
        Install-ToDevices -Apks $apks -Flavor $target -Package 'com.fongmi.android.tv'
    }
}

function Invoke-IconsAction {
    Sync-Icons -SourceDir $IconSource -Mode (Resolve-IconMode $IconMode)
}

function Invoke-ReleaseAction([string]$Target) {
    if (-not $Target) {
        Write-Host ''
        Write-Host '  发哪个？' -ForegroundColor Cyan
        Write-Host '    [1] AITV影视 全部 4 个变体'
        Write-Host '    [2] AITV影视 只发 TV 版（2 个）'
        Write-Host '    [3] AITV影视 只发手机版（2 个）'
        Write-Host '    [4] AITV 助手'
        Write-Host '    [5] AITV影视 4 个变体 + 助手'
        $Target = switch (Read-Index '  请选择' 1 5) {
            1 { 'aitv' }
            2 { 'leanback' }
            3 { 'mobile' }
            4 { 'assistant' }
            5 { 'all' }
        }
    }
    Invoke-Release -Target $Target
}

function Show-Artifacts {
    Write-Step '现有产物'
    $any = $false
    foreach ($flavor in @('leanback', 'mobile')) {
        foreach ($apk in (Get-AitvApks @($flavor))) {
            $any = $true
            Write-Host ("    {0,-34} {1,7:N2} MB  {2}" -f $apk.Name, ($apk.Length / 1MB), $apk.LastWriteTime.ToString('MM-dd HH:mm'))
        }
    }
    $asst = Get-AssistantApk
    if ($asst) {
        $any = $true
        Write-Host ("    {0,-34} {1,7:N2} MB  {2}" -f $asst.Name, ($asst.Length / 1MB), $asst.LastWriteTime.ToString('MM-dd HH:mm'))
    }
    if (-not $any) { Write-Warn '还没有构建产物' }

    Write-Host ''
    $devices = @(Get-AdbDevices)
    if ($devices.Count -gt 0) {
        Write-Step '当前连接的设备'
        foreach ($d in $devices) { Write-Host ("    {0,-24} {1,-12} {2}" -f $d.Serial, $d.State, $d.Model) }
    } else {
        Write-Warn '当前没有连接的设备'
    }
}

# ---------------------------------------------------------------- 主循环

function Show-MainMenu {
    Write-Host ''
    Write-Host '============== AITV 工具 ==============' -ForegroundColor Cyan
    Write-Host '  [1] 构建（TV / 手机 / 全部 / 助手）'
    Write-Host '  [2] 安装到设备（用现有产物）'
    Write-Host '  [3] 同步图标（原图直出 / 多密度）'
    Write-Host '  [4] 发版到应用商店（构建 + 发布 + 校验）'
    Write-Host '  [5] 查看产物和已连接设备'
    Write-Host '  [0] 退出'
    Write-Host '======================================='
}

function Invoke-Once([string]$name) {
    switch ($name) {
        'build' { Invoke-BuildAction $App }
        'install' { Invoke-InstallAction }
        'icons' { Invoke-IconsAction }
        'release' { Invoke-ReleaseAction $App }
        'artifacts' { Show-Artifacts }
    }
}

$exitCode = 0
try {
    Resolve-JavaHome | Out-Null
    Write-Host "    项目：$repo" -ForegroundColor DarkGray

    if ($Action -and $Action -ne 'menu') {
        Invoke-Once $Action
        if ($failed) { $exitCode = 1 }
    } else {
        while ($true) {
            Show-MainMenu
            $pick = Read-Host '请选择'
            # 空输入（例如输入流结束）直接退出，避免卡在循环里
            if ([string]::IsNullOrWhiteSpace($pick)) { break }
            if ($pick -eq '0') { break }
            $failed = $false
            try {
                switch ($pick) {
                    '1' { Invoke-BuildAction $App }
                    '2' { Invoke-InstallAction }
                    '3' { Invoke-IconsAction }
                    '4' { Invoke-ReleaseAction $App }
                    '5' { Show-Artifacts }
                    default { Write-Warn '输入 0-5'; continue }
                }
            } catch {
                Fail $_.Exception.Message
            }
            if ($failed) {
                Write-Host ''
                Write-Host '这一步有失败，改完可以再选一次' -ForegroundColor Red
            } else {
                Write-Host ''
                Write-Host '完成，回到菜单' -ForegroundColor Green
            }
            Pause-IfNeeded
        }
    }
} catch {
    Fail $_.Exception.Message
    $exitCode = 1
    Pause-IfNeeded
}

Write-Host ''
if ($exitCode -eq 0) { Write-Host '结束' -ForegroundColor Green } else { Write-Host '结束（有失败项）' -ForegroundColor Red }
exit $exitCode
