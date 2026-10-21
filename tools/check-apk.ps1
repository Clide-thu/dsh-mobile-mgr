#!/usr/bin/env pwsh
<#
.SYNOPSIS
  Static acceptance checks for the DSH remote-hosts APK (no device needed).

.DESCRIPTION
  Runs the checks from docs/app-spec.md §10 that are possible without an Android
  device or emulator:

    * signature        apksigner verify (exit code + v2/v3 scheme + signer cert)
    * alignment        zipalign -c -v 4
    * package identity aapt2 dump badging (package, versionName/Code, min/target SDK,
                       permissions, launchable activity, application label);
                       versionName/versionCode must match the release being accepted
    * manifest         aapt2 dump xmltree: service/activities declared (v0.2.5:
                       MainActivity, EditHostActivity, ImportConfigActivity,
                       SshKeysActivity, SettingsActivity, WebViewActivity), service
                       exported=false + foregroundServiceType=dataSync,
                       allowBackup=false, no debuggable flags, no unexpected
                       exported components
    * archive         classes.dex present and valid, resources.arsc stored
                       uncompressed, no toolchain/source/test/keystore leakage
    * secrets         raw APK byte scan: no test token/fixture, private-key PEM
                       material, JSch PEM constant false-positives excluded, or
                       real lab host values from the test environment

  Exit codes: 0 = every check passed, 1 = at least one check failed,
  2 = APK/toolchain not found.

.PARAMETER Apk
  APK to check. Defaults to the newest dist/*.apk.

.PARAMETER ExpectVersionName
  Expected android:versionName for the release being accepted (default 0.2.5).

.PARAMETER ExpectVersionCode
  Expected android:versionCode for the release being accepted (default 7).

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File tools/check-apk.ps1

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File tools/check-apk.ps1 -Apk dist/dsh-remote-hosts-0.2.5.apk
#>
param(
    [string]$Apk = '',
    [string]$ExpectVersionName = '0.2.5',
    [int]$ExpectVersionCode = 7
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$toolchainFile = Join-Path $root '.toolchain/toolchain.json'
$dist = Join-Path $root 'dist'
$manifestPath = Join-Path $root 'app/AndroidManifest.xml'

$script:passCount = 0
$script:failCount = 0
$script:failures = New-Object System.Collections.ArrayList

function Write-Section([string]$title) {
    Write-Host ''
    Write-Host "==> $title" -ForegroundColor Cyan
}

function Write-Info([string]$message) {
    Write-Host "  $message"
}

function Test-Check([string]$name, [bool]$ok, [string]$detail) {
    if ($ok) {
        $script:passCount += 1
        Write-Host "  [PASS] $name" -ForegroundColor Green
        return
    }
    $script:failCount += 1
    $entry = $name
    if ($detail) { $entry = "$name - $detail" }
    [void]$script:failures.Add($entry)
    Write-Host "  [FAIL] $name  $detail" -ForegroundColor Red
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

# --- toolchain -------------------------------------------------------------
if (-not (Test-Path $toolchainFile)) {
    Write-Host "ERROR: toolchain missing: $toolchainFile" -ForegroundColor Red
    Write-Host '  run: node tools/setup-toolchain.mjs'
    exit 2
}
$tc = Get-Content $toolchainFile -Raw | ConvertFrom-Json
$java = $tc.java
$apksignerJar = $tc.apksignerJar
$aapt2 = $tc.aapt2
$zipalign = $tc.zipalign
foreach ($required in @($java, $apksignerJar, $aapt2, $zipalign)) {
    if (-not (Test-Path $required)) {
        Write-Host "ERROR: toolchain entry missing: $required" -ForegroundColor Red
        Write-Host '  run: node tools/setup-toolchain.mjs'
        exit 2
    }
}

# --- locate the APK --------------------------------------------------------
if ($Apk -ne '') {
    $apkPath = $Apk
    if (-not [System.IO.Path]::IsPathRooted($apkPath)) {
        $apkPath = Join-Path $root $apkPath
    }
} else {
    if (-not (Test-Path $dist)) {
        Write-Host 'ERROR: dist/ does not exist; build first: tools/build.ps1' -ForegroundColor Red
        exit 2
    }
    $candidate = Get-ChildItem $dist -Filter '*.apk' -File | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($candidate -eq $null) {
        Write-Host 'ERROR: no APK in dist/; build first: tools/build.ps1' -ForegroundColor Red
        exit 2
    }
    $apkPath = $candidate.FullName
}
if (-not (Test-Path $apkPath)) {
    Write-Host "ERROR: APK not found: $apkPath" -ForegroundColor Red
    exit 2
}
$apkPath = (Resolve-Path $apkPath).Path
$apkInfo = Get-Item $apkPath
$apkName = $apkInfo.Name

Write-Host ''
Write-Host 'DSH remote-hosts APK static acceptance check' -ForegroundColor White
Write-Info "apk    : $apkPath"
Write-Info "size   : $($apkInfo.Length) bytes ($([math]::Round($apkInfo.Length / 1MB, 2)) MB)"

$sha256 = (Get-FileHash -Path $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
Write-Info "sha256 : $sha256"

# Version name encoded in the artifact name: dsh-remote-hosts-<version>.apk
$fileVersionName = ''
if ($apkName -match '^dsh-remote-hosts-(.+)\.apk$') {
    $fileVersionName = $Matches[1]
}

# --- 1. signature ----------------------------------------------------------
Write-Section 'signature (apksigner verify)'
$signResult = Invoke-Capture $java @('-jar', $apksignerJar, 'verify', '--verbose', '--print-certs', $apkPath)
$signText = $signResult.Output -join "`n"
if ($signResult.Code -ne 0) {
    foreach ($line in $signResult.Output) { Write-Host "    $line" -ForegroundColor DarkGray }
}
Test-Check 'apksigner verify exits 0' ($signResult.Code -eq 0) "exit=$($signResult.Code)"
Test-Check 'apksigner reports the APK as verified' ($signText -match '(?m)^Verifies\s*$') 'missing "Verifies" line'
$v2 = $signText -match 'Verified using v2 scheme \(APK Signature Scheme v2\): true'
$v3 = $signText -match 'Verified using v3 scheme \(APK Signature Scheme v3\): true'
Test-Check 'APK Signature Scheme v2 or v3 present' ($v2 -or $v3) 'neither v2 nor v3 verified'
if ($signText -match 'Signer #1 certificate DN: (.+)') { Write-Info "signer DN : $($Matches[1].Trim())" }
if ($signText -match 'Signer #1 certificate SHA-256 digest: ([0-9a-f]+)') {
    Write-Info "cert sha256: $($Matches[1])"
}

# --- 2. zipalign -----------------------------------------------------------
Write-Section 'alignment (zipalign -c -v 4)'
$alignResult = Invoke-Capture $zipalign @('-c', '-v', '4', $apkPath)
$alignText = $alignResult.Output -join "`n"
Test-Check 'zipalign -c 4 exits 0' ($alignResult.Code -eq 0) "exit=$($alignResult.Code)"
Test-Check 'zipalign reports no misaligned entry' ($alignText -notmatch '\(BAD|FAILED|NOT ALIGNED') 'alignment errors reported'
$entryCount = 0
foreach ($line in $alignResult.Output) { if ($line -match '^\s*\d+\s+') { $entryCount += 1 } }
Write-Info "aligned entries: $entryCount"

# --- 3. badging / package identity ----------------------------------------
Write-Section 'package identity (aapt2 dump badging)'
$badgeResult = Invoke-Capture $aapt2 @('dump', 'badging', $apkPath)
$badgeText = $badgeResult.Output -join "`n"
Test-Check 'aapt2 dump badging exits 0' ($badgeResult.Code -eq 0) "exit=$($badgeResult.Code)"

$packageName = ''
$versionCode = ''
$versionName = ''
if ($badgeText -match "package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'") {
    $packageName = $Matches[1]
    $versionCode = $Matches[2]
    $versionName = $Matches[3]
}
$minSdk = ''
$targetSdk = ''
if ($badgeText -match "minSdkVersion:'(\d+)'") { $minSdk = $Matches[1] }
if ($badgeText -match "targetSdkVersion:'(\d+)'") { $targetSdk = $Matches[1] }

Test-Check 'package name is dev.dsh.remotehosts' ($packageName -eq 'dev.dsh.remotehosts') "got '$packageName'"
Test-Check 'versionCode is a positive integer' ($versionCode -match '^\d+$' -and [int]$versionCode -gt 0) "got '$versionCode'"
Test-Check "versionCode matches release $ExpectVersionCode" ($versionCode -eq "$ExpectVersionCode") "got '$versionCode'"
Test-Check 'versionName is present' ($versionName -ne '') "got '$versionName'"
Test-Check "versionName matches release '$ExpectVersionName'" ($versionName -eq $ExpectVersionName) "got '$versionName'"
if ($fileVersionName -ne '') {
    Test-Check 'artifact file name matches versionName' ($versionName -eq $fileVersionName) "file=$fileVersionName apk=$versionName"
}
Test-Check 'minSdkVersion is 26 (Android 8.0)' ($minSdk -eq '26') "got '$minSdk'"
Test-Check 'targetSdkVersion is 35' ($targetSdk -eq '35') "got '$targetSdk'"
Test-Check 'launchable activity is MainActivity' `
    ($badgeText -match "launchable-activity: name='dev\.dsh\.remotehosts\.ui\.MainActivity'") 'launcher activity mismatch'
Test-Check 'application label is non-empty' ($badgeText -match "application-label:'[^']+'") 'no application-label'

$permissions = @()
foreach ($line in $badgeResult.Output) {
    if ($line -match "uses-permission: name='([^']+)'") { $permissions += $Matches[1] }
}
Write-Info "permissions: $($permissions -join ', ')"
$requiredPermissions = @(
    'android.permission.INTERNET',
    'android.permission.ACCESS_NETWORK_STATE',
    'android.permission.FOREGROUND_SERVICE',
    'android.permission.FOREGROUND_SERVICE_DATA_SYNC',
    'android.permission.POST_NOTIFICATIONS'
)
foreach ($permission in $requiredPermissions) {
    Test-Check "permission declared: $permission" ($permissions -contains $permission) 'missing from APK'
}

# --- 4. manifest tree ------------------------------------------------------
Write-Section 'manifest declarations (aapt2 dump xmltree)'
$treeResult = Invoke-Capture $aapt2 @('dump', 'xmltree', '--file', 'AndroidManifest.xml', $apkPath)
$treeLines = @($treeResult.Output)
Test-Check 'aapt2 dump xmltree exits 0' ($treeResult.Code -eq 0) "exit=$($treeResult.Code)"

$blocks = New-Object System.Collections.ArrayList
for ($i = 0; $i -lt $treeLines.Count; $i++) {
    $line = $treeLines[$i]
    if ($line -match '^\s*E: ') {
        $tag = ($line -replace '^\s*E: ', '')
        $tag = ($tag -split '[ (]')[0]
        $textLines = New-Object System.Collections.ArrayList
        [void]$textLines.Add($line)
        for ($j = $i + 1; $j -lt $treeLines.Count; $j++) {
            if ($treeLines[$j] -match '^\s*E: ') { break }
            [void]$textLines.Add($treeLines[$j])
        }
        [void]$blocks.Add([pscustomobject]@{ Tag = $tag; Text = ($textLines -join "`n") })
    }
}
Test-Check 'manifest tree has elements' ($blocks.Count -gt 0) "blocks=$($blocks.Count)"

$applicationBlock = $blocks | Where-Object { $_.Tag -eq 'application' } | Select-Object -First 1
Test-Check 'application element present' ($applicationBlock -ne $null) 'missing application element'
if ($applicationBlock -ne $null) {
    $appText = $applicationBlock.Text
    Test-Check 'allowBackup=false' ($appText -match ':allowBackup\(0x[0-9A-Fa-f]+\)=false') 'allowBackup not false'
    Test-Check 'debuggable is not enabled' ($appText -notmatch ':debuggable\(0x[0-9A-Fa-f]+\)=true') 'debuggable=true found'
    Test-Check 'usesCleartextTraffic=true (loopback HTTP readiness probe)' `
        ($appText -match ':usesCleartextTraffic\(0x[0-9A-Fa-f]+\)=true') 'cleartext traffic not enabled'
    Test-Check 'networkSecurityConfig is declared' ($appText -match ':networkSecurityConfig\(0x[0-9A-Fa-f]+\)=') 'missing networkSecurityConfig'
}

$serviceBlock = $blocks | Where-Object {
    $_.Tag -eq 'service' -and $_.Text -match 'TunnelService'
} | Select-Object -First 1
Test-Check 'TunnelService service is declared' ($serviceBlock -ne $null) 'missing .core.TunnelService'
if ($serviceBlock -ne $null) {
    $serviceText = $serviceBlock.Text
    Test-Check 'TunnelService exported=false' ($serviceText -match ':exported\(0x[0-9A-Fa-f]+\)=false') 'service exported != false'
    $fgsValue = -1
    if ($serviceText -match ':foregroundServiceType\(0x[0-9A-Fa-f]+\)=(0x[0-9A-Fa-f]+|\d+)') {
        $rawFgs = $Matches[1]
        if ($rawFgs -match '^0[xX]') {
            $fgsValue = [Convert]::ToInt64($rawFgs.Substring(2), 16)
        } else {
            $fgsValue = [Convert]::ToInt64($rawFgs, 10)
        }
    }
    Test-Check 'TunnelService foregroundServiceType=dataSync(1)' ($fgsValue -eq 1) "got '$fgsValue'"
}

$activityNames = @('MainActivity', 'EditHostActivity', 'ImportConfigActivity', 'SshKeysActivity', 'SettingsActivity', 'WebViewActivity')
foreach ($activityName in $activityNames) {
    $activityBlock = $blocks | Where-Object {
        $_.Tag -eq 'activity' -and $_.Text -match $activityName
    } | Select-Object -First 1
    Test-Check "activity declared: $activityName" ($activityBlock -ne $null) 'missing from manifest'
    if ($activityBlock -ne $null -and $activityName -eq 'MainActivity') {
        Test-Check 'MainActivity exported=true (launcher)' `
            ($activityBlock.Text -match ':exported\(0x[0-9A-Fa-f]+\)=true') 'launcher activity must be exported'
    }
    if ($activityBlock -ne $null -and $activityName -ne 'MainActivity') {
        Test-Check "$activityName exported=false" `
            ($activityBlock.Text -match ':exported\(0x[0-9A-Fa-f]+\)=false') 'internal activity exported != false'
    }
}

$exportedComponents = @()
foreach ($block in $blocks) {
    if (@('activity', 'service', 'receiver', 'provider') -notcontains $block.Tag) { continue }
    if ($block.Text -notmatch ':exported\(0x[0-9A-Fa-f]+\)=true') { continue }
    if ($block.Text -match 'MainActivity') { continue }
    $exportedComponents += $block.Tag
}
Test-Check 'no unexpected exported components' ($exportedComponents.Count -eq 0) `
    "exported: $($exportedComponents -join ', ')"

# --- 4b. v0.2.5 feature wiring ---------------------------------------------
# v0.2.5 replaces the per-host appendToken switch with global settings and adds
# a collapsible app bar to the built-in WebView page. The settings entry points
# and the collapse resources must be compiled into the APK. The old per-host
# switch resources must be gone from the compiled resource table: their absence
# is the regression guard that the per-host switch was not reintroduced.
Write-Section 'v0.2.5 feature wiring (settings / WebView collapse)'
$resourcesResult = Invoke-Capture $aapt2 @('dump', 'resources', $apkPath)
$resourcesText = $resourcesResult.Output -join "`n"
Test-Check 'aapt2 dump resources exits 0' ($resourcesResult.Code -eq 0) "exit=$($resourcesResult.Code)"

$featureResources = @(
    # Settings page + main-menu entry.
    'string/settings_title',
    'string/menu_settings',
    'string/settings_browser_mode_label',
    'string/settings_collapse_label',
    'string/settings_browser_mode_help_message',
    'string/settings_collapse_help_message',
    'string/open_mode_settings_hint',
    'string/webview_collapse',
    'id/switch_browser_mode',
    'id/switch_collapse_app_bar',
    'id/appbar_collapse',
    'id/text_open_mode_hint',
    # Built-in WebView (v0.2.4) resources retained by v0.2.5.
    'string/webview_title',
    'string/webview_error_title',
    'string/webview_retry',
    'string/webview_edit_credential'
)
foreach ($featureResource in $featureResources) {
    Test-Check "resource present: $featureResource" ($resourcesText -match [regex]::Escape($featureResource)) `
        'missing from the compiled resource table'
}

$removedResources = @(
    'string/append_token_label',
    'string/append_token_hint',
    'string/append_token_help_title',
    'string/append_token_warn_title',
    'id/check_append_token'
)
foreach ($removedResource in $removedResources) {
    Test-Check "resource removed: $removedResource" ($resourcesText -notmatch [regex]::Escape($removedResource)) `
        'still present in the compiled resource table'
}

# --- 5. archive contents ---------------------------------------------------
Write-Section 'archive contents'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($apkPath)
try {
    $entries = @($zip.Entries)
    Write-Info "zip entries: $($entries.Count)"
    Test-Check 'AndroidManifest.xml present in archive' (($entries | Where-Object { $_.FullName -eq 'AndroidManifest.xml' }).Count -eq 1) 'missing AndroidManifest.xml'

    $dexEntry = $entries | Where-Object { $_.FullName -eq 'classes.dex' } | Select-Object -First 1
    Test-Check 'classes.dex present in archive' ($dexEntry -ne $null) 'missing classes.dex'
    if ($dexEntry -ne $null) {
        Test-Check 'classes.dex is non-empty' ($dexEntry.Length -gt 0) "length=$($dexEntry.Length)"
        $dexStream = $dexEntry.Open()
        $header = New-Object byte[] 8
        $read = 0
        try {
            $read = $dexStream.Read($header, 0, 8)
        } finally {
            $dexStream.Dispose()
        }
        $dexMagic = [System.Text.Encoding]::ASCII.GetString($header, 0, $read)
        Test-Check 'classes.dex magic is dex\n' ($dexMagic.StartsWith("dex`n")) "magic='$dexMagic'"
        if ($read -ge 8) {
            $dexVersion = [System.Text.Encoding]::ASCII.GetString($header, 4, 3)
            Write-Info "dex version: $dexVersion"
        }
    }

    $arscEntry = $entries | Where-Object { $_.FullName -eq 'resources.arsc' } | Select-Object -First 1
    Test-Check 'resources.arsc present in archive' ($arscEntry -ne $null) 'missing resources.arsc'
    if ($arscEntry -ne $null) {
        Test-Check 'resources.arsc is stored uncompressed' `
            ($arscEntry.Length -eq $arscEntry.CompressedLength) `
            "length=$($arscEntry.Length) compressed=$($arscEntry.CompressedLength)"
    }

    $leakPattern = '(?i)^\.toolchain|^tools/|^test/|^app/|^src/|^build/|^keystore/|\.jks$|id_rsa|\.java$|\.ps1$|\.mjs$|\.class$|javac|\.toolchain'
    $leaked = @()
    foreach ($entry in $entries) {
        if ($entry.FullName -match $leakPattern) { $leaked += $entry.FullName }
    }
    Test-Check 'no toolchain/source/test/keystore files leaked into APK' ($leaked.Count -eq 0) `
        "leaked: $($leaked -join ', ')"
    Write-Info "uncompressed payload: $([math]::Round((($entries | Measure-Object -Property Length -Sum).Sum) / 1MB, 2)) MB"
} finally {
    $zip.Dispose()
}

# --- 6. secret material scan ----------------------------------------------
Write-Section 'secret material scan (raw bytes)'
$bytes = [System.IO.File]::ReadAllBytes($apkPath)
$latin1 = [System.Text.Encoding]::GetEncoding(28591).GetString($bytes)
$utf16 = [System.Text.Encoding]::Unicode.GetString($bytes)
$forbidden = @(
    'TESTTOKEN0000000000000000000000000000000000',
    'QQ7x1GAXn0DJkD5ycdKdH5QY8hABacDkP7XXrMtDZZZ',
    'SUPERSECRET123',
    'abcdef0123456789',
    'hunter2',
    'pw-secret',
    'passphrase-secret',
    'key-body',
    'secret-token-value',
    'dsh-mobile-mgr',
    'release.jks',
    '.toolchain',
    'SshSmokeTest',
    'TunnelEngineTest',
    'CredentialUrlTest',
    'HostsCodecTest',
    'LogSanitizerTest',
    'SshKeyManagerTest',
    'HostsMigrationTest'
)
# If the acceptance environment injects real SSH endpoints, make sure none of
# them leaked into the build. Keeps actual host values out of this script.
foreach ($envName in @('SSH_TEST_DIRECT_HOST', 'SSH_TEST_DIRECT_USER', 'SSH_TEST_JUMP_HOST',
                       'SSH_TEST_JUMP_USER', 'SSH_TEST_TARGET_HOST', 'SSH_TEST_TARGET_USER')) {
    $value = [Environment]::GetEnvironmentVariable($envName)
    if ($value -ne $null -and $value.Trim().Length -ge 3) { $forbidden += $value.Trim() }
}
Write-Info "scanned $($bytes.Length) bytes for $($forbidden.Count) patterns"
$foundSecrets = @()
foreach ($needle in $forbidden) {
    if ($latin1.Contains($needle) -or $utf16.Contains($needle)) { $foundSecrets += $needle }
}
Test-Check 'no test token / secret / toolchain strings in APK' ($foundSecrets.Count -eq 0) `
    "found: $($foundSecrets -join ', ')"

# A bare "-----BEGIN RSA PRIVATE KEY-----" constant is normal: the JSch library
# ships PEM header strings. Real key material has base64 data after the header.
$pemRegex = New-Object System.Text.RegularExpressions.Regex(
    '-----BEGIN [A-Z ]*PRIVATE KEY-----[\r\n]+[A-Za-z0-9+/=]{20,}')
$pemMatch = $pemRegex.IsMatch($latin1)
Test-Check 'no embedded private key material in APK' (-not $pemMatch) 'PEM private key body found in APK bytes'

# Any IPv4 literal other than loopback means a real host was hardcoded into the
# app; the app must learn host names from the imported SSH config instead.
# (127.0.0.1 is the tunnel endpoint; 0.0.0.0 is a JSch string constant.)
$hardcodedIps = @()
$ipRegex = New-Object System.Text.RegularExpressions.Regex('(?<![\d.])(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})(?![\d.])')
foreach ($match in $ipRegex.Matches($latin1)) {
    $ip = $match.Groups[1].Value
    if ($ip -eq '127.0.0.1' -or $ip -eq '0.0.0.0') { continue }
    if ($hardcodedIps -notcontains $ip) { $hardcodedIps += $ip }
}
Test-Check 'no hardcoded host IPv4 literal in APK (loopback excepted)' ($hardcodedIps.Count -eq 0) `
    "found: $($hardcodedIps -join ', ')"

# --- report ----------------------------------------------------------------
$total = $script:passCount + $script:failCount
Write-Section 'report'
Write-Info "apk      : $apkPath"
Write-Info "size     : $($apkInfo.Length) bytes"
Write-Info "sha256   : $sha256"
Write-Info "package  : $packageName $versionName (versionCode $versionCode), minSdk $minSdk, targetSdk $targetSdk"
Write-Info "checks   : $script:passCount/$total passed, $script:failCount failed"
if ($script:failCount -gt 0) {
    Write-Host ''
    foreach ($failure in $script:failures) { Write-Host "  [FAIL] $failure" -ForegroundColor Red }
    Write-Host ''
    Write-Host "APK CHECK FAILED ($($script:failCount) check(s))" -ForegroundColor Red
    exit 1
}
Write-Host ''
Write-Host 'APK CHECK PASSED' -ForegroundColor Green
exit 0
