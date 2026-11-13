@echo off
setlocal
REM PowerShell is embedded so the distributed installer remains a single file.
set "COBBLIFY_INSTALL_SCRIPT=%~f0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$text = [IO.File]::ReadAllText($env:COBBLIFY_INSTALL_SCRIPT); $body = ([regex]'(?m)^# COBBLIFY POWERSHELL\r?$').Split($text, 2)[1]; & ([scriptblock]::Create($body))"
set "COBBLIFY_INSTALL_RESULT=%ERRORLEVEL%"
pause
exit /b %COBBLIFY_INSTALL_RESULT%
# COBBLIFY POWERSHELL
# Cobblify - one-click installer for Lunar Client (Windows, PowerShell 5.1).
# Old plain releases are backed up outside Weave's mods directory before an upgrade.
Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$bundle = [IO.Path]::GetDirectoryName($env:COBBLIFY_INSTALL_SCRIPT)
$weave = Join-Path $env:USERPROFILE '.weave'
$mods = Join-Path $weave 'mods'
$backups = Join-Path $weave 'cobblify-backups'
$desktop = Join-Path $env:USERPROFILE 'Desktop'
$launcher = 'Launch Lunar (Cobblify).bat'
$stage = $null; $backup = $null; $committed = $false; $result = 1
$saved = New-Object Collections.ArrayList
$installed = New-Object Collections.ArrayList

function Plain-Path([string]$Path, [bool]$Directory = $false) {
    try { $item = Get-Item -LiteralPath $Path -Force }
    catch [System.Management.Automation.ItemNotFoundException] { return }
    if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "Cannot install through a linked file or folder: $Path"
    }
    if ([bool]$item.PSIsContainer -ne $Directory) { throw "Unexpected file or folder: $Path" }
}

function Superseded-Release([string]$Name, [string]$Ours) {
    if ($Name -notmatch '^Cobblify-Lunar-([0-9]+)\.([0-9]+)\.([0-9]+)\.jar$') { return $false }
    $theirs = @($Matches[1], $Matches[2], $Matches[3])
    if ($Ours -notmatch '^Cobblify-Lunar-([0-9]+)\.([0-9]+)\.([0-9]+)\.jar$') { return $false }
    $oursParts = @($Matches[1], $Matches[2], $Matches[3])
    # Decimal strings avoid overflow and accept leading zeroes consistently with macOS.
    for ($i = 0; $i -lt 3; $i++) {
        $a = $theirs[$i].TrimStart('0'); $b = $oursParts[$i].TrimStart('0')
        if ($a.Length -lt $b.Length) { return $true }
        if ($a.Length -gt $b.Length) { return $false }
        $order = [string]::CompareOrdinal($a, $b)
        if ($order -lt 0) { return $true }
        if ($order -gt 0) { return $false }
    }
    return $true
}

function Stage-File([string]$Source, [string]$Destination) {
    [IO.File]::Copy($Source, $Destination, $false)
    if ((Get-FileHash -LiteralPath $Source -Algorithm SHA256).Hash -ne
        (Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash) {
        throw 'Could not verify the staged copy.'
    }
}

function Save-File([string]$Path, [string]$Name) {
    Plain-Path $Path
    if (-not [IO.File]::Exists($Path)) { return }
    $destination = Join-Path $backup $Name
    [IO.File]::Move($Path, $destination)
    [void]$saved.Add(@{ From = $destination; To = $Path })
}

function Install-File([string]$Source, [string]$Destination) {
    [IO.File]::Move($Source, $Destination)
    [void]$installed.Add($Destination)
}

try {
    Write-Host ''
    Write-Host '  Installing Cobblify for Lunar Client...'
    $agents = @(Get-ChildItem -LiteralPath $bundle -Filter 'Weave-Loader-Agent-*.jar' -Force)
    $modJars = @(Get-ChildItem -LiteralPath $bundle -Filter 'Cobblify-Lunar-*.jar' -Force)
    if ($agents.Count -ne 1 -or $modJars.Count -ne 1) {
        throw 'Expected exactly one Weave-Loader-Agent jar and one Cobblify-Lunar jar beside this installer. Extract the bundle into a fresh folder.'
    }
    $agent = $agents[0].Name; $mod = $modJars[0].Name
    if ($agent -notmatch '^Weave-Loader-Agent-[A-Za-z0-9._-]+\.jar$') { throw 'Unexpected agent filename.' }
    if ($mod -notmatch '^Cobblify-Lunar-[0-9]+\.[0-9]+\.[0-9]+\.jar$') { throw 'Expected a plain Cobblify release filename.' }
    Plain-Path $agents[0].FullName; Plain-Path $modJars[0].FullName
    Plain-Path $weave $true; Plain-Path $mods $true; Plain-Path $backups $true; Plain-Path $desktop $true
    if (-not [IO.Directory]::Exists($desktop)) { throw 'Desktop folder was not found.' }
    $bundleLauncher = [IO.Path]::GetFullPath((Join-Path $bundle $launcher))
    $desktopLauncher = [IO.Path]::GetFullPath((Join-Path $desktop $launcher))
    $separateLaunchers = -not [string]::Equals($bundleLauncher, $desktopLauncher, [StringComparison]::OrdinalIgnoreCase)
    Plain-Path (Join-Path $weave $agent)
    Plain-Path (Join-Path $bundle $launcher); Plain-Path (Join-Path $desktop $launcher)
    $peers = @()
    if ([IO.Directory]::Exists($mods)) {
        $peers = @(Get-ChildItem -LiteralPath $mods -Filter 'Cobblify-Lunar-*.jar' -Force | Sort-Object Name)
    }
    foreach ($peer in $peers) {
        Plain-Path $peer.FullName
        if (-not (Superseded-Release $peer.Name $mod)) {
            throw "Conflicting Cobblify jar: $($peer.Name). Move it out of Weave's mods folder before installing."
        }
    }
    [IO.Directory]::CreateDirectory($mods) | Out-Null
    [IO.Directory]::CreateDirectory($backups) | Out-Null
    $stage = Join-Path $weave ('.cobblify-install-' + [Guid]::NewGuid().ToString('N'))
    [IO.Directory]::CreateDirectory($stage) | Out-Null
    Stage-File $agents[0].FullName (Join-Path $stage 'agent.jar')
    Stage-File $modJars[0].FullName (Join-Path $stage 'mod.jar')
    $launcherText = @'
@echo off
REM Launch the official Lunar Client with Cobblify (Weave) injected.
REM Fully quit Lunar first, then double-click this file.
if not exist "%USERPROFILE%\.weave\__COBBLIFY_AGENT__" goto :noagent
if not exist "%LOCALAPPDATA%\Programs\lunarclient\Lunar Client.exe" goto :nolunar
echo If Lunar is already open, fully quit it first, then run this again.
set JAVA_TOOL_OPTIONS=-javaagent:"%USERPROFILE%\.weave\__COBBLIFY_AGENT__"
start "" "%LOCALAPPDATA%\Programs\lunarclient\Lunar Client.exe"
exit /b 0
:noagent
echo ERROR: Weave agent not found. Run the Cobblify installer again.
pause
exit /b 1
:nolunar
echo ERROR: Lunar Client not found. Install Lunar Client first, then run this again.
pause
exit /b 1
'@
    $launcherText = $launcherText.Replace('__COBBLIFY_AGENT__', $agent).Replace("`r`n", "`n").Replace("`n", "`r`n") + "`r`n"
    [IO.File]::WriteAllText((Join-Path $stage 'launcher'), $launcherText, [Text.Encoding]::ASCII)
    Stage-File (Join-Path $stage 'launcher') (Join-Path $stage 'desktop-launcher')
    $backup = Join-Path $backups ('install-' + [Guid]::NewGuid().ToString('N'))
    [IO.Directory]::CreateDirectory($backup) | Out-Null
    foreach ($peer in $peers) { Save-File $peer.FullName ('mod-' + $peer.Name) }
    Save-File (Join-Path $weave $agent) ('agent-' + $agent)
    Save-File $bundleLauncher 'bundle-launcher'
    if ($separateLaunchers) { Save-File $desktopLauncher 'desktop-launcher' }
    Install-File (Join-Path $stage 'agent.jar') (Join-Path $weave $agent)
    Install-File (Join-Path $stage 'mod.jar') (Join-Path $mods $mod)
    Install-File (Join-Path $stage 'launcher') $bundleLauncher
    if ($separateLaunchers) {
        # A short-name alias can still address the launcher just installed above.
        if ([IO.File]::Exists($desktopLauncher)) {
            Plain-Path $desktopLauncher
            if ((Get-FileHash -LiteralPath $desktopLauncher -Algorithm SHA256).Hash -ne
                (Get-FileHash -LiteralPath (Join-Path $stage 'desktop-launcher') -Algorithm SHA256).Hash) {
                throw 'The Desktop launcher changed during installation.'
            }
        } else { Install-File (Join-Path $stage 'desktop-launcher') $desktopLauncher }
    }
    $committed = $true; $result = 0
    Write-Host '  Done.'
    if ($saved.Count -gt 0) { Write-Host "  Previous files saved in: $backup" }
    Write-Host ''
    Write-Host "  A launcher named '$launcher' was placed next to this installer AND on your Desktop."
    Write-Host '  Use it every time you play - it starts Lunar with Cobblify loaded.'
    Write-Host ''
    Write-Host '  Remaining steps (in Lunar, after launching):'
    Write-Host '   1. Log into Lunar Client.'
    Write-Host '   2. Pick version 1.8.9 and click Play.'
    Write-Host '   3. Turn Waypoints OFF inside your active Lunar settings profile.'
    Write-Host '   4. Press Right Shift in-game to open the Cobblify settings menu.'
} catch {
    Write-Host "  ERROR: $($_.Exception.Message)"
} finally {
    if (-not $committed) {
        for ($i = $installed.Count - 1; $i -ge 0; $i--) {
            try { [IO.File]::Delete($installed[$i]) }
            catch { Write-Host "  Could not remove incomplete file: $($installed[$i])" }
        }
        for ($i = $saved.Count - 1; $i -ge 0; $i--) {
            try { [IO.File]::Move($saved[$i].From, $saved[$i].To) }
            catch { Write-Host "  Previous file remains in: $($saved[$i].From)" }
        }
    }
    if ($stage -and [IO.Directory]::Exists($stage)) {
        try { [IO.Directory]::Delete($stage, $true) } catch { }
    }
    if ($backup -and [IO.Directory]::Exists($backup)) {
        try { [IO.Directory]::Delete($backup, $false) } catch { }
    }
}
exit $result
