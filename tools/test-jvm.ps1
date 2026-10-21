#!/usr/bin/env pwsh
<#
.SYNOPSIS
  Compile and run every desktop-JVM test for the DSH remote-hosts Android app.

.DESCRIPTION
  One command, no JUnit, no Gradle, no network at build time. It uses the
  portable toolchain provisioned by `node tools/setup-toolchain.mjs`:

    1. discover the pure-Java classes under app/src/dev/dsh/remotehosts/core
       (files that reference android.* are Android-only and skipped),
    2. compile them with javac -source 8 -target 8 against JSch + org.json,
    3. compile every test/jvm/*.java file,
    4. run the unit-test mains (v0.2.2: CredentialUrl, SshConfigParser,
       HostsCodec, LogSanitizer, SshKeyManager, HostsMigration, TokenProbe),
       then (unless -SkipSsh) the real-SSH smoke/integration tests.

  `test/jvm/TunnelEngineTest.java` is owned by the core developer and may not
  exist yet: it is compiled/run automatically once it appears.

  Exit codes:
    0  everything that could run passed (missing-network cases are reported
       as SKIPPED, not failed; a missing core layer is reported and skipped
       only when there is nothing at all to compile)
    1  at least one unit/SSH test failed
    2  toolchain missing/corrupt
    3  Java compilation failed (core or tests)

  NOTE: writes to build/jvm-test. Do not run concurrently with tools/build.ps1
  (which cleans build/).

.PARAMETER SkipSsh
  Only compile + run the offline unit tests (skips SshSmokeTest /
  TunnelEngineTest, which contact a real machine and need the network).

.PARAMETER RequireSsh
  Release-acceptance mode for the real-SSH tests. Passes
  -Dssh.require=true to them (a "not configured" SKIP becomes a failure) and
  stops treating network errors as SKIPs. The SSH tests are configured purely
  from the SSH_TEST_* environment variables (see test/jvm/SshSmokeTest.java);
  this script never embeds host names or credentials.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File tools/test-jvm.ps1

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File tools/test-jvm.ps1 -SkipSsh

.EXAMPLE
  $env:SSH_TEST_DIRECT_HOST='example.invalid'; ...
  powershell -NoProfile -ExecutionPolicy Bypass -File tools/test-jvm.ps1 -RequireSsh
#>
param(
    [switch]$SkipSsh,
    [switch]$RequireSsh
)

$ErrorActionPreference = 'Stop'

$root = Split-Path $PSScriptRoot -Parent
$toolchainFile = Join-Path $root '.toolchain/toolchain.json'
$coreDir = Join-Path $root 'app/src/dev/dsh/remotehosts/core'
$testDir = Join-Path $root 'test/jvm'
$buildDir = Join-Path $root 'build/jvm-test'
$coreClasses = Join-Path $buildDir 'core-classes'
$testClasses = Join-Path $buildDir 'test-classes'

function Write-Step([string]$message) {
    Write-Host ''
    Write-Host "==> $message" -ForegroundColor Cyan
}

function Write-Ok([string]$message) {
    Write-Host "  [ok]   $message" -ForegroundColor Green
}

function Write-Skip([string]$message) {
    Write-Host "  [skip] $message" -ForegroundColor Yellow
}

function Write-Fail([string]$message) {
    Write-Host "  [fail] $message" -ForegroundColor Red
}

function Exit-With([int]$code) {
    exit $code
}

# PS 5.1 turns redirected native stderr into ErrorRecords; with
# $ErrorActionPreference = 'Stop' that throws "NativeCommandError" even though
# the tool merely printed a warning. Capture with a temporarily relaxed EAP and
# return plain strings + the real exit code.
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
    Write-Fail "toolchain missing: $toolchainFile"
    Write-Host '  run: node tools/setup-toolchain.mjs'
    Exit-With 2
}
$tc = Get-Content $toolchainFile -Raw | ConvertFrom-Json
foreach ($tool in @('java', 'javac')) {
    $path = $tc.$tool
    if (-not (Test-Path $path)) {
        Write-Fail "toolchain entry '$tool' not found: $path"
        Exit-With 2
    }
}
$jsonJar = $tc.jsonJar
$jschJar = $tc.jschJar
$libClasspath = "$jschJar;$jsonJar"

# --- discover pure-Java core sources --------------------------------------
Write-Step 'discover pure-Java core sources'
if (-not (Test-Path $coreDir)) {
    Write-Skip "core not ready: directory $coreDir does not exist yet; nothing to test"
    Write-Host '  (this script turns green automatically once the core layer lands)'
    Exit-With 0
}

$allCore = @(Get-ChildItem $coreDir -Filter '*.java' -File | Sort-Object Name)
if ($allCore.Count -eq 0) {
    Write-Skip "core not ready: no .java files in $coreDir yet; nothing to test"
    Exit-With 0
}

$pureCore = New-Object System.Collections.ArrayList
$androidCore = New-Object System.Collections.ArrayList
$incompleteCore = New-Object System.Collections.ArrayList
foreach ($file in $allCore) {
    $text = [System.IO.File]::ReadAllText($file.FullName)
    # Strip comments before looking for android.* references so a Javadoc mention
    # does not reclassify a pure class as Android-only.
    $stripped = [regex]::Replace($text, '(?s)/\*.*?\*/', '')
    $stripped = [regex]::Replace($stripped, '(?m)//.*$', '')
    if ($stripped -match '\bandroid\.[A-Za-z]') {
        [void]$androidCore.Add($file.Name)
    } else {
        [void]$pureCore.Add($file.FullName)
    }
    if ($text -match 'TODO\(core\)') {
        [void]$incompleteCore.Add($file.Name)
    }
}
if ($pureCore.Count -eq 0) {
    Write-Skip "core not ready: every file in $coreDir still depends on Android; nothing to test"
    Exit-With 0
}
Write-Host "  pure Java: $(($pureCore | ForEach-Object { Split-Path $_ -Leaf }) -join ', ')"
if ($androidCore.Count -gt 0) {
    Write-Host "  android-only (skipped): $($androidCore -join ', ')"
}
if ($incompleteCore.Count -gt 0) {
    Write-Host "  warning: TODO(core) placeholders still present in: $($incompleteCore -join ', ')" -ForegroundColor Yellow
    Write-Host '           results below are expected to be red until the core layer is finished' -ForegroundColor Yellow
}

# --- clean build dir -------------------------------------------------------
Write-Step 'prepare build/jvm-test'
if (Test-Path $buildDir) {
    Remove-Item -Recurse -Force $buildDir
}
New-Item -ItemType Directory -Force -Path $coreClasses, $testClasses | Out-Null

# --- compile core ----------------------------------------------------------
Write-Step 'compile core (javac -source 8 -target 8)'
$javacArgs = @(
    '-J-Duser.language=en', '-J-Duser.country=US',
    '-encoding', 'UTF-8',
    '-source', '8', '-target', '8',
    '-Xlint:-options',
    '-classpath', $libClasspath,
    '-d', $coreClasses
) + @($pureCore)
$compile = Invoke-Capture $tc.javac $javacArgs
if ($compile.Code -ne 0) {
    Write-Fail "core compilation failed (javac exit $($compile.Code))"
    foreach ($line in $compile.Output) { Write-Host "    $line" }
    $joined = $compile.Output -join "`n"
    if ($joined -match 'cannot find symbol' -and $joined -match 'android') {
        Write-Host '  hint: an Android-only file was not detected above; that is a tools/test-jvm.ps1 bug.' -ForegroundColor Yellow
    } else {
        Write-Host '  hint: core/ does not compile yet (core-engine still implementing) or has a real error.' -ForegroundColor Yellow
    }
    Exit-With 3
}
foreach ($line in $compile.Output) {
    if ($line -match '(?i)\bwarning:') { Write-Host "    $line" -ForegroundColor Yellow }
}
Write-Ok "$($pureCore.Count) pure-Java core file(s) compiled"

# --- compile tests ---------------------------------------------------------
Write-Step 'compile JVM tests'
if (-not (Test-Path $testDir)) {
    Write-Fail "test directory missing: $testDir"
    Exit-With 3
}
$testSources = @(Get-ChildItem $testDir -Filter '*.java' -File | Sort-Object Name)
if ($testSources.Count -eq 0) {
    Write-Fail "no JVM test sources found in $testDir"
    Exit-With 3
}
$hasTunnelEngineTest = @($testSources | Where-Object { $_.Name -eq 'TunnelEngineTest.java' }).Count -gt 0
$hasSshSmokeTest = @($testSources | Where-Object { $_.Name -eq 'SshSmokeTest.java' }).Count -gt 0
if (-not $hasTunnelEngineTest) {
    Write-Skip 'test/jvm/TunnelEngineTest.java not present yet (owned by the core developer); will run when it lands'
}
$testJavacArgs = @(
    '-J-Duser.language=en', '-J-Duser.country=US',
    '-encoding', 'UTF-8',
    '-source', '8', '-target', '8',
    '-Xlint:-options',
    '-classpath', "$coreClasses;$libClasspath",
    '-d', $testClasses
)
foreach ($source in $testSources) { $testJavacArgs += $source.FullName }
$testCompile = Invoke-Capture $tc.javac $testJavacArgs
if ($testCompile.Code -ne 0) {
    Write-Fail "test compilation failed (javac exit $($testCompile.Code))"
    foreach ($line in $testCompile.Output) { Write-Host "    $line" }
    $joined = $testCompile.Output -join "`n"
    if ($joined -match 'cannot find symbol' -and $joined -match 'dev\.dsh\.remotehosts\.core') {
        Write-Host '  hint: the core layer is not ready (referenced class/method missing).' -ForegroundColor Yellow
        Write-Host '        Full test run will be possible once core-engine lands the missing files.' -ForegroundColor Yellow
    }
    Exit-With 3
}
Write-Ok "$($testSources.Count) test file(s) compiled"

$runClasspath = "$testClasses;$coreClasses;$libClasspath"

function Invoke-TestMain([string]$className, [string]$label, [string[]]$jvmArgs) {
    # Runs one main() and echoes its output. Returns @{ Code; Output }.
    Write-Host ''
    Write-Host "-- $label ($className)" -ForegroundColor Cyan
    $arguments = @()
    if ($jvmArgs -ne $null) { $arguments += $jvmArgs }
    $arguments += @('-Duser.language=en', '-Duser.country=US', '-Dfile.encoding=UTF-8')
    $arguments += @('-cp', $runClasspath, $className)
    $result = Invoke-Capture $tc.java $arguments
    foreach ($line in $result.Output) { Write-Host "    $line" }
    return @{ Code = $result.Code; Output = ($result.Output -join "`n") }
}

$unitTests = @('CredentialUrlTest', 'SshConfigParserTest', 'HostsCodecTest', 'LogSanitizerTest',
    'SshKeyManagerTest', 'HostsMigrationTest', 'TokenProbeTest')
$ranUnit = New-Object System.Collections.ArrayList
$failedUnit = New-Object System.Collections.ArrayList
$unitFailureDetails = New-Object System.Collections.ArrayList

Write-Step 'run offline unit tests'
foreach ($name in $unitTests) {
    $classFile = Join-Path $testClasses "$name.class"
    if (-not (Test-Path $classFile)) {
        Write-Skip "${name}: not compiled (test source missing)"
        continue
    }
    $result = Invoke-TestMain $name $name $null
    [void]$ranUnit.Add($name)
    if ($result.Code -ne 0) {
        [void]$failedUnit.Add($name)
        [void]$unitFailureDetails.Add("$name (exit $($result.Code))")
    }
}

# --- real-SSH integration tests -------------------------------------------
# Configuration is entirely environmental (SSH_TEST_*); with nothing set the
# tests print SKIP and exit 0. -RequireSsh passes -Dssh.require=true so a SKIP
# becomes a failure, and network errors stop counting as skips.
$ranSsh = New-Object System.Collections.ArrayList
$skippedSsh = New-Object System.Collections.ArrayList
$failedSsh = New-Object System.Collections.ArrayList

# Patterns that mean "the test host/network is unreachable", not "the code is wrong".
$networkSkipPattern = 'UnknownHostException|ConnectException|NoRouteToHostException|SocketTimeoutException|Connection refused|connect timed out|Connection timed out|Network is unreachable|No route to host|Connection reset'

function Test-IsNetworkSkip([string]$output) {
    return $output -match $networkSkipPattern
}

$sshJvmArgs = @()
if ($RequireSsh) { $sshJvmArgs += '-Dssh.require=true' }

if ($SkipSsh) {
    Write-Step 'real-SSH tests skipped (-SkipSsh)'
    [void]$skippedSsh.Add('SshSmokeTest (-SkipSsh)')
    [void]$skippedSsh.Add('TunnelEngineTest (-SkipSsh)')
} else {
    Write-Step 'run real-SSH integration tests'
    if ($RequireSsh) {
        Write-Host '  -RequireSsh: SKIP is a failure and network errors are failures' -ForegroundColor Yellow
        # Child JVMs inherit the parent process environment; list the names only
        # (never values) so the acceptance run is auditable.
        $configuredSshEnv = @(Get-ChildItem Env: | Where-Object { $_.Name -like 'SSH_TEST_*' } |
            ForEach-Object { $_.Name } | Sort-Object)
        if ($configuredSshEnv.Count -gt 0) {
            Write-Host "  SSH_TEST_* inherited by the JVMs: $($configuredSshEnv -join ', ')"
        } else {
            Write-Host '  warning: no SSH_TEST_* environment variables are set; -RequireSsh will fail' -ForegroundColor Yellow
        }
    } else {
        Write-Host '  configure via SSH_TEST_* env vars (see test/jvm/SshSmokeTest.java); unset -> SKIP'
    }
    $sshCases = @()
    if ($hasSshSmokeTest -and (Test-Path (Join-Path $testClasses 'SshSmokeTest.class'))) {
        $sshCases += @{ Class = 'SshSmokeTest'; Label = 'SshSmokeTest (direct + ProxyJump banner)'; Optional = $false }
    } else {
        Write-Skip 'SshSmokeTest: not available'
        if ($RequireSsh) { [void]$failedSsh.Add('SshSmokeTest (missing)') }
    }
    if ($hasTunnelEngineTest -and (Test-Path (Join-Path $testClasses 'TunnelEngineTest.class'))) {
        $sshCases += @{ Class = 'TunnelEngineTest'; Label = 'TunnelEngineTest (SshTunnelEngine integration)'; Optional = $true }
    } else {
        Write-Skip 'TunnelEngineTest: not present yet (core-engine owns it)'
        if ($RequireSsh) { [void]$failedSsh.Add('TunnelEngineTest (missing, required by -RequireSsh)') }
    }

    foreach ($case in $sshCases) {
        $result = Invoke-TestMain $case.Class $case.Label $sshJvmArgs
        [void]$ranSsh.Add($case.Class)
        if ($RequireSsh -and $result.Output -match 'SKIP') {
            # Acceptance runs must execute every real-SSH case, including the
            # per-case skips a partially configured environment would produce.
            [void]$failedSsh.Add("$($case.Class) (SKIP while -RequireSsh)")
            continue
        }
        if ($result.Code -eq 0) {
            if ($result.Output -match 'SKIP') { [void]$skippedSsh.Add("$($case.Class) (reported SKIP)") }
            continue
        }
        if ($RequireSsh) {
            [void]$failedSsh.Add("$($case.Class) (exit $($result.Code))")
            continue
        }
        if (Test-IsNetworkSkip $result.Output) {
            Write-Skip "$($case.Class): network/host unreachable - reported as SKIPPED, not a code failure"
            [void]$skippedSsh.Add("$($case.Class) (network unavailable)")
            continue
        }
        [void]$failedSsh.Add("$($case.Class) (exit $($result.Code))")
    }
}

# --- summary ---------------------------------------------------------------
Write-Step 'summary'
Write-Host "  unit tests run : $(if ($ranUnit.Count -gt 0) { $ranUnit -join ', ' } else { '(none)' })"
if ($failedUnit.Count -gt 0) {
    Write-Fail "unit failures: $($failedUnit -join ', ')"
} elseif ($ranUnit.Count -gt 0) {
    Write-Ok 'all runnable unit tests passed'
}
if ($SkipSsh) {
    Write-Skip 'SSH integration tests skipped (-SkipSsh)'
} else {
    Write-Host "  ssh tests run  : $(if ($ranSsh.Count -gt 0) { $ranSsh -join ', ' } else { '(none)' })"
    if ($skippedSsh.Count -gt 0) { Write-Skip "ssh skipped: $($skippedSsh -join '; ')" }
    if ($failedSsh.Count -gt 0) { Write-Fail "ssh failures: $($failedSsh -join ', ')" }
}

$failureCount = $failedUnit.Count + $failedSsh.Count
if ($failureCount -gt 0) {
    Write-Host ''
    Write-Fail "JVM TEST SUITE FAILED ($failureCount test class(es))"
    Exit-With 1
}

if ($ranUnit.Count -eq 0 -and $ranSsh.Count -eq 0 -and -not $SkipSsh) {
    Write-Skip 'nothing was executed (core layer not ready yet); not reporting a failure'
    Exit-With 0
}

Write-Host ''
Write-Ok 'JVM TEST SUITE PASSED'
Exit-With 0
