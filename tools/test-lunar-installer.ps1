param([string]$Installer = (Join-Path $PSScriptRoot '..\lunar\dist\Install BedwarsQOL (Lunar).bat'))
# Exercise the actual batch bootstrap with disposable profiles. USERPROFILE is never changed.
$ErrorActionPreference = 'Stop'
$root = Join-Path ([IO.Path]::GetTempPath()) ('cobblify-installer-tests-' + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($root) | Out-Null
$source = [IO.File]::ReadAllText((Resolve-Path -LiteralPath $Installer))
$source = $source.Replace('%USERPROFILE%', '%COBBLIFY_INSTALLER_TEST_PROFILE%').Replace('$env:USERPROFILE', '$env:COBBLIFY_INSTALLER_TEST_PROFILE')
$mod = 'Cobblify-Lunar-0.18.2.jar'
$agent = 'Weave-Loader-Agent-1.3.3.jar'
$passed = 0
function Assert($value, [string]$message) { if (-not $value) { throw $message } }
function Fixture([string]$name) {
    $base = Join-Path $root $name
    $bundle = Join-Path $base 'bundle with space'
    $profile = Join-Path $base 'profile with space'
    $mods = Join-Path $profile '.weave\mods'
    [IO.Directory]::CreateDirectory($bundle) | Out-Null
    [IO.Directory]::CreateDirectory($mods) | Out-Null
    [IO.Directory]::CreateDirectory((Join-Path $profile 'Desktop')) | Out-Null
    [IO.File]::WriteAllText((Join-Path $bundle $mod), 'new mod')
    [IO.File]::WriteAllText((Join-Path $bundle $agent), 'agent')
    $script = Join-Path $bundle 'installer.bat'
    [IO.File]::WriteAllText($script, $source, [Text.Encoding]::ASCII)
    return @{ Bundle = $bundle; Profile = $profile; Mods = $mods; Script = $script }
}
function Run($fixture) {
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = $env:ComSpec
    $info.Arguments = '/d /c ""' + $fixture.Script + '""'
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.EnvironmentVariables['COBBLIFY_INSTALLER_TEST_PROFILE'] = $fixture.Profile
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $info
    [void]$process.Start()
    $process.StandardInput.WriteLine('')
    $process.StandardInput.Close()
    $out = $process.StandardOutput.ReadToEnd()
    $err = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    $result = @{ Code = $process.ExitCode; Out = $out; Err = $err }
    $process.Dispose()
    return $result
}
function Failed($result) {
    Assert ($result.Code -ne 0) ('Expected nonzero exit: ' + $result.Out + $result.Err)
    Assert (-not $result.Out.Contains('  Done.')) 'Failed installation claimed Done'
}
try {
    $f = Fixture 'clean'
    foreach ($attempt in 1..2) {
        $r = Run $f
        Assert ($r.Code -eq 0) ('Clean/rerun failed: ' + $r.Out + $r.Err)
        Assert ([IO.File]::ReadAllText((Join-Path $f.Mods $mod)) -eq 'new mod') 'Wrong mod bytes'
        Assert (Test-Path -LiteralPath (Join-Path $f.Profile 'Desktop\Launch Lunar (Cobblify).bat') -PathType Leaf) 'Desktop launcher missing'
    }
    $passed++; Write-Host 'PASS clean install and rerun'

    $f = Fixture 'desktop-source'
    $desktopBundle = Join-Path $f.Profile 'Desktop'
    [IO.File]::Copy((Join-Path $f.Bundle $mod), (Join-Path $desktopBundle $mod))
    [IO.File]::Copy((Join-Path $f.Bundle $agent), (Join-Path $desktopBundle $agent))
    $f.Bundle = $desktopBundle
    $f.Script = Join-Path $desktopBundle 'installer.bat'
    [IO.File]::WriteAllText($f.Script, $source, [Text.Encoding]::ASCII)
    $r = Run $f
    Assert ($r.Code -eq 0) ('Desktop-source install failed: ' + $r.Out + $r.Err)
    Assert ([IO.File]::ReadAllText((Join-Path $f.Mods $mod)) -eq 'new mod') 'Desktop-source mod missing'
    $passed++; Write-Host 'PASS installing from Desktop uses one launcher destination'

    $f = Fixture 'upgrade'
    [IO.File]::WriteAllText((Join-Path $f.Mods 'Cobblify-Lunar-0.18.1.jar'), 'old mod')
    [IO.File]::WriteAllText((Join-Path $f.Mods 'Other-Mod.jar'), 'unrelated')
    $r = Run $f
    Assert ($r.Code -eq 0) ('Upgrade failed: ' + $r.Out + $r.Err)
    Assert (@(Get-ChildItem -LiteralPath $f.Mods -Filter 'Cobblify-Lunar-*.jar').Count -eq 1) 'Upgrade left multiple mod releases'
    Assert ([IO.File]::ReadAllText((Join-Path $f.Mods 'Other-Mod.jar')) -eq 'unrelated') 'Other mod changed'
    $backups = @(Get-ChildItem -LiteralPath (Join-Path $f.Profile '.weave\cobblify-backups') -Recurse -File)
    Assert (@($backups | Where-Object { [IO.File]::ReadAllText($_.FullName) -eq 'old mod' }).Count -gt 0) 'Old release not backed up'
    $passed++; Write-Host 'PASS upgrade backs up old jar and preserves other mods'

    foreach ($name in @('Cobblify-Lunar-0.19.0.jar', 'Cobblify-Lunar-0.18.1-dev.4.jar', 'cobblify-lunar-custom.jar')) {
        $f = Fixture $name
        $peer = Join-Path $f.Mods $name
        [IO.File]::WriteAllText($peer, 'keep this')
        Failed (Run $f)
        Assert ([IO.File]::ReadAllText($peer) -eq 'keep this') 'Conflict changed'
        Assert (-not (Test-Path -LiteralPath (Join-Path $f.Mods $mod))) 'Installed beside conflict'
    }
    $passed++; Write-Host 'PASS newer, dev and renamed conflicts preserved'

    $f = Fixture 'copy-failure'
    $old = Join-Path $f.Mods 'Cobblify-Lunar-0.18.1.jar'
    [IO.File]::WriteAllText($old, 'old mod')
    $lock = [IO.File]::Open((Join-Path $f.Bundle $mod), 'Open', 'ReadWrite', 'None')
    try { Failed (Run $f) } finally { $lock.Dispose() }
    Assert ([IO.File]::ReadAllText($old) -eq 'old mod') 'Copy failure changed prior install'
    $passed++; Write-Host 'PASS failed source copy reports failure and preserves old jar'

    $f = Fixture 'backup-failure'
    $first = Join-Path $f.Mods 'Cobblify-Lunar-0.18.0.jar'
    $second = Join-Path $f.Mods 'Cobblify-Lunar-0.18.1.jar'
    [IO.File]::WriteAllText($first, 'first')
    [IO.File]::WriteAllText($second, 'second')
    $lock = [IO.File]::Open($second, 'Open', 'ReadWrite', 'None')
    try { Failed (Run $f) } finally { $lock.Dispose() }
    Assert ([IO.File]::ReadAllText($first) -eq 'first') 'Earlier backup not restored'
    Assert ([IO.File]::ReadAllText($second) -eq 'second') 'Locked release changed'
    Assert (-not (Test-Path -LiteralPath (Join-Path $f.Mods $mod))) 'Installed after failed backup'
    $passed++; Write-Host 'PASS partial backup failure rolls back'

    $f = Fixture 'junction'
    [IO.Directory]::Delete($f.Mods)
    $outside = Join-Path $root 'user-owned-mods'
    [IO.Directory]::CreateDirectory($outside) | Out-Null
    New-Item -ItemType Junction -Path $f.Mods -Target $outside | Out-Null
    try {
        Failed (Run $f)
        Assert (@(Get-ChildItem -LiteralPath $outside).Count -eq 0) 'Followed mods junction'
    } finally { [IO.Directory]::Delete($f.Mods) }
    $passed++; Write-Host 'PASS mods directory junction rejected'
    Write-Host "Passed $passed Windows installer fixture groups."
} finally {
    [IO.Directory]::Delete($root, $true)
}
