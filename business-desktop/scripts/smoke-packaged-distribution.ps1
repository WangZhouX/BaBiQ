param(
    [Parameter(Mandatory = $true)]
    [string]$AppBuildDir,

    [Parameter(Mandatory = $true)]
    [string]$RepositoryRoot
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

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
$desktopProcess = $null
$reportedChildPid = $null
$primaryFailure = $null
try {
    $msiRoot = Join-Path $appBuild 'compose\binaries\main\msi'
    Assert-Smoke (Test-Path -LiteralPath $msiRoot -PathType Container) 'Canonical packaged MSI directory is missing.'
    $msi = Get-ChildItem -LiteralPath $msiRoot -File -Filter '*.msi' |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1
    Assert-Smoke ($null -ne $msi) 'Packaged MSI was not produced.'

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

    $desktopExe = Get-ChildItem -LiteralPath $extractedRoot -Recurse -File -Filter 'HuitaiBusinessDesktop.exe' |
        Select-Object -First 1
    Assert-Smoke ($null -ne $desktopExe) 'HuitaiBusinessDesktop.exe is missing from the extracted MSI.'
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
    $reportPath = Join-Path $temporaryRoot 'smoke-report.json'
    $secretMarker = 'smoke-secret-marker-' + [Guid]::NewGuid().ToString('N')
    $environmentSnapshot = Set-SmokeEnvironment @{
        HUITAI_DESKTOP_HOME = $smokeHome
        HUITAI_DESKTOP_SMOKE_REPORT = $reportPath
        HUITAI_DESKTOP_KEYSTORE_PASSWORD = $secretMarker
        HUITAI_DESKTOP_FRAMEWORK_DEMO_IDENTITY = '0'
    }

    $desktopProcess = Start-Process -FilePath $desktopExe.FullName -WorkingDirectory $desktopExe.DirectoryName -PassThru -WindowStyle Hidden
    $deadline = [DateTime]::UtcNow.AddSeconds(120)
    while (-not (Test-Path -LiteralPath $reportPath -PathType Leaf)) {
        if ($desktopProcess.HasExited) {
            throw ("HuitaiBusinessDesktop.exe exited before writing the smoke report: {0}" -f $desktopProcess.ExitCode)
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
    Assert-Smoke ($null -eq $child) 'Bundled Agent child process remains alive after desktop shutdown.'

    $secretHits = Get-ChildItem -LiteralPath $expectedRuntimeRoot -Recurse -File -Filter '*.log' -ErrorAction SilentlyContinue |
        Select-String -SimpleMatch $secretMarker -ErrorAction SilentlyContinue
    Assert-Smoke ($null -eq $secretHits) 'Secret marker leaked into packaged runtime logs.'

    $reportedChildPid = $null
    $desktopProcess = $null
    Write-Host ("Packaged distribution smoke passed: {0}" -f $msi.FullName)
}
catch {
    $primaryFailure = $_
    throw
}
finally {
    $cleanupFailure = $null
    try {
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
    } catch {
        if ($null -eq $cleanupFailure) { $cleanupFailure = $_ }
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
        $verifiedTemporaryRoot = [IO.Path]::GetFullPath($temporaryRoot)
        if ($verifiedTemporaryRoot.StartsWith($systemTemp, [StringComparison]::OrdinalIgnoreCase) -and
            (Split-Path -Leaf $verifiedTemporaryRoot).StartsWith('huitai-packaged-smoke-') -and
            (Test-Path -LiteralPath $verifiedTemporaryRoot -PathType Container)) {
            Remove-Item -LiteralPath $verifiedTemporaryRoot -Recurse -Force
        }
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
