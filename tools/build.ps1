#!/usr/bin/env pwsh
<#
.SYNOPSIS
  Build, sign and verify the DSH remote-hosts Android APK without Gradle.

.DESCRIPTION
  Uses the portable toolchain provisioned by `node tools/setup-toolchain.mjs`:
  Temurin JDK 17 + Android SDK platform 35 + build-tools 35 + JSch.
  Pipeline: aapt2 compile -> aapt2 link -> javac -> jar -> d8 -> package ->
  zipalign -> apksigner (then apksigner verify + zipalign -c self-check).
  No AndroidX, no Gradle, no network access at build time.

  The build is reproducible: zip entry timestamps are pinned and signing uses
  the deterministic v2/v3 schemes, so the same sources produce the same sha256.

  Requires Windows PowerShell 5.1 (run with -ExecutionPolicy Bypass):
    powershell -NoProfile -ExecutionPolicy Bypass -File tools/build.ps1

  The output is idempotent: every run rebuilds build/ from scratch and writes
  dist/dsh-remote-hosts-<version>.apk for the requested version.

.OUTPUTS
  dist/dsh-remote-hosts-<version>.apk (signed, zipaligned)
  dist/dsh-remote-hosts-<version>-unsigned.apk (only with -SkipSign)

.PARAMETER VersionName
  Override android:versionName (default: taken from app/AndroidManifest.xml).

.PARAMETER VersionCode
  Override android:versionCode (default: taken from app/AndroidManifest.xml).

.PARAMETER SkipSign
  Build an unsigned (but zipaligned) APK. Useful to inspect a build without the
  project keystore; not installable as an upgrade.

.PARAMETER Clean
  Also delete previous build outputs (dist/*.apk + dist/*.idsig) before building.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File tools/build.ps1

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File tools/build.ps1 -VersionName 0.2.0 -VersionCode 2 -Clean
#>
param(
    [string]$VersionName = '',
    [int]$VersionCode = 0,
    [switch]$SkipSign,
    [switch]$Clean
)

$ErrorActionPreference = 'Stop'
$startedAt = Get-Date

$root = Split-Path $PSScriptRoot -Parent
$toolchainFile = Join-Path $root '.toolchain/toolchain.json'
$manifestPath = Join-Path $root 'app/AndroidManifest.xml'
$resDir = Join-Path $root 'app/res'
$srcDir = Join-Path $root 'app/src'
$buildRoot = Join-Path $root 'build'
$dist = Join-Path $root 'dist'
$keystoreDir = Join-Path $root 'keystore'
$keystore = Join-Path $keystoreDir 'release.jks'
# The signing-key password is NEVER stored in the repository: read it from the
# environment so a public checkout cannot leak the release signing identity.
#   PowerShell:  $env:DSH_KEYSTORE_PASSWORD='...'
# Unsigned builds (-SkipSign) do not need it.
$keystorePassword = $env:DSH_KEYSTORE_PASSWORD
$keyAlias = 'dshremote'

# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------
function Write-Step([string]$message) {
    Write-Host ''
    Write-Host "==> $message" -ForegroundColor Cyan
}

function Fail([string]$message) {
    Write-Host ''
    Write-Host "BUILD FAILED: $message" -ForegroundColor Red
    exit 1
}

function Invoke-Native([string]$exe, [string[]]$nativeArgs, [string]$label) {
    Write-Host "==> $label"
    & $exe @nativeArgs
    if ($LASTEXITCODE -ne 0) {
        Fail "$label failed (exit $LASTEXITCODE). Tool: $exe"
    }
}

# PS 5.1 turns redirected native stderr into ErrorRecords; with
# $ErrorActionPreference = 'Stop' that throws "NativeCommandError" even when the
# tool only printed a warning. Capture with a temporarily relaxed EAP.
function Invoke-Capture([string]$exe, [string[]]$arguments) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $raw = & $exe @arguments 2>&1
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    $lines = @()
    foreach ($item in @($raw)) { $lines += "$item" }
    return @{ Code = $code; Output = $lines }
}

function Write-ToolOutput($captured) {
    foreach ($line in $captured.Output) { Write-Host "    $line" }
}

function Write-ToolWarnings($captured) {
    foreach ($line in $captured.Output) {
        if ($line -match '(?i)\bwarning') { Write-Host "    $line" -ForegroundColor Yellow }
    }
}

# ---------------------------------------------------------------------------
# precondition checks (clear errors instead of cryptic tool failures)
# ---------------------------------------------------------------------------
if (-not (Test-Path $toolchainFile)) {
    Fail "toolchain manifest missing: $toolchainFile`n       run: node tools/setup-toolchain.mjs"
}
$tc = Get-Content $toolchainFile -Raw | ConvertFrom-Json
$requiredTools = @(
    @{ Name = 'java'; Path = $tc.java },
    @{ Name = 'javac'; Path = $tc.javac },
    @{ Name = 'jar'; Path = $tc.jar },
    @{ Name = 'keytool'; Path = $tc.keytool },
    @{ Name = 'android.jar'; Path = $tc.androidJar },
    @{ Name = 'aapt2'; Path = $tc.aapt2 },
    @{ Name = 'zipalign'; Path = $tc.zipalign },
    @{ Name = 'd8.jar'; Path = $tc.d8Jar },
    @{ Name = 'apksigner.jar'; Path = $tc.apksignerJar },
    @{ Name = 'jsch.jar'; Path = $tc.jschJar }
)
foreach ($tool in $requiredTools) {
    if (-not (Test-Path $tool.Path)) {
        Fail "toolchain component '$($tool.Name)' missing: $($tool.Path)`n       run: node tools/setup-toolchain.mjs"
    }
}
foreach ($input in @(@{ Name = 'AndroidManifest.xml'; Path = $manifestPath },
                     @{ Name = 'app/res'; Path = $resDir },
                     @{ Name = 'app/src'; Path = $srcDir })) {
    if (-not (Test-Path $input.Path)) {
        Fail "required input missing: $($input.Name) ($($input.Path))"
    }
}

function Read-Manifest-Value([string]$attribute) {
    $text = Get-Content $manifestPath -Raw
    $match = [regex]::Match($text, "$attribute=`"([^`"]+)`"")
    if (-not $match.Success) { Fail "manifest attribute $attribute not found in $manifestPath" }
    return $match.Groups[1].Value
}

$manifestVersionName = Read-Manifest-Value 'android:versionName'
$manifestVersionCode = [int](Read-Manifest-Value 'android:versionCode')
if ($VersionName -eq '') { $VersionName = $manifestVersionName }
if ($VersionCode -eq 0) { $VersionCode = $manifestVersionCode }
if ($VersionCode -lt 1 -or $VersionCode -gt 2100000000) {
    Fail "invalid -VersionCode $VersionCode (must be 1..2100000000)"
}
if ($VersionName -match '[\\/:*?"<>|]') {
    Fail "invalid -VersionName '$VersionName' (must not contain path characters)"
}

Write-Step 'toolchain'
Write-Host "  java     : $($tc.java)"
Write-Host "  android  : $($tc.androidJar)"
$javaVersionResult = Invoke-Capture $tc.java @('-version')
if ($javaVersionResult.Output.Count -gt 0) { Write-Host "  version  : $($javaVersionResult.Output[0])" }

# ---------------------------------------------------------------------------
# clean
#
# build/ and dist/ are shared between teammates and a previous build may still
# be running (or leave a file locked). Each run therefore builds into its own
# stage directory instead of deleting build/ itself, so concurrent/abandoned
# extractions can never block a build. Stale stages are removed best-effort.
# ---------------------------------------------------------------------------
Write-Step 'prepare build staging directory'
if (-not (Test-Path $buildRoot)) {
    New-Item -ItemType Directory -Force -Path $buildRoot | Out-Null
}
foreach ($stale in @(Get-ChildItem $buildRoot -Directory -Filter 'stage-*' -ErrorAction SilentlyContinue)) {
    Remove-Item -Recurse -Force $stale.FullName -ErrorAction SilentlyContinue
}
# Legacy layout (fixed paths under build/) from earlier versions: best effort.
foreach ($legacy in @('gen', 'classes', 'dex', 'flat')) {
    Remove-Item -Recurse -Force (Join-Path $buildRoot $legacy) -ErrorAction SilentlyContinue
}
foreach ($legacyFile in @('base.apk', 'classes.jar', 'javac.args', 'app-unsigned.apk', 'app-aligned.apk')) {
    Remove-Item -Force (Join-Path $buildRoot $legacyFile) -ErrorAction SilentlyContinue
}
$build = Join-Path $buildRoot ('stage-' + $PID)
if (Test-Path $build) {
    Remove-Item -Recurse -Force $build -ErrorAction SilentlyContinue
}
if ($Clean -and (Test-Path $dist)) {
    Write-Host '  removing previous dist outputs (-Clean)'
    Remove-Item -Force (Join-Path $dist '*.apk') -ErrorAction SilentlyContinue
    Remove-Item -Force (Join-Path $dist '*.apk.idsig') -ErrorAction SilentlyContinue
}
New-Item -ItemType Directory -Force -Path $build, $dist, `
    (Join-Path $build 'gen'), (Join-Path $build 'classes'), (Join-Path $build 'dex'), (Join-Path $build 'flat') | Out-Null
Write-Host "  staging : $build"

# ---------------------------------------------------------------------------
# signing key
# ---------------------------------------------------------------------------
if (-not $SkipSign) {
    if ([string]::IsNullOrEmpty($keystorePassword)) {
        Fail "release keystore password is not set.`n       set the DSH_KEYSTORE_PASSWORD environment variable before a signed build, e.g.:`n       `$env:DSH_KEYSTORE_PASSWORD='<your-keystore-password>'`n       (the password is never committed to the repository; -SkipSign does not need it)"
    }
    if (-not (Test-Path $keystore)) {
        New-Item -ItemType Directory -Force -Path $keystoreDir | Out-Null
        Write-Step 'generate signing key (keystore/release.jks)'
        Write-Host '  NOTE: keep this file; Android only upgrades an app signed with the same key.'
        & $tc.keytool -genkeypair -noprompt `
            -keystore $keystore -storepass $keystorePassword -keypass $keystorePassword `
            -alias $keyAlias -keyalg RSA -keysize 2048 -validity 10000 `
            -dname 'CN=DSH Remote Hosts, OU=Personal, O=DSH, C=CN'
        if ($LASTEXITCODE -ne 0) { Fail 'keytool failed to create keystore/release.jks' }
    }
}

# ---------------------------------------------------------------------------
# resources
# ---------------------------------------------------------------------------
Write-Step 'aapt2 compile'
$flatDir = Join-Path $build 'flat'
$compileResult = Invoke-Capture $tc.aapt2 @('compile', '--dir', $resDir, '-o', $flatDir)
if ($compileResult.Code -ne 0) {
    Write-ToolOutput $compileResult
    Fail "aapt2 compile failed (exit $($compileResult.Code)); resource syntax errors belong to app/res/** (ui-app)"
}
Write-ToolWarnings $compileResult
$flatFiles = @(Get-ChildItem $flatDir -Filter '*.flat' -Recurse | ForEach-Object { $_.FullName })
if ($flatFiles.Count -eq 0) { Fail "aapt2 compile produced no .flat files from $resDir" }
Write-Host "  $($flatFiles.Count) compiled resource file(s)"

Write-Step 'aapt2 link'
$linkArgs = @(
    'link', '-o', (Join-Path $build 'base.apk'),
    '-I', $tc.androidJar,
    '--manifest', $manifestPath,
    '--java', (Join-Path $build 'gen'),
    '--auto-add-overlay',
    '--min-sdk-version', '26',
    '--target-sdk-version', '35',
    '--version-code', "$VersionCode",
    '--version-name', "$VersionName"
) + $flatFiles
$linkResult = Invoke-Capture $tc.aapt2 $linkArgs
if ($linkResult.Code -ne 0) {
    Write-ToolOutput $linkResult
    Fail "aapt2 link failed (exit $($linkResult.Code)); manifest/resource references belong to app/ (ui-app/core-engine)"
}
Write-ToolWarnings $linkResult

# ---------------------------------------------------------------------------
# java
# ---------------------------------------------------------------------------
Write-Step 'javac (source/target 8, bootclasspath android.jar)'
$sources = @()
$sources += @(Get-ChildItem $srcDir -Filter '*.java' -Recurse | ForEach-Object { $_.FullName })
$sources += @(Get-ChildItem (Join-Path $build 'gen') -Filter '*.java' -Recurse | ForEach-Object { $_.FullName })
if ($sources.Count -eq 0) { Fail "no Java sources found under $srcDir" }
Write-Host "  $($sources.Count) Java source file(s)"
$argFile = Join-Path $build 'javac.args'
$quoted = $sources | ForEach-Object { '"' + ($_ -replace '\\', '\\') + '"' }
# Windows PowerShell 5.1's utf8 writer emits a BOM, which javac would read as
# part of the first argument. Write BOM-less UTF-8 explicitly.
[System.IO.File]::WriteAllLines($argFile, [string[]]$quoted, (New-Object System.Text.UTF8Encoding($false)))
$classesDir = Join-Path $build 'classes'
$javacResult = Invoke-Capture $tc.javac @(
    '-J-Duser.language=en', '-J-Duser.country=US',
    '-encoding', 'UTF-8',
    '-source', '8', '-target', '8',
    '-bootclasspath', $tc.androidJar,
    '-classpath', $tc.jschJar,
    '-d', $classesDir,
    "@$argFile",
    '-Xlint:-options'
)
if ($javacResult.Code -ne 0) {
    Write-ToolOutput $javacResult
    Fail "javac failed (exit $($javacResult.Code)). Fix the file:line errors above (core/app sources), then re-run."
}
Write-ToolWarnings $javacResult

# ---------------------------------------------------------------------------
# dex
# ---------------------------------------------------------------------------
# d8 in build-tools 35 accepts class files / jars, not a directory tree.
Write-Step 'jar + d8'
$classesJar = Join-Path $build 'classes.jar'
Invoke-Native $tc.jar @('--create', '--file', $classesJar, '-C', $classesDir, '.') 'jar classes'
$dexDir = Join-Path $build 'dex'
$d8Result = Invoke-Capture $tc.java @(
    '-cp', $tc.d8Jar,
    'com.android.tools.r8.D8',
    '--release',
    '--min-api', '26',
    '--lib', $tc.androidJar,
    '--output', $dexDir,
    $classesJar,
    $tc.jschJar
)
if ($d8Result.Code -ne 0) {
    Write-ToolOutput $d8Result
    Fail "d8 failed (exit $($d8Result.Code)); usually an Android-API or multi-dex issue in app/src"
}
Write-ToolWarnings $d8Result
$dexFile = Join-Path $dexDir 'classes.dex'
if (-not (Test-Path $dexFile)) { Fail "d8 produced no classes.dex in $dexDir" }

# ---------------------------------------------------------------------------
# package + align + sign
# ---------------------------------------------------------------------------
$unsigned = Join-Path $build 'app-unsigned.apk'

# Append classes.dex without repacking: Jar/Zip tools that rewrite the archive
# would recompress resources.arsc, which Android refuses for targetSdk >= 30.
#
# Every entry timestamp is then pinned to a fixed instant. aapt2/d8 outputs carry
# "now" timestamps, which would make the APK hash differ on every build; pinning
# them makes the signed artifact byte-for-byte reproducible (same sources ->
# same sha256), which is what the docs and the acceptance report quote.
function Add-DexToApk([string]$baseApk, [string]$dexFile, [string]$outApk) {
    Copy-Item $baseApk $outApk -Force
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::Open($outApk, [System.IO.Compression.ZipArchiveMode]::Update)
    try {
        $entry = $zip.CreateEntry('classes.dex', [System.IO.Compression.CompressionLevel]::NoCompression)
        $stream = $entry.Open()
        try {
            $bytes = [System.IO.File]::ReadAllBytes($dexFile)
            $stream.Write($bytes, 0, $bytes.Length)
        } finally {
            $stream.Dispose()
        }
        # 2000-01-01T00:00:00Z is inside the ZIP date range (>= 1980) and stable.
        $fixedTime = [System.DateTimeOffset]::Parse('2000-01-01T00:00:00Z')
        foreach ($zipEntry in @($zip.Entries)) {
            $zipEntry.LastWriteTime = $fixedTime
        }
    } finally {
        $zip.Dispose()
    }
}
Write-Step 'package APK'
Add-DexToApk (Join-Path $build 'base.apk') $dexFile $unsigned

Write-Step 'zipalign'
$aligned = Join-Path $build 'app-aligned.apk'
Invoke-Native $tc.zipalign @('-f', '-p', '4', $unsigned, $aligned) 'zipalign -p 4'

if ($SkipSign) {
    $unsignedApk = Join-Path $dist "dsh-remote-hosts-$VersionName-unsigned.apk"
    Copy-Item $aligned $unsignedApk -Force
    Write-Step 'unsigned APK (skipped signing)'
    Write-Host "  APK: $unsignedApk"
    Write-Host "  size: $([math]::Round((Get-Item $unsignedApk).Length / 1MB, 2)) MB"
    Write-Host "  sha256: $((Get-FileHash -Path $unsignedApk -Algorithm SHA256).Hash.ToLowerInvariant())"
    Write-Host ''
    Write-Host 'BUILD OK (unsigned)' -ForegroundColor Green
    exit 0
}

$finalApk = Join-Path $dist "dsh-remote-hosts-$VersionName.apk"
if (Test-Path $finalApk) { Remove-Item -Force $finalApk }
if (Test-Path "$finalApk.idsig") { Remove-Item -Force "$finalApk.idsig" }
Write-Step 'apksigner sign'
# v2/v3 only: minSdk 26 supports APK Signature Scheme v2, v1 (JAR) is unnecessary
# and its META-INF entries would carry fresh timestamps. v4 only produces a
# .idsig sidecar used by `adb install --incremental`, not needed for sideloading,
# so it is disabled to keep dist/ to exactly one file.
Invoke-Native $tc.java @(
    '-jar', $tc.apksignerJar,
    'sign',
    '--ks', $keystore,
    '--ks-key-alias', $keyAlias,
    '--ks-pass', "pass:$keystorePassword",
    '--key-pass', "pass:$keystorePassword",
    '--v1-signing-enabled', 'false',
    '--v2-signing-enabled', 'true',
    '--v3-signing-enabled', 'true',
    '--v4-signing-enabled', 'false',
    '--out', $finalApk,
    $aligned
) 'apksigner sign'

# ---------------------------------------------------------------------------
# self-check (docs/app-spec.md §9: signed APK verifies and is zipaligned)
# ---------------------------------------------------------------------------
Write-Step 'verify signature (apksigner verify)'
$verifyResult = Invoke-Capture $tc.java @('-jar', $tc.apksignerJar, 'verify', '--verbose', $finalApk)
if ($verifyResult.Code -ne 0) {
    Write-ToolOutput $verifyResult
    Fail "apksigner verify failed (exit $($verifyResult.Code))"
}
Write-Host '  verified'

Write-Step 'verify alignment (zipalign -c 4)'
$alignResult = Invoke-Capture $tc.zipalign @('-c', '-v', '4', $finalApk)
if ($alignResult.Code -ne 0) {
    Write-ToolOutput $alignResult
    Fail "zipalign -c 4 failed (exit $($alignResult.Code)): APK is not 4-byte aligned"
}
Write-Host '  aligned'

# ---------------------------------------------------------------------------
# artifact summary
# ---------------------------------------------------------------------------
$apkInfo = Get-Item $finalApk
$sha256 = (Get-FileHash -Path $finalApk -Algorithm SHA256).Hash.ToLowerInvariant()
$badgeResult = Invoke-Capture $tc.aapt2 @('dump', 'badging', $finalApk)
$packageLine = ''
foreach ($line in $badgeResult.Output) {
    if ($line -match '^package:') { $packageLine = $line; break }
}
$elapsed = [math]::Round(((Get-Date) - $startedAt).TotalSeconds, 1)

Write-Step 'APK'
Write-Host "  path      : $($apkInfo.FullName)"
Write-Host "  size      : $($apkInfo.Length) bytes ($([math]::Round($apkInfo.Length / 1MB, 2)) MB)"
Write-Host "  sha256    : $sha256"
Write-Host "  version   : $VersionName (versionCode $VersionCode)"
if ($packageLine) { Write-Host "  package   : $packageLine" }
Write-Host "  keystore  : $keystore (alias $keyAlias)"
Write-Host "  staging   : $build"
Write-Host "  elapsed   : $elapsed s"
Write-Host ''
Write-Host 'BUILD OK' -ForegroundColor Green
exit 0
