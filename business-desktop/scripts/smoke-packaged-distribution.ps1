param(
    [Parameter(Mandatory = $true)]
    [string]$AppBuildDir,

    [Parameter(Mandatory = $true)]
    [string]$RepositoryRoot
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'packaged-smoke-icon.ps1')

function Assert-Smoke {
    param(
        [Parameter(Mandatory = $true)] [bool]$Condition,
        [Parameter(Mandatory = $true)] [string]$Message
    )
    if (-not $Condition) { throw $Message }
}

function Test-PathWithinRoot {
    param(
        [Parameter(Mandatory = $true)] [string]$Candidate,
        [Parameter(Mandatory = $true)] [string]$Root
    )
    $candidatePath = [IO.Path]::GetFullPath($Candidate)
    $separators = [char[]]@([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    $rootPath = [IO.Path]::GetFullPath($Root).TrimEnd($separators)
    if ($candidatePath.Equals($rootPath, [StringComparison]::OrdinalIgnoreCase)) { return $true }
    $boundedRoot = $rootPath + [IO.Path]::DirectorySeparatorChar
    return $candidatePath.StartsWith($boundedRoot, [StringComparison]::OrdinalIgnoreCase)
}

function Remove-SmokeTemporaryRoot {
    param(
        [Parameter(Mandatory = $true)] [string]$Root,
        [Parameter(Mandatory = $true)] [string]$SystemTemp
    )
    $verifiedRoot = [IO.Path]::GetFullPath($Root)
    $verifiedSystemTemp = [IO.Path]::GetFullPath($SystemTemp)
    if (-not (Test-PathWithinRoot -Candidate $verifiedRoot -Root $verifiedSystemTemp) -or
        -not (Split-Path -Leaf $verifiedRoot).StartsWith('huitai-packaged-smoke-')) {
        throw 'Refusing to remove an unverified smoke temporary root.'
    }
    if (Test-Path -LiteralPath $verifiedRoot -PathType Container) {
        Remove-Item -LiteralPath $verifiedRoot -Recurse -Force
    }
}

function Set-SmokeEnvironment {
    param([hashtable]$Values)
    $snapshot = @{}
    foreach ($name in $Values.Keys) {
        $snapshot[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, [string]$Values[$name], 'Process')
    }
    return $snapshot
}

function Restore-SmokeEnvironment {
    param([hashtable]$Snapshot)
    if ($null -eq $Snapshot) { return }
    foreach ($name in $Snapshot.Keys) {
        [Environment]::SetEnvironmentVariable($name, $Snapshot[$name], 'Process')
    }
}

function Get-SmokeDescendantProcessIds {
    param(
        [int]$RootProcessId,
        [string]$OwnedRoot
    )
    $processes = @(Get-CimInstance Win32_Process -ErrorAction Stop)
    $pending = New-Object 'System.Collections.Generic.Queue[int]'
    $pending.Enqueue($RootProcessId)
    $result = New-Object 'System.Collections.Generic.List[int]'
    while ($pending.Count -gt 0) {
        $parentId = $pending.Dequeue()
        foreach ($child in $processes | Where-Object { [int]$_.ParentProcessId -eq $parentId }) {
            $childId = [int]$child.ProcessId
            $commandLine = [string]$child.CommandLine
            $executablePath = [string]$child.ExecutablePath
            $owned = $commandLine.IndexOf($OwnedRoot, [StringComparison]::OrdinalIgnoreCase) -ge 0 -or
                $executablePath.StartsWith($OwnedRoot, [StringComparison]::OrdinalIgnoreCase)
            if ($owned -and -not $result.Contains($childId)) {
                $result.Add($childId)
                $pending.Enqueue($childId)
            }
        }
    }
    return @($result)
}

function Stop-SmokeProcessId {
    param(
        [int]$ProcessId,
        [string]$OwnedRoot
    )
    $record = Get-CimInstance Win32_Process -Filter ("ProcessId = {0}" -f $ProcessId) -ErrorAction SilentlyContinue
    if ($null -eq $record) { return }
    $commandLine = [string]$record.CommandLine
    $executablePath = [string]$record.ExecutablePath
    $owned = $commandLine.IndexOf($OwnedRoot, [StringComparison]::OrdinalIgnoreCase) -ge 0 -or
        $executablePath.StartsWith($OwnedRoot, [StringComparison]::OrdinalIgnoreCase)
    if (-not $owned) { throw ("Refusing to terminate an unowned PID: {0}" -f $ProcessId) }
    $process = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if ($null -eq $process) { return }
    Stop-Process -Id $ProcessId -Force -ErrorAction Stop
    if (-not $process.WaitForExit(10000)) {
        throw ("Owned smoke process did not exit: {0}" -f $ProcessId)
    }
}

function Assert-NoSecretMarkerInTree {
    param(
        [Parameter(Mandatory = $true)] [string]$Root,
        [Parameter(Mandatory = $true)] [string]$Marker
    )
    if ([string]::IsNullOrWhiteSpace($Marker)) {
        throw 'Packaged smoke secret marker is unavailable.'
    }
    if (-not (Test-Path -LiteralPath $Root -PathType Container)) {
        throw 'Packaged smoke artifact root is unavailable.'
    }
    if ($null -eq ('SmokeSecretMarkerScanner' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.IO;

public static class SmokeSecretMarkerScanner
{
    public static bool ContainsFile(string path, byte[][] markers)
    {
        if (String.IsNullOrWhiteSpace(path) || markers == null || markers.Length == 0)
        {
            return false;
        }
        int maxMarkerLength = 0;
        foreach (byte[] marker in markers)
        {
            if (marker != null && marker.Length > maxMarkerLength)
            {
                maxMarkerLength = marker.Length;
            }
        }
        if (maxMarkerLength == 0)
        {
            return false;
        }
        const int chunkSize = 65536;
        byte[] buffer = new byte[chunkSize + maxMarkerLength - 1];
        int carry = 0;
        using (FileStream stream = new FileStream(
            path, FileMode.Open, FileAccess.Read, FileShare.Read, chunkSize, FileOptions.SequentialScan))
        {
            while (true)
            {
                int read = stream.Read(buffer, carry, chunkSize);
                if (read == 0)
                {
                    break;
                }
                int length = carry + read;
                foreach (byte[] marker in markers)
                {
                    if (Contains(buffer, length, marker))
                    {
                        return true;
                    }
                }
                carry = Math.Min(maxMarkerLength - 1, length);
                if (carry > 0)
                {
                    Buffer.BlockCopy(buffer, length - carry, buffer, 0, carry);
                }
            }
        }
        return false;
    }

    private static bool Contains(byte[] source, int sourceLength, byte[] marker)
    {
        if (marker == null || marker.Length == 0 || sourceLength < marker.Length)
        {
            return false;
        }
        int limit = sourceLength - marker.Length;
        int last = marker.Length - 1;
        for (int offset = 0; offset <= limit; offset++)
        {
            if (source[offset] != marker[0] || source[offset + last] != marker[last])
            {
                continue;
            }
            int index = 1;
            while (index < marker.Length && source[offset + index] == marker[index])
            {
                index++;
            }
            if (index == marker.Length)
            {
                return true;
            }
        }
        return false;
    }
}
'@
    }
    [byte[][]]$markerBytes = @(
        [Text.Encoding]::UTF8.GetBytes($Marker),
        [Text.Encoding]::Unicode.GetBytes($Marker),
        [Text.Encoding]::BigEndianUnicode.GetBytes($Marker)
    )
    $entries = @(Get-ChildItem -LiteralPath $Root -Recurse -Force -ErrorAction Stop)
    foreach ($entry in $entries) {
        if ($entry.FullName.IndexOf($Marker, [StringComparison]::Ordinal) -ge 0) {
            throw 'Secret marker detected in packaged smoke artifacts.'
        }
        if (($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw 'Unexpected reparse point in packaged smoke artifacts.'
        }
        if ($entry.PSIsContainer) {
            continue
        }
        if ([SmokeSecretMarkerScanner]::ContainsFile($entry.FullName, $markerBytes)) {
            throw 'Secret marker detected in packaged smoke artifacts.'
        }
    }
}

function Get-MsiProperty {
    param(
        [Parameter(Mandatory = $true)] [string]$Path,
        [Parameter(Mandatory = $true)] [string]$Property
    )
    $installerObject = $null
    $database = $null
    $view = $null
    $record = $null
    try {
        $installerObject = New-Object -ComObject WindowsInstaller.Installer
        $database = $installerObject.OpenDatabase($Path, 0)
        $query = "SELECT ``Value`` FROM ``Property`` WHERE ``Property`` = '$Property'"
        $view = $database.OpenView($query)
        [void]$view.Execute()
        $record = $view.Fetch()
        if ($null -eq $record) { return $null }
        return [string]$record.StringData(1)
    } finally {
        foreach ($item in @($record, $view, $database, $installerObject)) {
            if ($null -ne $item -and [Runtime.InteropServices.Marshal]::IsComObject($item)) {
                [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($item)
            }
        }
    }
}

$appBuild = [IO.Path]::GetFullPath($AppBuildDir)
$repoRoot = [IO.Path]::GetFullPath($RepositoryRoot)
Assert-Smoke (Test-Path -LiteralPath $repoRoot -PathType Container) 'Repository root is unavailable.'
$expectedAppBuild = [IO.Path]::GetFullPath((Join-Path $repoRoot 'business-desktop\app\build'))
Assert-Smoke ($appBuild -eq $expectedAppBuild) 'AppBuildDir must be the repository business-desktop app build directory.'

$systemTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$temporaryRoot = Join-Path $systemTemp ("huitai-packaged-smoke-" + [Guid]::NewGuid().ToString('N'))
$temporaryRoot = [IO.Path]::GetFullPath($temporaryRoot)
Assert-Smoke ($temporaryRoot.StartsWith($systemTemp, [StringComparison]::OrdinalIgnoreCase)) 'Unsafe smoke temporary root.'
Assert-Smoke ((Split-Path -Leaf $temporaryRoot).StartsWith('huitai-packaged-smoke-')) 'Unexpected smoke temporary directory.'
New-Item -ItemType Directory -Path $temporaryRoot | Out-Null

$environmentSnapshot = $null
$authenticatedEnvironmentSnapshot = $null
$desktopProcess = $null
$authenticatedProcess = $null
$fakeOaProcess = $null
$reportedChildPid = $null
$primaryFailure = $null
$secretMarker = $null
$authenticatedSecretMarkers = @()
$expectedProductName = -join ([char[]]@(0x7FD4, 0x9E1F, 0x5F8B, 0x667A, 0x684C, 0x9762, 0x7AEF))
$desktopLauncherName = "$expectedProductName.exe"
try {
    $msiRoot = Join-Path $appBuild 'compose\binaries\main\msi'
    Assert-Smoke (Test-Path -LiteralPath $msiRoot -PathType Container) 'Canonical packaged MSI directory is missing.'
    $msi = Get-ChildItem -LiteralPath $msiRoot -File -Filter '*.msi' |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1
    Assert-Smoke ($null -ne $msi) 'Packaged MSI was not produced.'

    $exeRoot = Join-Path $appBuild 'compose\binaries\main\exe'
    Assert-Smoke (Test-Path -LiteralPath $exeRoot -PathType Container) 'Canonical packaged EXE directory is missing.'
    $packageExe = Get-ChildItem -LiteralPath $exeRoot -File -Filter '*.exe' |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1
    Assert-Smoke ($null -ne $packageExe) 'Packaged EXE was not produced.'

    $expectedInstallerProductName = "$expectedProductName Installer"
    $expectedInstallerDescription = "Installer of $expectedProductName"
    $msiProductName = Get-MsiProperty -Path $msi.FullName -Property 'ProductName'
    Assert-Smoke ($msiProductName -eq $expectedProductName) 'MSI ProductName does not match the Xiangniao brand.'
    $packageExeVersion = [Diagnostics.FileVersionInfo]::GetVersionInfo($packageExe.FullName)
    Assert-Smoke ($packageExeVersion.ProductName -eq $expectedInstallerProductName) 'EXE ProductName does not match the Xiangniao installer brand.'
    Assert-Smoke ($packageExeVersion.FileDescription -eq $expectedInstallerDescription) 'EXE FileDescription does not match the Xiangniao installer brand.'

    $extractedRoot = Join-Path $temporaryRoot 'extracted'
    New-Item -ItemType Directory -Path $extractedRoot | Out-Null
    $msiArguments = @(
        '/a',
        ('"{0}"' -f $msi.FullName),
        '/qn',
        ('TARGETDIR="{0}"' -f $extractedRoot)
    )
    $installer = Start-Process -FilePath 'msiexec.exe' -ArgumentList $msiArguments -Wait -PassThru -WindowStyle Hidden
    Assert-Smoke ($installer.ExitCode -eq 0) ("MSI administrative extraction failed: {0}" -f $installer.ExitCode)

    $desktopExe = Get-ChildItem -LiteralPath $extractedRoot -Recurse -File -Filter $desktopLauncherName |
        Select-Object -First 1
    Assert-Smoke ($null -ne $desktopExe) ("{0} is missing from the extracted MSI." -f $desktopLauncherName)
    $launcherVersion = [Diagnostics.FileVersionInfo]::GetVersionInfo($desktopExe.FullName)
    Assert-Smoke ($launcherVersion.ProductName -eq $expectedProductName) 'Extracted launcher ProductName is invalid.'
    $brandIconPath = Join-Path $repoRoot 'business-desktop\app\src\main\resources\brand\xiangniao.ico'
    Assert-Smoke (Test-LauncherBrandIcon -LauncherPath $desktopExe.FullName -BrandIconPath $brandIconPath) 'The launcher associated icon does not match the Xiangniao brand.'
    $bundledJar = Get-ChildItem -LiteralPath $extractedRoot -Recurse -File -Filter 'babiq-server.jar' |
        Where-Object { $_.FullName -match '[\\/]backend[\\/]babiq-server\.jar$' } |
        Select-Object -First 1
    Assert-Smoke ($null -ne $bundledJar) 'common/backend/babiq-server.jar is missing from the package.'
    $runtimeJava = Get-ChildItem -LiteralPath $extractedRoot -Recurse -File -Filter 'java.exe' |
        Where-Object { $_.FullName -match '[\\/]runtime[\\/]bin[\\/]java\.exe$' } |
        Select-Object -First 1
    Assert-Smoke ($null -ne $runtimeJava) 'The packaged runtime does not retain runtime/bin/java.exe.'

    $smokeHome = Join-Path $temporaryRoot 'home'
    New-Item -ItemType Directory -Path $smokeHome | Out-Null
    $processTemp = Join-Path $temporaryRoot 'process-temp'
    New-Item -ItemType Directory -Path $processTemp | Out-Null
    $reportPath = Join-Path $temporaryRoot 'smoke-report.json'
    $secretMarker = 'smoke-secret-marker-' + [Guid]::NewGuid().ToString('N')
    $environmentSnapshot = Set-SmokeEnvironment @{
        HUITAI_DESKTOP_HOME = $smokeHome
        HUITAI_DESKTOP_SMOKE_REPORT = $reportPath
        HUITAI_DESKTOP_KEYSTORE_PASSWORD = $secretMarker
        TEMP = $processTemp
        TMP = $processTemp
        TMPDIR = $processTemp
    }

    $desktopProcess = Start-Process -FilePath $desktopExe.FullName -WorkingDirectory $desktopExe.DirectoryName -PassThru -WindowStyle Hidden
    $deadline = [DateTime]::UtcNow.AddSeconds(120)
    while (-not (Test-Path -LiteralPath $reportPath -PathType Leaf)) {
        if ($desktopProcess.HasExited) {
            throw ("{0} exited before writing the smoke report: {1}" -f $desktopLauncherName, $desktopProcess.ExitCode)
        }
        if ([DateTime]::UtcNow -ge $deadline) { throw 'Packaged desktop smoke timed out after 120 seconds.' }
        Start-Sleep -Milliseconds 250
        $desktopProcess.Refresh()
    }

    $report = Get-Content -LiteralPath $reportPath -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-Smoke ($report.profile -eq 'business-desktop') 'Unexpected backend profile.'
    Assert-Smoke ($report.dynamicPort -eq $true -and [int]$report.port -gt 0) 'Dynamic port evidence is invalid.'
    Assert-Smoke ($report.loopbackAddress -eq $true -and $report.address -eq '127.0.0.1') 'Agent did not bind exact loopback.'
    Assert-Smoke ($report.tokenFileDeleted -eq $true) 'One-shot token deletion was not proven.'
    Assert-Smoke ($report.unauthorizedHandshakeRejected -eq $true) 'Unauthorized handshake rejection was not proven.'
    Assert-Smoke ($report.authenticatedConnection -eq $true) 'Authenticated WebSocket connection was not proven.'
    Assert-Smoke ($report.signedOutIdentityBound -eq $true) 'Framework signed-out identity bind was not proven.'
    Assert-Smoke ($report.windowComposed -eq $true) 'The real Compose Window was not committed.'
    Assert-Smoke ($report.loginGateComposed -eq $true) 'The signed-out login gate was not composed.'
    Assert-Smoke ($report.businessShellHiddenWhileSignedOut -eq $true) 'The business shell was visible while signed out.'
    Assert-Smoke ($report.brandLogoDecoded -eq $true) 'The packaged brand logo was not decoded.'
    Assert-Smoke ($report.productName -eq $expectedProductName) 'The composed product name is invalid.'
    Assert-Smoke ([long]$report.childPid -gt 0) 'Child PID is missing.'
    $reportedChildPid = [int]$report.childPid

    $expectedRuntimeRoot = [IO.Path]::GetFullPath((Join-Path $smokeHome '.huitai-agent-desktop'))
    Assert-Smoke ([IO.Path]::GetFullPath([string]$report.runtimeRoot) -eq $expectedRuntimeRoot) 'Runtime escaped the temporary home.'
    foreach ($reportedPath in @(
        $report.desktopRoot,
        $report.agentRoot,
        $report.desktopDatabase,
        $report.agentDatabase,
        $report.desktopKeyStore,
        $report.agentKeyStore,
        $report.tokenFile
    )) {
        Assert-Smoke (Test-PathWithinRoot -Candidate ([string]$reportedPath) -Root $expectedRuntimeRoot) 'Reported runtime path escaped isolation.'
    }
    Assert-Smoke ($report.desktopRoot -ne $report.agentRoot) 'Desktop and Agent roots are shared.'
    Assert-Smoke (-not (Test-Path -LiteralPath ([string]$report.tokenFile))) 'One-shot token file still exists.'
    Assert-Smoke (Test-Path -LiteralPath ([string]$report.desktopDatabase) -PathType Leaf) 'Desktop SQLite database is missing.'
    Assert-Smoke (Test-Path -LiteralPath ([string]$report.agentDatabase) -PathType Leaf) 'Agent SQLite database is missing.'

    Assert-Smoke ($desktopProcess.WaitForExit(30000)) 'Desktop did not exit after the one-run probe.'
    $desktopProcess.Refresh()
    Assert-Smoke ($desktopProcess.HasExited) 'Desktop process remains alive after smoke completion.'
    $child = Get-Process -Id $reportedChildPid -ErrorAction SilentlyContinue
    if ($null -ne $child) {
        Assert-Smoke ($child.WaitForExit(30000)) 'Bundled Agent child process did not stop during graceful shutdown.'
        $child.Refresh()
    }
    $child = Get-Process -Id $reportedChildPid -ErrorAction SilentlyContinue
    Assert-Smoke ($null -eq $child) 'Bundled Agent child process remains alive after desktop shutdown.'

    $reportedChildPid = $null
    $desktopProcess = $null

    $fakeOaReadyReport = Join-Path $temporaryRoot 'fake-oa-ready.json'
    $authenticatedReport = Join-Path $temporaryRoot 'authenticated-smoke-report.json'
    $authenticatedHome = Join-Path $temporaryRoot 'authenticated-home'
    New-Item -ItemType Directory -Path $authenticatedHome | Out-Null
    $authenticatedPassword = 'Pa' + [Guid]::NewGuid().ToString('N').Substring(0, 12)
    $authenticatedAccess = 'oa-access-' + [Guid]::NewGuid().ToString('N')
    $authenticatedRefresh = 'oa-refresh-' + [Guid]::NewGuid().ToString('N')
    $authenticatedSecretMarkers = @($authenticatedPassword, $authenticatedAccess, $authenticatedRefresh)
    $authenticatedEnvironmentSnapshot = Set-SmokeEnvironment @{
        HUITAI_DESKTOP_AUTH_SMOKE_ACCOUNT = '13800138000'
        HUITAI_DESKTOP_AUTH_SMOKE_PASSWORD = $authenticatedPassword
        HUITAI_DESKTOP_AUTH_SMOKE_ACCESS = $authenticatedAccess
        HUITAI_DESKTOP_AUTH_SMOKE_REFRESH = $authenticatedRefresh
        HUITAI_OA_BASE_URL = ''
    }
    $fakeOaScript = Join-Path $repoRoot 'business-desktop\scripts\packaged-authenticated-fake-oa.ps1'
    $fakeOaArguments = @(
        '-NoProfile',
        '-ExecutionPolicy',
        'Bypass',
        '-File',
        ('"{0}"' -f $fakeOaScript),
        '-ReadyReport',
        ('"{0}"' -f $fakeOaReadyReport)
    )
    $fakeOaOutput = Join-Path $temporaryRoot 'fake-oa.stdout.log'
    $fakeOaError = Join-Path $temporaryRoot 'fake-oa.stderr.log'
    $fakeOaProcess = Start-Process -FilePath 'powershell.exe' -ArgumentList $fakeOaArguments `
        -PassThru -WindowStyle Hidden -WorkingDirectory $repoRoot `
        -RedirectStandardOutput $fakeOaOutput -RedirectStandardError $fakeOaError
    $fakeOaDeadline = [DateTime]::UtcNow.AddSeconds(30)
    while (-not (Test-Path -LiteralPath $fakeOaReadyReport -PathType Leaf)) {
        if ($fakeOaProcess.HasExited) {
            $fakeFailure = if (Test-Path -LiteralPath $fakeOaError -PathType Leaf) {
                Get-Content -LiteralPath $fakeOaError -Raw -Encoding UTF8
            } else { '' }
            throw ("Authenticated packaged-smoke fake OA exited early: {0}; {1}" -f
                $fakeOaProcess.ExitCode, $fakeFailure.Trim())
        }
        if ([DateTime]::UtcNow -ge $fakeOaDeadline) {
            throw 'Authenticated packaged-smoke fake OA timed out during startup.'
        }
        Start-Sleep -Milliseconds 100
        $fakeOaProcess.Refresh()
    }
    $fakeOaReady = Get-Content -LiteralPath $fakeOaReadyReport -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-Smoke ([string]$fakeOaReady.baseUrl -match '^http://127\.0\.0\.1:\d+$') `
        'Authenticated packaged-smoke fake OA did not bind exact loopback.'
    [Environment]::SetEnvironmentVariable('HUITAI_OA_BASE_URL', [string]$fakeOaReady.baseUrl, 'Process')

    $packagedAppDirectory = Join-Path $desktopExe.DirectoryName 'app'
    Assert-Smoke (Test-Path -LiteralPath $packagedAppDirectory -PathType Container) `
        'Extracted packaged application classpath is missing.'
    $authenticatedArguments = @(
        '-cp',
        ('"{0}\*"' -f $packagedAppDirectory),
        'com.wzx.huitai.desktop.smoke.PackagedAuthenticatedSmokeMainKt',
        ('"{0}"' -f $authenticatedReport),
        ('"{0}"' -f $bundledJar.FullName),
        ('"{0}"' -f $authenticatedHome)
    )
    $authenticatedOutput = Join-Path $temporaryRoot 'authenticated.stdout.log'
    $authenticatedError = Join-Path $temporaryRoot 'authenticated.stderr.log'
    $authenticatedProcess = Start-Process -FilePath $runtimeJava.FullName `
        -ArgumentList $authenticatedArguments -PassThru -WindowStyle Hidden `
        -WorkingDirectory $desktopExe.DirectoryName `
        -RedirectStandardOutput $authenticatedOutput -RedirectStandardError $authenticatedError
    $authenticatedDeadline = [DateTime]::UtcNow.AddSeconds(120)
    while (-not (Test-Path -LiteralPath $authenticatedReport -PathType Leaf)) {
        if ($authenticatedProcess.HasExited) {
            $authenticatedFailure = if (Test-Path -LiteralPath $authenticatedError -PathType Leaf) {
                Get-Content -LiteralPath $authenticatedError -Raw -Encoding UTF8
            } else { '' }
            foreach ($marker in $authenticatedSecretMarkers) {
                $authenticatedFailure = $authenticatedFailure.Replace($marker, '[REDACTED]')
            }
            throw ("Authenticated packaged-smoke runtime exited early: {0}; {1}" -f
                $authenticatedProcess.ExitCode, $authenticatedFailure.Trim())
        }
        if ([DateTime]::UtcNow -ge $authenticatedDeadline) {
            throw 'Authenticated packaged-smoke runtime timed out after 120 seconds.'
        }
        Start-Sleep -Milliseconds 250
        $authenticatedProcess.Refresh()
    }
    $authenticatedResult = Get-Content -LiteralPath $authenticatedReport -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-Smoke ($authenticatedResult.profile -eq 'business-desktop') `
        'Authenticated packaged smoke used an unexpected profile.'
    Assert-Smoke ($authenticatedResult.oaLoopback -eq $true) `
        'Authenticated packaged smoke did not use loopback OA.'
    Assert-Smoke ($authenticatedResult.ready -eq $true -and [long]$authenticatedResult.identityEpoch -gt 0) `
        'Authenticated packaged smoke did not reach READY.'
    Assert-Smoke ($authenticatedResult.workbenchReady -eq $true) `
        'Authenticated packaged smoke did not load the workbench.'
    Assert-Smoke ($authenticatedResult.navigationAllowlisted -eq $true) `
        'Authenticated packaged smoke navigation escaped its allowlist.'
    Assert-Smoke ($authenticatedResult.assistantControllerReady -eq $true) `
        'Authenticated packaged smoke did not exercise the assistant controller.'
    Assert-Smoke ($authenticatedProcess.WaitForExit(30000)) `
        'Authenticated packaged-smoke runtime did not exit after reporting.'
    $authenticatedProcess.Refresh()
    Assert-Smoke ($authenticatedProcess.HasExited) `
        'Authenticated packaged-smoke runtime remains alive after reporting.'
    $authenticatedFailure = if (Test-Path -LiteralPath $authenticatedError -PathType Leaf) {
        Get-Content -LiteralPath $authenticatedError -Raw -Encoding UTF8
    } else { '' }
    Assert-Smoke ([string]::IsNullOrWhiteSpace($authenticatedFailure)) `
        'Authenticated packaged-smoke runtime wrote an error after reporting.'
    Write-Host ("Packaged distribution smoke passed (signed-out + fake-OA authenticated): MSI={0}; EXE={1}" -f $msi.FullName, $packageExe.FullName)
}
catch {
    $primaryFailure = $_
    throw
}
finally {
    $cleanupFailure = $null
    try {
        Restore-SmokeEnvironment $authenticatedEnvironmentSnapshot
        Restore-SmokeEnvironment $environmentSnapshot
    } catch {
        $cleanupFailure = $_
    }
    $descendants = @()
    try {
        if ($null -ne $desktopProcess) {
            $descendants = @(
                Get-SmokeDescendantProcessIds -RootProcessId $desktopProcess.Id -OwnedRoot $temporaryRoot
            )
        }
        foreach ($ownedRootProcess in @($authenticatedProcess, $fakeOaProcess)) {
            if ($null -ne $ownedRootProcess) {
                $descendants += @(
                    Get-SmokeDescendantProcessIds -RootProcessId $ownedRootProcess.Id -OwnedRoot $temporaryRoot
                )
            }
        }
    } catch {
        if ($null -eq $cleanupFailure) { $cleanupFailure = $_ }
    }
    foreach ($ownedRootProcess in @($authenticatedProcess, $fakeOaProcess)) {
        try {
            if ($null -ne $ownedRootProcess -and -not $ownedRootProcess.HasExited) {
                Stop-SmokeProcessId -ProcessId $ownedRootProcess.Id -OwnedRoot $temporaryRoot
            }
        } catch {
            if ($null -eq $cleanupFailure) { $cleanupFailure = $_ }
        }
    }
    if ($null -ne $reportedChildPid) {
        $descendants += $reportedChildPid
    }
    $descendants = @($descendants | Select-Object -Unique)
    [Array]::Reverse($descendants)
    foreach ($descendantId in $descendants) {
        try {
            Stop-SmokeProcessId -ProcessId ([int]$descendantId) -OwnedRoot $temporaryRoot
        } catch {
            if ($null -eq $cleanupFailure) { $cleanupFailure = $_ }
        }
    }
    try {
        if ($null -ne $desktopProcess -and -not $desktopProcess.HasExited) {
            Stop-Process -InputObject $desktopProcess -Force -ErrorAction Stop
            if (-not $desktopProcess.WaitForExit(10000)) {
                throw 'Desktop process did not exit during smoke cleanup.'
            }
        }
    } catch {
        if ($null -eq $cleanupFailure) { $cleanupFailure = $_ }
    }
    try {
        $ownedProcesses = @(
            Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object {
                $commandLine = [string]$_.CommandLine
                $executablePath = [string]$_.ExecutablePath
                $commandLine.IndexOf($temporaryRoot, [StringComparison]::OrdinalIgnoreCase) -ge 0 -or
                    $executablePath.StartsWith($temporaryRoot, [StringComparison]::OrdinalIgnoreCase)
            }
        )
        if ($ownedProcesses.Count -gt 0) {
            throw ("Owned smoke processes remain alive: {0}" -f (($ownedProcesses | ForEach-Object ProcessId) -join ','))
        }
        if ($null -ne $secretMarker) {
            Assert-NoSecretMarkerInTree -Root $temporaryRoot -Marker $secretMarker
        }
        foreach ($authenticatedSecretMarker in $authenticatedSecretMarkers) {
            Assert-NoSecretMarkerInTree -Root $temporaryRoot -Marker $authenticatedSecretMarker
        }
    } catch {
        if ($null -eq $cleanupFailure) { $cleanupFailure = $_ }
    }
    try {
        Remove-SmokeTemporaryRoot -Root $temporaryRoot -SystemTemp $systemTemp
    } catch {
        if ($null -eq $cleanupFailure) { $cleanupFailure = $_ }
    }
    if ($null -ne $cleanupFailure) {
        if ($null -eq $primaryFailure) {
            throw $cleanupFailure
        }
        Write-Warning ("Smoke cleanup also failed: {0}" -f $cleanupFailure.Exception.Message)
    }
}
