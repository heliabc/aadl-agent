param(
    [ValidateSet('Deploy', 'Rules', 'Status', 'Start', 'Stop', 'Logs')]
    [string]$Action = 'Deploy',
    [string]$HostAlias = 'qwen-server',
    [string]$RemoteRoot = '/home/wdd/aadl-single-agent',
    [string]$ModelPath = '/home/wdd/qwen3/models/Qwen3-14B',
    [string]$BasePython = '/home/wdd/miniforge3/envs/qwen-vllm/bin/python',
    [string]$RulesPath,
    [ValidateRange(1024, 65535)][int]$Port = 8000
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$projectRoot = Split-Path -Parent $PSScriptRoot
$python = Join-Path $projectRoot '.venv/Scripts/python.exe'
$ssh = 'C:/Windows/System32/OpenSSH/ssh.exe'
$scp = 'C:/Windows/System32/OpenSSH/scp.exe'
# These values become shell arguments on Linux; accept paths, not shell syntax.
foreach ($value in @($RemoteRoot, $ModelPath, $BasePython)) {
    if ($value -notmatch '^/[A-Za-z0-9_./-]+$' -or $value.Contains('/../')) {
        throw "Unsupported remote path: $value"
    }
}
if ($HostAlias -notmatch '^[A-Za-z0-9_.-]+$') { throw 'Invalid SSH alias.' }
$sshOptions = @('-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes', '-o', 'ConnectTimeout=15')
$unit = 'aadl-single-agent.service'
if ($Action -in @('Status', 'Start', 'Stop', 'Logs')) {
    $command = switch ($Action) {
        'Status' { "systemctl --user status $unit --no-pager" }
        'Start'  { "systemctl --user start $unit" }
        'Stop'   { "systemctl --user stop $unit" }
        'Logs'   { "journalctl --user -u $unit -n 100 --no-pager" }
    }
    & $ssh @sshOptions $HostAlias $command
    if ($LASTEXITCODE -ne 0) { throw "$Action failed with exit code $LASTEXITCODE." }
    exit 0
}
if (-not (Test-Path -LiteralPath $python)) { throw 'Install the project .venv first.' }
if (-not $RulesPath) { $RulesPath = Join-Path $projectRoot 'resources/aadl' }
$releaseId = (Get-Date -AsUTC -Format 'yyyyMMddTHHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$archive = Join-Path $projectRoot ".runs/deploy/$releaseId.tar.gz"
& $python (Join-Path $PSScriptRoot 'deploy_support.py') pack --project $projectRoot --rules $RulesPath --archive $archive --action $Action
if ($LASTEXITCODE -ne 0) { throw 'Local rule validation/packaging failed.' }
& $ssh @sshOptions $HostAlias "mkdir -p $RemoteRoot/uploads"
if ($LASTEXITCODE -ne 0) { throw 'Cannot create the project upload directory.' }
& $scp @sshOptions $archive (Join-Path $PSScriptRoot 'deploy_support.py') ($HostAlias + ':' + $RemoteRoot + '/uploads/')
if ($LASTEXITCODE -ne 0) { throw 'Upload failed.' }
# The helper only manages this project's release paths and named unit.
& $ssh @sshOptions $HostAlias "$BasePython $RemoteRoot/uploads/deploy_support.py install --root $RemoteRoot --archive $RemoteRoot/uploads/$releaseId.tar.gz --base-python $BasePython --model $ModelPath --port $Port"
if ($LASTEXITCODE -ne 0) { throw 'Server deployment or health verification failed; inspect the service log.' }
