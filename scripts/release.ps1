#Requires -Version 5.1
<#
.SYNOPSIS
    Build and publish AITV release APKs to the nodeapp App Store.

.DESCRIPTION
    Reads versionCode/versionName straight from the built APKs (no manual sync with
    build.gradle), derives the appKey from the artifact name, publishes every
    flavor+ABI combination, then verifies each generated update.json.

    artifact name              -> appKey
    aitv-leanback-arm64_v8a.apk -> aitv-leanback-arm64-v8a
    aitv-mobile-armeabi_v7a.apk -> aitv-mobile-armeabi-v7a

.EXAMPLE
    .\scripts\release.ps1 -Notes "修复播放器崩溃"

.EXAMPLE
    .\scripts\release.ps1 -SkipBuild -Flavors leanback -DryRun

.EXAMPLE
    .\scripts\release.ps1 -NotesFile .\release-notes.txt -Force
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'https://nodeapp.uandj.site',
    [string]$UploadToken = $env:FILE_SHARING_UPLOAD_TOKEN,
    [string]$Notes = '',
    [string]$NotesFile = '',
    [switch]$Force,
    [string]$Flavors = 'leanback,mobile',
    [string]$Only = '',
    [switch]$SkipBuild,
    [switch]$VerifyOnly,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$repo = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path (Join-Path $repo 'settings.gradle'))) { throw "cannot locate repo root from $PSScriptRoot" }

$appStoreUrl = "$BaseUrl/api/file-sharing/app-store"
$apkDir = Join-Path $repo 'Release\apk'

function Write-Step([string]$text) {
    Write-Host ''
    Write-Host "==> $text" -ForegroundColor Cyan
}

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
    # System JAVA_HOME is often JDK 11; AGP 9 needs 17+, and this project targets 21.
    $fallback = 'D:\IDE\scoop\apps\temurin21-jdk\current'
    if (Test-JavaHome $fallback) {
        Write-Host "JAVA_HOME -> $fallback (was: $env:JAVA_HOME)"
        $env:JAVA_HOME = $fallback
        return
    }
    throw "JAVA_HOME must point to JDK 21+ (current: '$env:JAVA_HOME')"
}

function Resolve-Aapt2 {
    $roots = @()
    $localProps = Join-Path $repo 'local.properties'
    if (Test-Path $localProps) {
        $line = Select-String -Path $localProps -Pattern '^sdk\.dir=(.+)$' | Select-Object -First 1
        if ($line) { $roots += $line.Matches[0].Groups[1].Value.Trim() }
    }
    $roots += @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, 'D:\Scoop\apps\android-clt\current')
    foreach ($root in $roots) {
        if ([string]::IsNullOrWhiteSpace($root)) { continue }
        $buildTools = Join-Path $root 'build-tools'
        if (-not (Test-Path $buildTools)) { continue }
        $latest = Get-ChildItem $buildTools -Directory | Sort-Object Name -Descending | Select-Object -First 1
        if (-not $latest) { continue }
        $exe = Join-Path $latest.FullName 'aapt2.exe'
        if (Test-Path $exe) { return $exe }
    }
    throw 'aapt2.exe not found; pass a valid sdk.dir in local.properties'
}

function Read-ApkInfo([string]$apk, [string]$aapt2) {
    $lines = & $aapt2 dump badging $apk 2>$null
    $hit = $lines | Select-String -Pattern "^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'" | Select-Object -First 1
    if (-not $hit) { throw "cannot read package info from $apk" }
    $match = $hit.Matches[0]
    return [pscustomobject]@{
        Package     = $match.Groups[1].Value
        VersionCode = [int]$match.Groups[2].Value
        VersionName = $match.Groups[3].Value
    }
}

function Get-Target([System.IO.FileInfo]$apk) {
    $base = $apk.BaseName
    if ($base -notlike 'aitv-*') { return $null }
    $flavor = $null
    if ($base -like 'aitv-leanback-*') { $flavor = 'leanback' }
    elseif ($base -like 'aitv-mobile-*') { $flavor = 'mobile' }
    if (-not $flavor) { return $null }
    $abi = $null
    if ($base -like '*arm64_v8a*') { $abi = 'arm64-v8a' }
    elseif ($base -like '*armeabi_v7a*') { $abi = 'armeabi-v7a' }
    if (-not $abi) { return $null }
    $label = if ($flavor -eq 'leanback') { 'TV' } else { 'Phone' }
    return [pscustomobject]@{
        File    = $apk
        Flavor  = $flavor
        Abi     = $abi
        AppKey  = "aitv-$flavor-$abi"
        AppName = "AITV-$label-$abi"
    }
}

function Invoke-ReleaseBuild {
    param([string[]]$OnlyFlavors)
    Resolve-JavaHome
    $jvmProps = Join-Path $repo 'gradle\gradle-daemon-jvm.properties'
    $hold = Join-Path $repo 'gradle-daemon-jvm.properties.hold'
    # This file pins a JetBrains JDK 21 toolchain; machines with Temurin only cannot
    # resolve it offline, so park it for the duration of the build.
    $parked = $false
    if (Test-Path $jvmProps) {
        Move-Item $jvmProps $hold -Force
        $parked = $true
    }
    try {
        $gradleArgs = @('--offline')
        foreach ($flavor in $OnlyFlavors) {
            $gradleArgs += ":app:assemble$($flavor.Substring(0, 1).ToUpper())$($flavor.Substring(1))Release"
        }
        $initScript = Join-Path $repo 'tmp\mirror.init.gradle'
        if (Test-Path $initScript) { $gradleArgs += @('--init-script', $initScript) }
        Write-Host "gradlew $($gradleArgs -join ' ')"
        & (Join-Path $repo 'gradlew.bat') @gradleArgs
        if ($LASTEXITCODE -ne 0) { throw "gradle build failed with exit code $LASTEXITCODE" }
    } finally {
        if ($parked) { Move-Item $hold $jvmProps -Force }
    }
}

function New-NotesFile([string]$text) {
    $path = Join-Path $repo 'tmp\release-notes.txt'
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $path) | Out-Null
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($path, $text, $utf8)
    return $path
}

function Publish-Apk {
    param(
        [pscustomobject]$Target,
        [int]$VersionCode,
        [string]$VersionName,
        [string]$NotesPath
    )
    $curlArgs = @(
        '--silent', '--show-error', '--max-time', '1800',
        '-X', 'POST', "$appStoreUrl/upload",
        '-F', "file=@$($Target.File.FullName)",
        '-F', "appKey=$($Target.AppKey)",
        '-F', "appName=$($Target.AppName)",
        '-F', "versionCode=$VersionCode",
        '-F', "versionName=$VersionName",
        '-F', "force=$([int]$Force.IsPresent)",
        '-F', 'generateUpdate=1',
        '-F', "updateContent=<$NotesPath"
    )
    if ($UploadToken) { $curlArgs += @('-H', "x-upload-token: $UploadToken") }
    # Write the response to a file and decode it as UTF-8: capturing curl's stdout goes
    # through the console code page, which mangles non-ASCII updateContent and breaks JSON.
    $responsePath = Join-Path $env:TEMP ("aitv-publish-" + $Target.AppKey + ".json")
    $curlArgs += @('--output', $responsePath)
    $null = & curl.exe @curlArgs
    if ($LASTEXITCODE -ne 0) { throw "upload failed (curl exit $LASTEXITCODE) for $($Target.AppKey)" }
    $raw = [System.IO.File]::ReadAllText($responsePath, [System.Text.Encoding]::UTF8)
    Remove-Item $responsePath -Force -ErrorAction SilentlyContinue
    $json = $raw | ConvertFrom-Json
    if (-not $json.success) { throw "publish rejected for $($Target.AppKey): $($json.error)" }
    return $json
}

function Confirm-UpdateJson {
    param([string]$AppKey, [int]$VersionCode, [long]$ExpectedSize)
    $url = "$appStoreUrl/$AppKey/update.json"
    $json = Invoke-RestMethod -Uri $url -Method Get -TimeoutSec 60
    $remote = Get-RemoteLength $json.url
    $ok = ($json.versionCode -eq $VersionCode) -and ([long]$json.size -eq $ExpectedSize) -and (($remote -le 0) -or ($remote -eq $ExpectedSize))
    return [pscustomobject]@{
        AppKey       = $AppKey
        Ok           = $ok
        VersionCode  = $json.versionCode
        Size         = [long]$json.size
        ExpectedSize = $ExpectedSize
        RemoteSize   = $remote
        DownloadUrl  = $json.url
    }
}

function Get-RemoteLength([string]$url) {
    if ([string]::IsNullOrWhiteSpace($url)) { return -1 }
    try {
        $response = Invoke-WebRequest -Uri $url -Method Head -TimeoutSec 60 -UseBasicParsing
        $value = $response.Headers['Content-Length']
        if ($value -is [array]) { $value = $value[0] }
        if (-not $value) { return -1 }
        return [long]$value
    } catch {
        return -1
    }
}

# ---------------------------------------------------------------- main

$flavorList = @($Flavors.Split(',') | ForEach-Object { $_.Trim().ToLower() } | Where-Object { $_ })
foreach ($flavor in $flavorList) {
    if ($flavor -notin @('leanback', 'mobile')) { throw "unknown flavor '$flavor' (expected leanback or mobile)" }
}

if (-not $SkipBuild) {
    Write-Step 'Building release APKs'
    Invoke-ReleaseBuild -OnlyFlavors $flavorList
}

if (-not (Test-Path $apkDir)) { throw "missing $apkDir" }

$aapt2 = Resolve-Aapt2
$targets = @()
foreach ($file in Get-ChildItem $apkDir -Filter '*.apk' | Sort-Object Name) {
    $target = Get-Target $file
    if ($target -and $target.Flavor -in $flavorList) { $targets += $target }
}
if ($targets.Count -eq 0) { throw "no aitv-*.apk found in $apkDir" }

if ($Only) {
    $wanted = @($Only.Split(',') | ForEach-Object { $_.Trim().ToLower() } | Where-Object { $_ })
    $targets = @($targets | Where-Object { $wanted -contains $_.AppKey })
    if ($targets.Count -eq 0) { throw "-Only matched no artifact: $Only" }
}

Write-Step 'Reading version info from APKs'
foreach ($target in $targets) {
    $info = Read-ApkInfo $target.File.FullName $aapt2
    $target | Add-Member -NotePropertyName VersionCode -NotePropertyValue $info.VersionCode
    $target | Add-Member -NotePropertyName VersionName -NotePropertyValue $info.VersionName
    $target | Add-Member -NotePropertyName Size -NotePropertyValue $target.File.Length
    Write-Host ("  {0,-34} {1} ({2})  {3:N0} bytes" -f $target.File.Name, $info.VersionName, $info.VersionCode, $target.File.Length)
}

$codes = @($targets | ForEach-Object { $_.VersionCode } | Sort-Object -Unique)
if ($codes.Count -gt 1) { throw "APKs carry different versionCodes: $($codes -join ', ')" }
$versionCode = $codes[0]
$versionName = ($targets | Select-Object -First 1).VersionName

$notes = $Notes
if ($NotesFile) {
    if (-not (Test-Path $NotesFile)) { throw "notes file not found: $NotesFile" }
    # Read as UTF-8 explicitly: Windows PowerShell 5.1 Get-Content defaults to ANSI for BOM-less files.
    $notes = [System.IO.File]::ReadAllText((Resolve-Path $NotesFile), [System.Text.Encoding]::UTF8)
}

if ($VerifyOnly) {
    Write-Step "Verifying $($targets.Count) artifact(s) against $appStoreUrl"
    $liveChecks = @()
    foreach ($target in $targets) {
        $liveChecks += Confirm-UpdateJson -AppKey $target.AppKey -VersionCode $target.VersionCode -ExpectedSize $target.Size
    }
    $liveChecks | Format-Table AppKey, Ok, VersionCode, Size, ExpectedSize, RemoteSize -AutoSize | Out-String | Write-Host
    if (@($liveChecks | Where-Object { -not $_.Ok }).Count -gt 0) {
        Write-Host 'Some appKeys do not match the local artifacts.' -ForegroundColor Red
        exit 1
    }
    Write-Host 'All appKeys match the local artifacts.' -ForegroundColor Green
    return
}

if ([string]::IsNullOrWhiteSpace($notes)) {
    Write-Warning 'updateContent is empty; the app will fall back to "New version x.y.z" in the dialog'
}

Write-Step "Publishing $($targets.Count) artifact(s): $versionName ($versionCode)"
if ($DryRun) {
    foreach ($target in $targets) { Write-Host "  [dry-run] $($target.AppKey) <- $($target.File.Name)" }
    Write-Host ''
    Write-Host 'Dry run only; nothing was uploaded.' -ForegroundColor Yellow
    return
}

$notesPath = New-NotesFile $notes
$published = @()
foreach ($target in $targets) {
    Write-Host "  uploading $($target.AppKey) ..." -NoNewline
    $result = Publish-Apk -Target $target -VersionCode $versionCode -VersionName $versionName -NotesPath $notesPath
    Write-Host ' ok' -ForegroundColor Green
    $published += [pscustomobject]@{ Target = $target; Result = $result }
}

Write-Step 'Verifying update.json'
$checks = @()
foreach ($item in $published) {
    $check = Confirm-UpdateJson -AppKey $item.Target.AppKey -VersionCode $versionCode -ExpectedSize $item.Target.Size
    $checks += $check
}

Write-Host ''
$checks | Format-Table AppKey, Ok, VersionCode, Size, ExpectedSize, RemoteSize -AutoSize | Out-String | Write-Host

Write-Host 'Downloads:'
foreach ($item in $published) {
    $url = $item.Result.release.downloadUrl
    if (-not $url) { $url = $item.Result.updateFile.url }
    if ($url -and $url.StartsWith('/')) { $url = "$BaseUrl$url" }
    Write-Host ("  {0,-30} {1}" -f $item.Target.AppKey, $url)
}
Write-Host 'update.json:'
foreach ($check in $checks) { Write-Host ("  {0,-30} {1}/{2}/update.json" -f $check.AppKey, $appStoreUrl, $check.AppKey) }

$failed = @($checks | Where-Object { -not $_.Ok })
if ($failed.Count -gt 0) {
    Write-Host ''
    Write-Host "FAILED: $($failed.Count) appKey(s) did not match after publish" -ForegroundColor Red
    exit 1
}
Write-Host ''
Write-Host "Done. $($checks.Count) app(s) now serve $versionName ($versionCode)." -ForegroundColor Green
exit 0
