#
# Builds "Cobblify Launcher (Next)" for Windows: a local test build of the launcher
# that can sit beside the stable one, and hands it over in C:\Users\Public. The
# Windows twin of next-build.sh; run it on the Windows build box.
#
# What makes it safe to open (see .ai research BUILD-PLAN "Release safety"):
#   - Its own identity (club.cobblify.launcher.next), so its single-instance lock
#     and its webview folder (%LOCALAPPDATA%\club.cobblify.launcher.next) are its
#     own. Everything else the launcher touches is in the profile and shared with
#     the stable launcher, so only open it with your real setup set aside
#     (fresh-win.ps1 aside).
#   - No updater: built with the three compile-time updater values removed from
#     the environment, then the exe is searched for the update host.
#   - Only the launcher carries the prerelease version (0.18.0-next.N). The mod
#     jars keep their release name and version: the launcher rejects a prerelease
#     jar name (resources.rs is_release_jar_name).
#
# The jars come from a vendor dir holding a released launcher's resources: three
# jars plus its manifest.json. Every jar is checked against that manifest, and the
# Weave agent also against the hash pinned in .github\workflows\launcher-update.yml.
#
# Usage: powershell -ExecutionPolicy Bypass -File launcher\tools\next-build.ps1 <N> -Vendor <dir> [-Replace]
#   <N>       the build number: version 0.18.0-next.<N>
#   -Vendor   the vendor dir (a copy of .ai\vendor)
#   -Replace  overwrite an earlier delivery of the same version
#
# Output: C:\Users\Public\CobblifyNext\0.18.0-next.<N>\Cobblify Launcher (Next).exe
# with resources\ beside it, and C:\Users\Public\CobblifyNext\fresh-win.ps1
# (NEXT_DELIVER_ROOT overrides the root). Every account on the PC can read it.

param(
  [Parameter(Mandatory = $true, Position = 0)] [string]$N,
  [Parameter(Mandatory = $true)] [string]$Vendor,
  [switch]$Replace
)

Set-StrictMode -Version 2
$ErrorActionPreference = 'Stop'

function Die([string]$s) { [Console]::Error.WriteLine("next-build: $s"); exit 1 }
function Step([string]$s) { Write-Output "==> $s" }
function Check([string]$s) { Write-Output "  ok  $s" }

$NEXT_BASE_VERSION = '0.18.0'
$NEXT_NAME = 'Cobblify Launcher (Next)'
$NEXT_IDENTIFIER = 'club.cobblify.launcher.next'
# Hosts an updater-enabled build embeds (updater.rs: the URL is compiled in verbatim).
$UPDATE_HOSTS = @('workers.dev', 'bedwarsqol-stats')
# Always in the binary (updater.rs). Proves the search actually read it.
$ALWAYS_PRESENT = '/launcher/update-event'
$UPDATER_VARS = @('COBBLIFY_UPDATE_URL', 'COBBLIFY_UPDATE_TOKEN', 'COBBLIFY_UPDATER_PUBKEY')

if ($N -notmatch '^[1-9][0-9]*$') { Die 'usage: next-build.ps1 <N> -Vendor <dir> (N a positive integer)' }
$version = "$NEXT_BASE_VERSION-next.$N"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$launcher = Join-Path $repoRoot 'launcher'
if (-not (Test-Path -LiteralPath $Vendor -PathType Container)) { Die "vendor dir not found: $Vendor" }
$Vendor = (Resolve-Path -LiteralPath $Vendor).Path
$deliverRoot = if ($env:NEXT_DELIVER_ROOT) { $env:NEXT_DELIVER_ROOT } else { 'C:\Users\Public\CobblifyNext' }
$exeName = "$NEXT_NAME.exe"

function Sha([string]$f) { return (Get-FileHash -LiteralPath $f -Algorithm SHA256).Hash.ToLowerInvariant() }
function Field($obj, [string]$name) {
  $p = $obj.PSObject.Properties[$name]
  if (-not $p -or -not $p.Value) { Die "manifest has no $name" }
  return [string]$p.Value
}

# 1. The vendor jars are the released ones.
Step "checking vendor jars in $Vendor"
$vm = Join-Path $Vendor 'manifest.json'
if (-not (Test-Path -LiteralPath $vm)) { Die "$vm not found" }
$manifest = Get-Content -LiteralPath $vm -Raw | ConvertFrom-Json
$modVersion = Field $manifest 'mod_version'
if ($modVersion -notmatch '^[0-9]+\.[0-9]+\.[0-9]+$') { Die "vendor mod_version '$modVersion' is not X.Y.Z; the launcher only accepts release-named jars" }
$pairs = @(@('mod_jar', 'mod_sha256'), @('agent_jar', 'agent_sha256'), @('forge_jar', 'forge_sha256'))
foreach ($pair in $pairs) {
  $name = Field $manifest $pair[0]; $want = Field $manifest $pair[1]
  if ($name -notmatch '^[0-9A-Za-z._+-]+\.jar$') { Die "jar name '$name' is not a plain .jar name" }
  $f = Join-Path $Vendor $name
  if (-not (Test-Path -LiteralPath $f)) { Die "vendor jar $name missing" }
  $got = Sha $f
  if ($got -ne $want.ToLowerInvariant()) { Die "$name sha256 $got does not match the vendor manifest ($want)" }
  Check "$name matches the vendor manifest"
}
$workflow = Join-Path $repoRoot '.github\workflows\launcher-update.yml'
$pinned = @([regex]::Matches((Get-Content -LiteralPath $workflow -Raw), 'WEAVE_AGENT_SHA256: *([0-9a-f]{64})') | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
if ($pinned.Count -eq 0) { Die 'no WEAVE_AGENT_SHA256 pinned in launcher-update.yml' }
if ($pinned.Count -ne 1) { Die 'launcher-update.yml pins more than one agent hash' }
$agentJar = Field $manifest 'agent_jar'
if ((Sha (Join-Path $Vendor $agentJar)) -ne $pinned[0]) { Die "$agentJar does not match the hash pinned in launcher-update.yml" }
Check "$agentJar matches launcher-update.yml"

# 2. Build, blank, with no updater.
Step "building $NEXT_NAME $version (updater values unset)"
$resSrc = Join-Path $launcher 'src-tauri\resources'
$dirty = @(Get-ChildItem -LiteralPath $resSrc -Recurse -Force -File | Where-Object { $_.Name -like '*.jar' -or $_.Name -eq 'manifest.json' })
if ($dirty.Count -gt 0) { Die 'launcher\src-tauri\resources holds staged jars or a manifest; the build must be blank' }
foreach ($v in $UPDATER_VARS) { [Environment]::SetEnvironmentVariable($v, $null, 'Process') }
$conf = Join-Path ([IO.Path]::GetTempPath()) "cobblify-next-$version.conf.json"
$confJson = @{ productName = $NEXT_NAME; identifier = $NEXT_IDENTIFIER; version = $version } | ConvertTo-Json -Compress
[IO.File]::WriteAllText($conf, $confJson, (New-Object System.Text.UTF8Encoding $false))
$builtExe = Join-Path $launcher 'src-tauri\target\release\cobblify-launcher.exe'
$started = Get-Date
Push-Location $launcher
try {
  & npm.cmd ci
  if ($LASTEXITCODE -ne 0) { Die 'npm ci failed' }
  & npx.cmd --no-install tauri build --no-bundle --config $conf
  if ($LASTEXITCODE -ne 0) { Die 'tauri build failed' }
} finally { Pop-Location; Remove-Item -LiteralPath $conf -ErrorAction SilentlyContinue }
if (-not (Test-Path -LiteralPath $builtExe)) { Die "no exe at $builtExe" }
if ((Get-Item -LiteralPath $builtExe).LastWriteTime -lt $started.AddSeconds(-5)) { Die "$builtExe was not rebuilt" }
Check 'built'

# 3. Stage the exe and its resources.
Step 'staging'
$stage = Join-Path $launcher "src-tauri\target\next\$version"
if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
[void](New-Item -ItemType Directory -Path (Join-Path $stage 'resources'))
Copy-Item -LiteralPath $builtExe -Destination (Join-Path $stage $exeName)
foreach ($pair in $pairs) { Copy-Item -LiteralPath (Join-Path $Vendor (Field $manifest $pair[0])) -Destination (Join-Path $stage 'resources') }
Copy-Item -LiteralPath $vm -Destination (Join-Path $stage 'resources\manifest.json')
Check 'staged'

# 4. Verify what will be handed over.
function Verify-Build([string]$dir) {
  $exe = Join-Path $dir $exeName
  if (-not (Test-Path -LiteralPath $exe)) { Die "${dir}: no $exeName" }
  # The version resource (what Explorer shows) carries the name and version; the identifier is
  # compiled into the exe's embedded config.
  $info = (Get-Item -LiteralPath $exe).VersionInfo
  if ($info.ProductName -ne $NEXT_NAME) { Die "${dir}: product name is '$($info.ProductName)', not '$NEXT_NAME'" }
  if ($info.ProductVersion -ne $version) { Die "${dir}: product version is '$($info.ProductVersion)', not '$version'" }
  $text = [Text.Encoding]::ASCII.GetString([IO.File]::ReadAllBytes($exe))
  if ($text.IndexOf($NEXT_IDENTIFIER, [StringComparison]::Ordinal) -lt 0) { Die "${dir}: the exe does not carry $NEXT_IDENTIFIER" }
  if ($text -match 'club\.cobblify\.launcher(?!\.next)') { Die "${dir}: the exe carries the stable launcher's identifier" }
  Check "identity $NEXT_IDENTIFIER, `"$NEXT_NAME`", $version"
  if ($text.IndexOf($ALWAYS_PRESENT, [StringComparison]::Ordinal) -lt 0) { Die "${dir}: the search did not find $ALWAYS_PRESENT; the update-host check would prove nothing" }
  foreach ($h in $UPDATE_HOSTS) {
    if ($text.IndexOf($h, [StringComparison]::OrdinalIgnoreCase) -ge 0) { Die "${dir}: the exe contains an update host ($h)" }
  }
  Check 'no update host in the exe'
  $res = Join-Path $dir 'resources'
  if ((Sha (Join-Path $res 'manifest.json')) -ne (Sha $vm)) { Die "${dir}: resources\manifest.json differs from the vendor manifest" }
  $jars = @(Get-ChildItem -LiteralPath $res -File -Filter '*.jar')
  if ($jars.Count -ne 3) { Die "${dir}: resources holds $($jars.Count) jar(s), expected exactly 3" }
  foreach ($pair in $pairs) {
    $f = Join-Path $res (Field $manifest $pair[0])
    if (-not (Test-Path -LiteralPath $f) -or (Sha $f) -ne (Field $manifest $pair[1]).ToLowerInvariant()) { Die "${dir}: bundled $(Field $manifest $pair[0]) differs from the vendor" }
  }
  Check 'bundled jars match the vendor manifest'
}
Step 'verifying the staged build'
Verify-Build $stage

# 5. Deliver.
Step "delivering to $deliverRoot\$version"
$dest = Join-Path $deliverRoot $version
if (Test-Path -LiteralPath $dest) {
  if (-not $Replace) { Die "$dest exists; pass -Replace to overwrite it" }
  Remove-Item -LiteralPath $dest -Recurse -Force
}
[void](New-Item -ItemType Directory -Path $dest -Force)
Copy-Item -LiteralPath (Join-Path $stage $exeName) -Destination $dest
Copy-Item -LiteralPath (Join-Path $stage 'resources') -Destination $dest -Recurse
# The reset tool goes beside it: the player's account cannot read this checkout.
Copy-Item -LiteralPath (Join-Path $launcher 'tools\fresh-win.ps1') -Destination (Join-Path $deliverRoot 'fresh-win.ps1') -Force
# Readable (and runnable) by every account on the PC: the Users group, by SID.
& icacls.exe $deliverRoot /grant '*S-1-5-32-545:(OI)(CI)RX' /T /Q | Out-Null
if ($LASTEXITCODE -ne 0) { Die "could not grant Users read access to $deliverRoot" }
Check 'readable by every account'
Step 'verifying the delivered copy'
Verify-Build $dest
if ((Sha (Join-Path $deliverRoot 'fresh-win.ps1')) -ne (Sha (Join-Path $launcher 'tools\fresh-win.ps1'))) { Die 'the delivered fresh-win.ps1 differs from the checkout' }
Check 'fresh-win.ps1 delivered'

Write-Output ''
Write-Output "Delivered: $dest\$exeName"
Write-Output "  exe sha256 $(Sha (Join-Path $dest $exeName))"
Write-Output 'Only open it with your setup set aside:'
Write-Output "  powershell -ExecutionPolicy Bypass -File $deliverRoot\fresh-win.ps1 aside all"
