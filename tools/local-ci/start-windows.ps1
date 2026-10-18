# Registration token is read from stdin, never put in a command line or file.
param([string]$Label, [string]$Run, [string]$Commit)
$ErrorActionPreference = 'Stop'
if ($Label -notmatch '^cobblify-[a-f0-9]{32}-windows$' -or $Run -notmatch '^\d+$' -or $Commit -notmatch '^[a-f0-9]{40}$') { throw 'Invalid runner identity' }
$ciRoot = 'C:\Users\agent\.local\share\cobblify-ci'
$runner = Join-Path $ciRoot runner-windows
$env:COBBLIFY_LOCAL_CI = '1'
$env:COBBLIFY_CI_CACHE = (Join-Path $ciRoot cache).Replace('\','/')
$env:Path = 'C:\Program Files\Git\bin;C:\msys64\usr\bin;' + $env:Path
$env:RUNNER_INPUT_TOKEN = [Console]::In.ReadLine()
$hook = Join-Path $ciRoot job-started.ps1
@"
if (`$env:GITHUB_RUN_ID -ne '$Run' -or `$env:GITHUB_SHA -ne '$Commit' -or `$env:GITHUB_REPOSITORY -ne 'mrcobbert/Cobblify' -or `$env:GITHUB_EVENT_NAME -ne 'workflow_dispatch' -or `$env:GITHUB_ACTOR -ne 'mrcobbert' -or `$env:GITHUB_JOB -ne 'windows') { Write-Error 'Refusing unexpected local runner job'; exit 1 }
"@ | Set-Content -Encoding UTF8 $hook
$env:ACTIONS_RUNNER_HOOK_JOB_STARTED = $hook
Push-Location $runner
try {
  if (Test-Path .runner) { throw 'Runner is already configured; inspect before reuse' }
  & .\config.cmd --unattended --ephemeral --no-default-labels --url https://github.com/mrcobbert/Cobblify --name "cobblify-windows-$Run" --labels $Label --work (Join-Path $ciRoot work)
  $env:RUNNER_INPUT_TOKEN = $null
  if ($LASTEXITCODE -ne 0) { throw 'Runner registration failed' }
  & .\run.cmd
  if ($LASTEXITCODE -ne 0) { throw 'Runner failed' }
} finally { $env:RUNNER_INPUT_TOKEN = $null; Pop-Location }
