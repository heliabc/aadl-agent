#Requires -Version 7.0
<#
Install the official portable OSATE CLI only when this script is explicitly run.
Existing installations are checked before any network request or file changes.
#>
[CmdletBinding()]
param(
    [string]$ToolsDirectory = (Join-Path (Split-Path $PSScriptRoot -Parent) '.tools')
)

$script:OsateVersion = '0.3.0'
$script:OsateAssetName = 'osate-cli-0.3.0-windows-x64.zip'
$script:OsateExpectedHash = '06a4eaf5f28ec85f0fe1b66c660fe3b2b0afd040d4e539f5aa85919ca7259ffa'
$script:OsateReleaseUri = 'https://api.github.com/repos/osate/aadl-tooling/releases/tags/osate-cli-v0.3.0'
$script:OsateDownloadUri = 'https://github.com/osate/aadl-tooling/releases/download/osate-cli-v0.3.0/osate-cli-0.3.0-windows-x64.zip'

function Assert-OsateArchive {
    param([Parameter(Mandatory)][string]$Path)
    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant()
    if ($actual -ne $script:OsateExpectedHash) {
        throw "OSATE archive SHA256 mismatch: $Path. Expected $script:OsateExpectedHash; got $actual. The file was preserved; it will not be overwritten."
    }
}

function Get-OsateOfficialAsset {
    # Pin both the release asset URL and its official metadata digest. A changed
    # upstream artifact must be reviewed instead of silently accepting new bytes.
    $release = Invoke-RestMethod -Uri $script:OsateReleaseUri -TimeoutSec 60 -ErrorAction Stop `
        -Headers @{ 'User-Agent' = 'aadl-single-agent-osate-installer' }
    $assets = @($release.assets | Where-Object { $_.name -eq $script:OsateAssetName })
    if ($release.tag_name -ne "osate-cli-v$script:OsateVersion" -or $assets.Count -ne 1 -or
        $assets[0].digest -ne "sha256:$script:OsateExpectedHash" -or
        $assets[0].browser_download_url -ne $script:OsateDownloadUri) {
        throw 'Official OSATE release metadata differs from the pinned version, URL or SHA256. No package was installed.'
    }
    return $assets[0].browser_download_url
}

function Get-OsateVersionResult {
    param([Parameter(Mandatory)][string]$JavaPath, [Parameter(Mandatory)][string]$JarPath)
    # Invoke the bundled executable directly: cmd.exe would expand percent
    # signs in a batch-file path. Separate streams also keep harmless Java
    # environment notices on stderr out of the stdout version comparison.
    $info = [System.Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $JavaPath
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    foreach ($argument in @('-jar', $JarPath, '--version')) {
        $info.ArgumentList.Add($argument)
    }
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $info
    try {
        $process.Start() | Out-Null
        # Drain both streams concurrently so a noisy process cannot block on a
        # full pipe before the finite startup timeout is reached.
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(30000)) {
            $process.Kill($true)
            if (-not $process.WaitForExit(5000)) {
                throw 'OSATE startup timed out after 30 seconds; its child process did not exit within the additional 5-second shutdown limit.'
            }
            throw "OSATE startup/version verification timed out after 30 seconds. stdout: $($stdout.GetAwaiter().GetResult()) stderr: $($stderr.GetAwaiter().GetResult())"
        }
        return [pscustomobject]@{ stdout = $stdout.GetAwaiter().GetResult()
            stderr = $stderr.GetAwaiter().GetResult(); exit_code = $process.ExitCode }
    } finally {
        $process.Dispose()
    }
}

function Assert-OsatePackage {
    param([Parameter(Mandatory)][string]$Root)
    # The portable package carries its own Java runtime. Checking these files
    # avoids treating a lone launcher or an interrupted extraction as installed.
    foreach ($relative in @('bin/osate-cli.bat', 'osate-cli.jar', 'runtime/bin/java.exe')) {
        if (-not (Test-Path -LiteralPath (Join-Path $Root $relative) -PathType Leaf)) {
            throw "OSATE installation is incomplete: missing $relative in $Root. Existing files were preserved; select a complete CLI in config.yaml or use a different -ToolsDirectory."
        }
    }
    $libraries = Join-Path $Root 'lib'
    if (-not (Test-Path -LiteralPath $libraries -PathType Container) -or
        @(Get-ChildItem -LiteralPath $libraries -Filter '*.jar' -File -ErrorAction Stop).Count -eq 0) {
        throw "OSATE installation is incomplete: missing libraries in $Root. Existing files were preserved."
    }
    $launcher = Join-Path $Root 'bin/osate-cli.bat'
    $version = Get-OsateVersionResult -JavaPath (Join-Path $Root 'runtime/bin/java.exe') -JarPath (Join-Path $Root 'osate-cli.jar')
    $banner = $version.stdout.Trim()
    if ($version.exit_code -ne 0 -or $banner -ne "osate-cli $script:OsateVersion") {
        throw "OSATE startup/version verification failed at $launcher (exit $($version.exit_code)). Expected 'osate-cli $script:OsateVersion'; stdout: $banner; stderr: $($version.stderr)"
    }
    return $launcher
}

function Expand-OsatePackage {
    param([Parameter(Mandatory)][string]$ArchivePath, [Parameter(Mandatory)][string]$StagingRoot)
    Expand-Archive -LiteralPath $ArchivePath -DestinationPath $StagingRoot -ErrorAction Stop
    $launchers = @(Get-ChildItem -LiteralPath $StagingRoot -Filter 'osate-cli.bat' -File -Recurse -ErrorAction Stop)
    if ($launchers.Count -ne 1) {
        throw "Expected one OSATE launcher; inspect the preserved staging directory: $StagingRoot"
    }
    $packageRoot = [System.IO.Path]::GetFullPath((Split-Path (Split-Path $launchers[0].FullName -Parent) -Parent))
    $stage = [System.IO.Path]::GetFullPath($StagingRoot)
    if ($packageRoot -ne $stage -and -not $packageRoot.StartsWith(
        $stage + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to move a package outside its staging directory: $packageRoot"
    }
    return $packageRoot
}

function Invoke-OsateInstallation {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$ToolsDirectory)
    $ErrorActionPreference = 'Stop'
    $ProgressPreference = 'SilentlyContinue'
    if (-not $IsWindows -or [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture -ne 'X64') {
        throw 'This installer supports Windows x64 only. Set osate_cli in config.yaml to a verified CLI launcher for your platform.'
    }
    $toolsRoot = [System.IO.Path]::GetFullPath($ToolsDirectory)
    $installRoot = Join-Path $toolsRoot "osate-cli-$script:OsateVersion"
    $archivePath = Join-Path $toolsRoot $script:OsateAssetName
    if (Test-Path -LiteralPath $installRoot) {
        # Reuse is entirely offline and does not modify the installation. A
        # manually installed package may have no ZIP cache; report that honestly.
        $launcher = Assert-OsatePackage -Root $installRoot
        $archiveHash = $null
        if (Test-Path -LiteralPath $archivePath) {
            Assert-OsateArchive -Path $archivePath
            $archiveHash = $script:OsateExpectedHash
        }
        return [pscustomobject]@{ status = 'reused'; path = $launcher; version = $script:OsateVersion
            archive_sha256 = $archiveHash; verification = 'package components and startup version' }
    }

    [System.IO.Directory]::CreateDirectory($toolsRoot) | Out-Null
    $operation = [guid]::NewGuid().ToString('N')
    $logPath = Join-Path $toolsRoot ".osate-install-$operation.log"
    $stagingRoot = Join-Path $toolsRoot ".osate-install-$operation"
    $partialPath = "$archivePath.partial-$operation"
    [System.IO.File]::WriteAllText($logPath, "OSATE $script:OsateVersion installation`nTarget: $installRoot`n", [System.Text.UTF8Encoding]::new($false))
    try {
        if (Test-Path -LiteralPath $archivePath) {
            # A verified cached archive is sufficient for an offline install;
            # never overwrite an existing cache, including a corrupted one.
            Assert-OsateArchive -Path $archivePath
        } else {
            $downloadUri = Get-OsateOfficialAsset
            Invoke-WebRequest -Uri $downloadUri -OutFile $partialPath -TimeoutSec 600 -ErrorAction Stop
            Assert-OsateArchive -Path $partialPath
            # File.Move and Directory.Move fail if the destination appeared
            # concurrently; unlike Move-Item, they cannot nest into an old tree.
            [System.IO.File]::Move($partialPath, $archivePath)
        }
        [System.IO.File]::AppendAllText($logPath, "Verified archive SHA256: $script:OsateExpectedHash`n")
        $packageRoot = Expand-OsatePackage -ArchivePath $archivePath -StagingRoot $stagingRoot
        # Recheck the resolved source and destination immediately before moving
        # directories. No existing installation is deleted or replaced.
        $resolvedPackage = [System.IO.Path]::GetFullPath($packageRoot)
        $resolvedStage = [System.IO.Path]::GetFullPath($stagingRoot)
        if ($resolvedPackage -ne $resolvedStage -and -not $resolvedPackage.StartsWith(
            $resolvedStage + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw 'Refusing to activate a package outside the installation staging directory.'
        }
        if (-not ([System.IO.Path]::GetFullPath($installRoot)).StartsWith(
            $toolsRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar,
            [System.StringComparison]::OrdinalIgnoreCase)) {
            throw 'Refusing to activate a package outside the selected tools directory.'
        }
        Assert-OsatePackage -Root $resolvedPackage | Out-Null
        [System.IO.Directory]::Move($resolvedPackage, $installRoot)
        $launcher = Join-Path $installRoot 'bin/osate-cli.bat'
        [System.IO.File]::AppendAllText($logPath, "Startup version verified; installed: $launcher`n")
        return [pscustomobject]@{ status = 'installed'; path = $launcher; version = $script:OsateVersion
            archive_sha256 = $script:OsateExpectedHash; log = $logPath }
    } catch {
        [System.IO.File]::AppendAllText($logPath, "FAILED: $($_.Exception.Message)`nPartial: $partialPath`nStaging: $stagingRoot`n")
        throw "OSATE installation failed: $($_.Exception.Message) Diagnostic log: $logPath. Download and staging files were preserved; no existing installation was overwritten."
    }
}

# Dot-sourcing exposes the helper functions without starting installation.
if ($MyInvocation.InvocationName -ne '.') {
    Invoke-OsateInstallation -ToolsDirectory $ToolsDirectory
}
