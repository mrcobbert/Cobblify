#
# Tests fresh-win.ps1 against a fake profile in a temp folder. Nothing outside the
# temp folder is read or written: the process list, the registry and every move
# failure are stubbed through FRESH_* overrides.
#
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File launcher\tools\test-fresh-win.ps1
# Exit:  0 = every case passed

Set-StrictMode -Version 2
$ErrorActionPreference = 'Stop'

$tool = Join-Path $PSScriptRoot 'fresh-win.ps1'
$T = Join-Path ([IO.Path]::GetTempPath()) ('fresh-win-test-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
[void][IO.Directory]::CreateDirectory($T)
$HOME_ = Join-Path $T 'home'
$A_ = Join-Path $HOME_ 'AppData\Roaming'
$L_ = Join-Path $HOME_ 'AppData\Local'
$D_ = Join-Path $HOME_ 'Desktop'
$SM_ = Join-Path $A_ 'Microsoft\Windows\Start Menu\Programs'
$OUT = Join-Path $T 'out.txt'
$PROCS = Join-Path $T 'procs.txt'
$TAB = "`t"
$UTF8 = New-Object System.Text.UTF8Encoding $false
$script:pass = 0
$script:failed = 0

function Ok([string]$m) { $script:pass++; Write-Output "  ok    $m" }
function Bad([string]$m) { $script:failed++; Write-Output "  FAIL  $m" }
function Show { if (Test-Path -LiteralPath $OUT) { Get-Content -LiteralPath $OUT | ForEach-Object { Write-Output "        | $_" } } }

function MkFile([string]$p, [string]$text) {
  [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($p))
  if (-not $text) { $text = $p }
  [IO.File]::WriteAllText($p, "$text`n", $UTF8)
}

function Junction([string]$link, [string]$target) {
  [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($link))
  cmd /c mklink /J "$link" "$target" | Out-Null
  if (-not (Test-Path -LiteralPath $link)) { throw "mklink /J failed for $link" }
}

function Wipe([string]$p) {
  if (-not (Test-Path -LiteralPath $p)) { return }
  # Junctions first, without following them.
  cmd /c "attrib -R -H -S /S /D `"$p\*`" >nul 2>&1"
  Get-ChildItem -LiteralPath $p -Recurse -Force -Attributes ReparsePoint -ErrorAction SilentlyContinue |
    Sort-Object { $_.FullName.Length } -Descending | ForEach-Object { cmd /c rmdir "$($_.FullName)" 2>$null }
  Remove-Item -LiteralPath $p -Recurse -Force
}

function Populate([string]$variant) {
  Wipe $HOME_
  [void][IO.Directory]::CreateDirectory($HOME_)
  MkFile "$HOME_\.lunarclient\settings\launcher.json" '{"settings":{"jvm-args":"-javaagent:C:\\x"}}'
  MkFile "$HOME_\.lunarclient\settings\launcher.json.bak-cobblify" '{"settings":{}}'
  MkFile "$HOME_\.lunarclient\jre\zulu\bin\javaw.exe" 'java'
  [void][IO.Directory]::CreateDirectory("$HOME_\.lunarclient\empty dir")
  # Non-ASCII built from code points: PowerShell 5.1 reads a script without a BOM as ANSI.
  MkFile "$HOME_\.lunarclient\$([char]0x00FC)n$([char]0x00EF)code $([char]0x4E16) name.txt" 'u'
  [IO.File]::SetAttributes("$HOME_\.lunarclient\settings\launcher.json.bak-cobblify", [IO.FileAttributes]::ReadOnly)
  if ($variant -ne 'no-weave') { MkFile "$HOME_\.weave\mods\Cobblify-Lunar-0.17.0.jar" 'jar' }
  MkFile "$HOME_\.cobblify\launcher-preferences.json" '{"use_external_overlay":true}'
  MkFile "$HOME_\.bedwarsqol\config.json"
  MkFile "$L_\Programs\Lunar Client\Lunar Client.exe" 'lunar exe'
  MkFile "$L_\Programs\Lunar Client\resources\app.asar" 'asar'
  MkFile "$A_\lunarclient\Local Storage\leveldb\x.log"
  MkFile "$L_\lunarclient-updater\installer.exe"
  if ($variant -ne 'no-weave') { MkFile "$A_\.minecraft\versions\1.8.9\1.8.9.jar" 'vanilla' }
  MkFile "$A_\.minecraft\saves\World\level.dat" 'my world'
  MkFile "$SM_\Lunar Client.lnk" 'lnk'
  MkFile "$D_\Lunar Client.lnk" 'lnk'
  MkFile "$D_\desktop.ini" 'shell'
  MkFile "$L_\Programs\PrismLauncher\prismlauncher.exe" 'prism exe'
  MkFile "$A_\PrismLauncher\prismlauncher.cfg"
  MkFile "$A_\PrismLauncher\instances\My 1.8.9\mmc-pack.json"
  MkFile "$A_\PrismLauncher\instances\My 1.8.9\instance.cfg"
  MkFile "$SM_\Prism Launcher.lnk" 'lnk'
  MkFile "$L_\Cobblify Launcher\Cobblify Launcher.exe" 'stable'
  MkFile "$L_\club.cobblify.launcher\EBWebView\Default\x"
  MkFile "$SM_\Cobblify Launcher.lnk" 'lnk'
  MkFile "$L_\Moonsworth Cache\y"
  MkFile "$L_\Google\Chrome\keep.txt"
  MkFile "$HOME_\Documents\keep me.txt"
  # Scoop's Prism: the version folder, a "current" junction to it, and a persisted folder.
  MkFile "$HOME_\scoop\apps\prismlauncher\11.1.1\prismlauncher.exe" 'scoop prism'
  MkFile "$HOME_\scoop\persist\prismlauncher\instances\S\instance.cfg"
  Junction "$HOME_\scoop\apps\prismlauncher\current" "$HOME_\scoop\apps\prismlauncher\11.1.1"
}

# Every entry under the fake profile (minus the stash): path, kind, size, attributes, link, sha256.
function Tree {
  $root = $HOME_.TrimEnd('\')
  $lines = New-Object System.Collections.Generic.List[string]
  $stack = New-Object System.Collections.Generic.Stack[string]
  $stack.Push($root)
  while ($stack.Count -gt 0) {
    $p = $stack.Pop()
    foreach ($c in [IO.Directory]::GetFileSystemEntries($p)) {
      if ($c -eq (Join-Path $root 'CobblifyStash')) { continue }
      $rel = $c.Substring($root.Length + 1)
      $a = [IO.File]::GetAttributes($c)
      $f = ''
      if ($a -band [IO.FileAttributes]::ReadOnly) { $f += 'R' }
      if ($a -band [IO.FileAttributes]::Hidden) { $f += 'H' }
      if ($a -band [IO.FileAttributes]::ReparsePoint) {
        $lines.Add(($rel, 'link', $f, ((Get-Item -LiteralPath $c -Force).Target -join '|')) -join $TAB)
      } elseif ($a -band [IO.FileAttributes]::Directory) {
        $lines.Add(($rel, 'dir', $f) -join $TAB); $stack.Push($c)
      } else {
        $h = (Get-FileHash -LiteralPath $c -Algorithm SHA256).Hash
        $lines.Add(($rel, 'file', $f, (New-Object IO.FileInfo $c).Length, $h) -join $TAB)
      }
    }
  }
  $arr = $lines.ToArray(); [Array]::Sort($arr, [StringComparer]::Ordinal)
  return ($arr -join "`n")
}

function ExpectTree([string]$before, [string]$msg) {
  $now = Tree
  if ($now -eq $before) { Ok $msg } else {
    Bad $msg
    Compare-Object ($before -split "`n") ($now -split "`n") | Select-Object -First 20 | ForEach-Object { Write-Output "        $($_.SideIndicator) $($_.InputObject)" }
  }
}

# Runs the tool in a fresh PowerShell with the fake roots; returns its exit code. Extra
# environment for this run only: a hashtable.
function Run([string[]]$argv, [hashtable]$extra) {
  $vars = @{
    FRESH_HOME = $HOME_; FRESH_APPDATA = $A_; FRESH_LOCALAPPDATA = $L_; FRESH_DESKTOP = $D_
    FRESH_PROCS = $PROCS; FRESH_NO_REGISTRY = '1'; FRESH_STAMP = ('s' + [DateTime]::Now.Ticks)
    FRESH_FAIL_DST = ''; FRESH_CRASH_DST = ''; FRESH_JOURNAL_RO_DST = ''
    # Hermetic: the machine's own Program Files, ProgramData, Scoop and Prism settings never count.
    FRESH_PROGRAMFILES = (Join-Path $T 'PF'); FRESH_PROGRAMDATA = (Join-Path $T 'PD'); FRESH_PRISM_INSTALLDIR = ''
    SCOOP = ''; SCOOP_GLOBAL = ''; PRISMLAUNCHER_DATA_DIR = ''
  }
  if ($extra) { foreach ($k in $extra.Keys) { $vars[$k] = $extra[$k] } }
  foreach ($k in $vars.Keys) { [Environment]::SetEnvironmentVariable($k, $vars[$k]) }
  $psi = New-Object Diagnostics.ProcessStartInfo
  $psi.FileName = (Get-Command powershell.exe).Source
  $quoted = @($argv | ForEach-Object { '"' + $_ + '"' }) -join ' '
  $psi.Arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$tool`" $quoted"
  $psi.UseShellExecute = $false
  $psi.RedirectStandardOutput = $true
  $psi.RedirectStandardError = $true
  $psi.RedirectStandardInput = $true
  $p = [Diagnostics.Process]::Start($psi)
  $p.StandardInput.Close()
  $so = $p.StandardOutput.ReadToEndAsync(); $se = $p.StandardError.ReadToEndAsync()
  $p.WaitForExit()
  [IO.File]::WriteAllText($OUT, $so.Result + $se.Result, $UTF8)
  foreach ($k in $vars.Keys) { [Environment]::SetEnvironmentVariable($k, $null) }
  return $p.ExitCode
}

function OutHas([string]$re) { return [bool]((Get-Content -LiteralPath $OUT -Raw) -match $re) }
function Stash {
  $root = Join-Path $HOME_ 'CobblifyStash'
  if (-not (Test-Path -LiteralPath $root)) { return $null }
  $d = @(Get-ChildItem -LiteralPath $root -Directory | Sort-Object Name | Select-Object -Last 1)
  if ($d.Count -eq 0) { return $null }
  return $d[0].FullName
}
function StateOf {
  if (-not (Stash)) { return 'none' }
  $f = Join-Path (Stash) 'state'
  if (-not [IO.File]::Exists($f)) { return 'none' }
  return ([IO.File]::ReadAllText($f)).Trim()
}
function ClearProcs { [IO.File]::WriteAllText($PROCS, '', $UTF8) }
function E([string]$p) { return [regex]::Escape($p) }

function SimulateTesting {
  MkFile "$HOME_\.lunarclient\settings\launcher.json" 'fresh lunar'
  MkFile "$HOME_\.weave\mods\Cobblify-Lunar-0.17.0.jar" 'next jar'
  MkFile "$HOME_\.cobblify\launcher-last-seen" '0.18.0-next.11'
  MkFile "$A_\.minecraft\versions\1.8.9\1.8.9.jar" 'downloaded'
  MkFile "$L_\club.cobblify.launcher.next\EBWebView\x"
  MkFile "$L_\Programs\Lunar Client\Lunar Client.exe" 'fresh install'
  MkFile "$SM_\Lunar Client.lnk" 'fresh lnk'
  MkFile "$L_\Programs\New Prism Folder Lunar\z"
  MkFile "$L_\Lunar Client Cache\Cache\c"
}

try {
  ClearProcs

  Write-Output '== status is read-only =='
  Populate; $before = Tree
  $rc = Run @('status')
  if ($rc -eq 0 -and (OutHas 'present\s+\[lunar\]')) { Ok 'status lists present paths' } else { Bad "status output (exit $rc)"; Show }
  ExpectTree $before 'status changes nothing'
  if (-not (Test-Path -LiteralPath "$HOME_\CobblifyStash")) { Ok 'status makes no stash' } else { Bad 'status made a stash' }

  Write-Output '== aside all, test, restore: exact round trip =='
  Populate; $before = Tree
  $rc = Run @('aside', 'all', '--yes')
  if ($rc -eq 0) { Ok 'aside all succeeds' } else { Bad "aside all exit $rc"; Show }
  foreach ($p in @('.lunarclient', '.weave', '.cobblify', '.bedwarsqol', 'AppData\Roaming\PrismLauncher', 'AppData\Local\Programs\Lunar Client',
                   'AppData\Local\Cobblify Launcher', 'AppData\Local\club.cobblify.launcher', 'AppData\Local\Moonsworth Cache',
                   'AppData\Roaming\.minecraft\versions\1.8.9', 'scoop\apps\prismlauncher', 'Desktop\Lunar Client.lnk')) {
    if (Test-Path -LiteralPath (Join-Path $HOME_ $p)) { Bad "still present after aside: $p" }
  }
  if ((Test-Path -LiteralPath "$HOME_\Documents\keep me.txt") -and (Test-Path -LiteralPath "$A_\.minecraft\saves\World\level.dat") -and (Test-Path -LiteralPath "$L_\Google\Chrome\keep.txt") -and (Test-Path -LiteralPath "$D_\desktop.ini")) {
    Ok 'unrelated files untouched (other .minecraft content too)'
  } else { Bad 'unrelated files moved' }
  if ((StateOf) -eq 'active') { Ok 'stash is active' } else { Bad 'stash state' }
  $junction = Join-Path (Stash) 'items\home\scoop\apps\prismlauncher\current'
  if (([IO.File]::GetAttributes($junction) -band [IO.FileAttributes]::ReparsePoint) -ne 0) { Ok 'a junction moves as a junction' } else { Bad 'junction was not kept' }
  SimulateTesting
  $rc = Run @('restore', '--yes')
  if ($rc -eq 0 -and (OutHas 'Restored\.')) { Ok 'restore succeeds' } else { Bad "restore exit $rc"; Show }
  ExpectTree $before 'every original path is identical after restore'
  $s = Stash
  if ((Test-Path -LiteralPath "$s\new\1\home\.cobblify\launcher-last-seen") -and (Test-Path -LiteralPath "$s\new\1\home\AppData\Local\club.cobblify.launcher.next") -and
      (Test-Path -LiteralPath "$s\new\1\home\AppData\Local\Lunar Client Cache") -and (Test-Path -LiteralPath "$s\new\1\home\AppData\Local\Programs\New Prism Folder Lunar")) {
    Ok 'test-created items are under new\'
  } else { Bad 'new\ contents'; Get-ChildItem -LiteralPath "$s\new" -Recurse -Depth 4 | ForEach-Object { Write-Output "        $($_.FullName)" } }
  if ((StateOf) -eq 'restored') { Ok 'stash marked restored' } else { Bad 'stash state after restore' }

  Write-Output '== paths absent before testing are swept =='
  Populate 'no-weave'; $before = Tree
  if ((Run @('aside', 'all', '--yes')) -ne 0) { Bad 'aside (no-weave) failed'; Show }
  $inv = [IO.File]::ReadAllText((Join-Path (Stash) 'inventory.tsv'))
  if ($inv -match ("absent" + $TAB + (E "$HOME_\.weave"))) { Ok 'absent paths recorded' } else { Bad 'absent not recorded' }
  SimulateTesting
  if ((Run @('restore', '--yes')) -ne 0) { Bad 'restore (no-weave) failed'; Show }
  ExpectTree $before 'absent-before paths do not survive restore'
  if ((Test-Path -LiteralPath "$(Stash)\new\1\home\.weave") -and (Test-Path -LiteralPath "$(Stash)\new\1\home\AppData\Roaming\.minecraft\versions\1.8.9")) {
    Ok 'a created .weave and the downloaded 1.8.9 jar are under new\'
  } else { Bad 'absent-before items not in new\' }
  if (-not (Test-Path -LiteralPath "$A_\.minecraft\versions")) { Ok 'the folder testing created for the jar is removed' } else { Bad 'empty versions folder left behind' }

  Write-Output '== --data-only keeps the programs =='
  Populate; $before = Tree
  if ((Run @('aside', 'lunar', '--data-only', '--yes')) -ne 0) { Bad 'aside --data-only failed'; Show }
  if ((Test-Path -LiteralPath "$L_\Programs\Lunar Client\Lunar Client.exe") -and (Test-Path -LiteralPath "$SM_\Lunar Client.lnk") -and -not (Test-Path -LiteralPath "$HOME_\.lunarclient")) {
    Ok 'Lunar program and shortcut kept, data moved'
  } else { Bad 'data-only layout' }
  if ((Test-Path -LiteralPath "$A_\PrismLauncher") -and (Test-Path -LiteralPath "$L_\Programs\PrismLauncher")) { Ok 'prism untouched in lunar mode' } else { Bad 'prism moved in lunar mode' }
  if (-not (Test-Path -LiteralPath "$HOME_\.cobblify")) { Ok "Cobblify's own state moves in every mode" } else { Bad '.cobblify stayed' }
  if ((Run @('restore', '--yes')) -ne 0) { Bad 'restore after data-only failed'; Show }
  ExpectTree $before 'data-only round trip'

  Write-Output '== refusals =='
  Populate; $before = Tree
  [IO.File]::WriteAllText($PROCS, "$L_\Programs\Lunar Client\Lunar Client.exe`n", $UTF8)
  if ((Run @('aside', 'all', '--yes')) -ne 0 -and (OutHas 'close these first')) { Ok 'aside refuses while Lunar runs' } else { Bad 'aside ran while Lunar runs'; Show }
  if (OutHas 'system tray') { Ok 'the refusal points at the tray' } else { Bad 'no tray hint' }
  ExpectTree $before 'refused aside moved nothing'
  [IO.File]::WriteAllText($PROCS, "$HOME_\.lunarclient\jre\zulu\bin\javaw.exe`n", $UTF8)
  if ((Run @('aside', 'all', '--yes')) -ne 0) { Ok 'aside refuses while the game JVM runs' } else { Bad 'aside ran while the game JVM runs' }
  [IO.File]::WriteAllText($PROCS, "C:\Somewhere\Cobblify Launcher (Next).exe`n", $UTF8)
  if ((Run @('aside', 'all', '--yes')) -ne 0) { Ok 'aside refuses while a Cobblify launcher runs' } else { Bad 'aside ran while Next runs' }
  [IO.File]::WriteAllText($PROCS, "C:\Tools\scoop\apps\prismlauncher\current\PrismLauncher.EXE`n", $UTF8)
  if ((Run @('aside', 'all', '--yes')) -ne 0) { Ok 'aside refuses while Prism runs (any folder, any case)' } else { Bad 'aside ran while Prism runs' }
  ClearProcs
  if ((Run @('restore', '--yes')) -ne 0) { Ok 'restore refuses with no active stash' } else { Bad 'restore without a stash' }
  if ((Run @('aside', 'all', '--yes')) -ne 0) { Bad 'aside failed'; Show }
  if ((Run @('aside', 'lunar', '--yes')) -ne 0) { Ok 'a second aside is refused' } else { Bad 'second aside allowed' }
  [IO.File]::WriteAllText($PROCS, "C:\Users\Public\CobblifyNext\0.18.0-next.11\Cobblify Launcher (Next).exe`n", $UTF8)
  if ((Run @('restore', '--yes')) -ne 0) { Ok 'restore refuses while Next runs' } else { Bad 'restore ran while Next runs' }
  ClearProcs
  if ((Run @('restore', '--yes')) -ne 0) { Bad 'restore failed'; Show }
  ExpectTree $before 'round trip after refusals'
  if ((Run @('aside', 'bogus', '--yes')) -eq 1) { Ok 'an unknown mode is refused' } else { Bad 'unknown mode accepted' }

  Write-Output '== no process command line is ever read =='
  $src = @(Get-Content -LiteralPath $tool | Where-Object { $_ -notmatch '^\s*#' }) -join "`n"
  if ($src -notmatch 'CommandLine|Win32_Process|Get-CimInstance|Get-WmiObject|wmic|StartInfo\.Arguments') { Ok 'the tool reads exe paths only' } else { Bad 'the tool can read command lines' }
  if ($src -notmatch '(?i)Remove-Item|\.Delete\(\$[a-z]+, *\$true\)|rd /s|rmdir /s|del ') { Ok 'the tool never deletes recursively' } else { Bad 'a recursive delete exists in the tool' }
  if ($src -notmatch '(?i)Set-ItemProperty|New-ItemProperty|reg add|reg delete|Remove-ItemProperty') { Ok 'the tool never writes the registry' } else { Bad 'the tool writes the registry' }

  Write-Output '== a failed aside puts everything back =='
  Populate; $before = Tree
  if ((Run @('aside', 'all', '--yes') @{ FRESH_FAIL_DST = 'items\\home\\\.lunarclient$' }) -ne 0) { Ok 'aside fails on an injected move error' } else { Bad 'aside should fail' }
  ExpectTree $before 'failed aside is fully rolled back'
  if ((StateOf) -eq 'aborted') { Ok 'stash not left active' } else { Bad 'stash state after rollback' }
  if ((Run @('aside', 'all', '--yes')) -eq 0) { Ok 'a new aside is allowed after a rollback' } else { Bad 'aside after rollback refused'; Show }
  if ((Run @('restore', '--yes')) -ne 0) { Bad 'restore failed'; Show }
  ExpectTree $before 'round trip after a rolled-back aside'

  Write-Output '== a failed rollback leaves the stash active for restore =='
  Populate; $before = Tree
  $rc = Run @('aside', 'all', '--yes') @{ FRESH_FAIL_DST = ('items\\home\\\.lunarclient$|^' + (E "$HOME_\.cobblify") + '$') }
  if ($rc -ne 0 -and (OutHas 'putting things back failed')) { Ok 'aside reports the failed rollback' } else { Bad 'rollback failure message'; Show }
  if ((StateOf) -eq 'active') { Ok 'stash stays active' } else { Bad 'stash state' }
  if ((Run @('restore', '--yes')) -ne 0) { Bad 'restore after failed rollback'; Show }
  ExpectTree $before 'restore completes a failed rollback'

  Write-Output '== restore that fails partway can be retried =='
  Populate; $before = Tree
  if ((Run @('aside', 'all', '--yes')) -ne 0) { Bad 'aside failed' }
  SimulateTesting
  if ((Run @('restore', '--yes') @{ FRESH_FAIL_DST = ('^' + (E "$HOME_\.lunarclient") + '$') }) -ne 0) { Ok 'restore fails on an injected error' } else { Bad 'restore should fail' }
  if (Test-Path -LiteralPath "$HOME_\.cobblify\launcher-preferences.json") { Ok 'earlier originals were returned before the failure' } else { Bad 'nothing returned' }
  if ((Run @('restore', '--yes')) -ne 0) { Bad 'retried restore failed'; Show }
  ExpectTree $before 'retried restore is exact'
  if (-not (Get-ChildItem -LiteralPath "$(Stash)\new" -Recurse -Filter 'launcher-preferences.json' -ErrorAction SilentlyContinue)) { Ok 'a returned original is never swept on retry' } else { Bad 'returned original was swept' }

  Write-Output '== crash between intent and done, at every op =='
  foreach ($c in @(@('stash', 'items\\home\\\.weave$'), @('sweep', 'new\\1\\home\\\.cobblify$'), @('return', ('^' + (E "$HOME_\.lunarclient") + '$')))) {
    $op = $c[0]; $pat = $c[1]
    Populate; $before = Tree
    if ($op -eq 'stash') { $rc = Run @('aside', 'all', '--yes') @{ FRESH_CRASH_DST = $pat } }
    else {
      if ((Run @('aside', 'all', '--yes')) -ne 0) { Bad 'aside failed' }
      SimulateTesting
      $rc = Run @('restore', '--yes') @{ FRESH_CRASH_DST = $pat }
    }
    $open = @{}
    foreach ($line in [IO.File]::ReadAllLines((Join-Path (Stash) 'journal.tsv'))) {
      if (-not $line) { continue }
      $f = $line.Split("`t"); if ($f[2] -eq 'intent') { $open[$f[0]] = 1 } else { $open.Remove($f[0]) }
    }
    if ($rc -eq 7 -and $open.Count -gt 0) { Ok "${op}: the crash left an intent without done" } else { Bad "${op}: crash did not interrupt a move (exit $rc)"; Show }
    if ((Run @('restore', '--yes')) -ne 0) { Bad "${op}: restore after crash failed"; Show }
    ExpectTree $before "${op}: restore after a crash is exact"
  }

  Write-Output '== a journal that stops accepting writes stops the moves =='
  Populate; $before = Tree
  $rc = Run @('aside', 'all', '--yes') @{ FRESH_JOURNAL_RO_DST = 'items\\home\\\.cobblify$' }
  if ($rc -ne 0 -and (OutHas 'cannot write the stash journal')) { Ok 'aside names the journal' } else { Bad 'journal failure message'; Show }
  if ((Test-Path -LiteralPath "$HOME_\.weave") -and (Test-Path -LiteralPath "$HOME_\.lunarclient")) { Ok 'nothing moves once the journal cannot be written' } else { Bad 'items moved without a journal record' }
  if ((StateOf) -eq 'active') { Ok 'the stash stays active for restore' } else { Bad 'stash state' }
  [IO.File]::SetAttributes((Join-Path (Stash) 'journal.tsv'), [IO.FileAttributes]::Normal)
  if ((Run @('restore', '--yes')) -ne 0) { Bad 'restore after a journal failure'; Show }
  ExpectTree $before 'restore after a journal failure is exact'

  Write-Output '== a folder that cannot be listed is refused at aside =='
  Populate
  MkFile "$HOME_\.cobblify\private\original.txt" 'original'
  $before = Tree
  $me = [Security.Principal.WindowsIdentity]::GetCurrent().Name
  icacls "$HOME_\.cobblify\private" /deny "${me}:(RD)" | Out-Null
  $rc = Run @('aside', 'all', '--yes')
  icacls "$HOME_\.cobblify\private" /remove:d "$me" | Out-Null
  if ($rc -ne 0 -and (OutHas 'could not read every file')) { Ok 'aside names the unreadable folder' } else { Bad "unreadable folder accepted (exit $rc)"; Show }
  ExpectTree $before 'nothing moved'
  $st = StateOf
  if ($st -ne 'active') { Ok 'no active stash left behind' } else { Bad 'stash left active' }

  Write-Output '== a resumed sweep still removes the folders testing created =='
  Populate 'no-weave'; $before = Tree
  if ((Run @('aside', 'all', '--yes')) -ne 0) { Bad 'aside failed' }
  SimulateTesting
  $rc = Run @('restore', '--yes') @{ FRESH_CRASH_DST = 'new\\1\\home\\AppData\\Roaming\\\.minecraft\\versions\\1\.8\.9$' }
  if ($rc -eq 7 -and (Test-Path -LiteralPath "$A_\.minecraft\versions")) { Ok 'the crash left the new versions folder behind' } else { Bad "crash did not interrupt the sweep (exit $rc)"; Show }
  if ((Run @('restore', '--yes')) -ne 0) { Bad 'retried restore failed'; Show }
  ExpectTree $before 'the retried restore removes the folder testing created'

  Write-Output '== Explorer files are ignored =='
  Populate; $before = Tree
  if ((Run @('aside', 'all', '--yes')) -ne 0) { Bad 'aside failed' }
  MkFile "$(Stash)\items\home\.lunarclient\desktop.ini" 'explorer'
  MkFile "$(Stash)\items\home\.cobblify\Thumbs.db" 'explorer'
  if ((Run @('restore', '--yes')) -eq 0) { Ok 'desktop.ini / Thumbs.db written while stashed do not fail the check' } else { Bad 'Explorer files failed the check'; Show }
  Remove-Item -LiteralPath "$HOME_\.lunarclient\desktop.ini", "$HOME_\.cobblify\Thumbs.db" -Force
  ExpectTree $before 'round trip apart from the Explorer files'

  Write-Output '== a file changed while stashed is reported =='
  Populate
  if ((Run @('aside', 'all', '--yes')) -ne 0) { Bad 'aside failed' }
  [IO.File]::WriteAllText("$(Stash)\items\home\.cobblify\launcher-preferences.json", "tampered`n")
  if ((Run @('restore', '--yes')) -ne 0) { Ok 'restore fails on a changed file' } else { Bad 'restore accepted a changed file' }
  if (OutHas 'CHANGED.*\.cobblify') { Ok 'the changed path is named' } else { Bad 'changed path not named'; Show }
  if ((StateOf) -eq 'active') { Ok 'stash stays active after a mismatch' } else { Bad 'stash state after mismatch' }

  Write-Output '== a Desktop outside the profile is never touched =='
  Populate; $before = Tree
  $otherDesk = Join-Path $T 'OtherDrive\Desktop'
  MkFile "$otherDesk\Lunar Client.lnk" 'elsewhere'
  if ((Run @('aside', 'all', '--yes') @{ FRESH_DESKTOP = $otherDesk }) -eq 0) { Ok 'aside succeeds' } else { Bad 'aside failed'; Show }
  if (Test-Path -LiteralPath "$otherDesk\Lunar Client.lnk") { Ok 'the outside shortcut stays' } else { Bad 'outside shortcut moved' }
  if ((Run @('restore', '--yes') @{ FRESH_DESKTOP = $otherDesk }) -ne 0) { Bad 'restore failed'; Show }
  ExpectTree $before 'round trip with an outside Desktop'

  Write-Output '== Prism folders outside the fixed list (plan-2 I1) =='
  Populate
  MkFile "$HOME_\Games\Prism\prismlauncher.exe" 'portable prism'
  MkFile "$HOME_\Games\Prism\portable.txt" ''
  MkFile "$HOME_\Games\Prism\accounts.json" '{}'
  MkFile "$HOME_\Games\Prism\prismlauncher.cfg" "[General]`nInstanceDir=$($HOME_.Replace('\','/'))/MC/Instances`nJavaDir=java"
  MkFile "$HOME_\MC\Instances\Mine\instance.cfg"
  MkFile "$HOME_\.cobblify\prism-location.json" ('{"exe":"' + "$HOME_\Games\Prism\prismlauncher.exe".Replace('\','\\') + '"}')
  $before = Tree
  if ((Run @('aside', 'prism', '--yes')) -eq 0) { Ok 'aside prism succeeds' } else { Bad 'aside prism failed'; Show }
  if (-not (Test-Path -LiteralPath "$HOME_\Games\Prism") -and -not (Test-Path -LiteralPath "$HOME_\MC\Instances")) {
    Ok 'the remembered portable Prism and its absolute InstanceDir are set aside'
  } else { Bad 'portable Prism or its instances stayed' }
  if (Test-Path -LiteralPath "$HOME_\.lunarclient") { Ok 'Lunar untouched in prism mode' } else { Bad 'lunar moved' }
  if ((Run @('restore', '--yes')) -ne 0) { Bad 'restore failed'; Show }
  ExpectTree $before 'round trip with a portable Prism and a custom InstanceDir'

  Populate
  $outside = Join-Path $T 'Outside\PrismData'
  MkFile "$outside\accounts.json" '{}'
  $before = Tree
  $rc = Run @('aside', 'all', '--yes') @{ PRISMLAUNCHER_DATA_DIR = $outside }
  if ($rc -ne 0 -and (OutHas 'outside your user folder')) { Ok 'a Prism data folder outside the profile stops aside' } else { Bad "outside data accepted (exit $rc)"; Show }
  ExpectTree $before 'nothing moved'
  if (-not (Stash)) { Ok 'no stash made' } else { Bad 'a stash was made' }
  if ((Run @('aside', 'lunar', '--yes') @{ PRISMLAUNCHER_DATA_DIR = $outside }) -eq 0) { Ok 'lunar mode does not care about Prism folders' } else { Bad 'lunar mode refused'; Show }
  if ((Run @('restore', '--yes')) -ne 0) { Bad 'restore failed'; Show }
  ExpectTree $before 'lunar-mode round trip'

  Write-Output '== review code-1: paths are compared resolved, every Prism the launcher knows, any Java =='
  Populate; $before = Tree
  $sneaky = Join-Path $T 'Outside2\PrismData'
  MkFile "$sneaky\accounts.json" '{}'
  $rc = Run @('aside', 'all', '--yes') @{ PRISMLAUNCHER_DATA_DIR = "$HOME_\..\Outside2\PrismData" }
  if ($rc -ne 0 -and (OutHas 'outside your user folder')) { Ok 'a .. path out of the profile is refused (B1)' } else { Bad "a .. path was accepted (exit $rc)"; Show }
  ExpectTree $before 'nothing moved'
  if (Test-Path -LiteralPath "$sneaky\accounts.json") { Ok 'the outside folder is untouched' } else { Bad 'the outside folder moved' }

  Populate
  MkFile "$HOME_\Tools\scoop\apps\prismlauncher\11.1.1\prismlauncher.exe" 'custom scoop prism'
  MkFile "$HOME_\Tools\scoop\persist\prismlauncher\accounts.json" '{"signed":"in"}'
  Junction "$HOME_\Tools\scoop\apps\prismlauncher\current" "$HOME_\Tools\scoop\apps\prismlauncher\11.1.1"
  MkFile "$HOME_\Applications\Client\prismlauncher.exe" 'registry prism'
  MkFile "$HOME_\Applications\Client\UserData\accounts.json" '{"signed":"in"}'
  $pf = Join-Path $T 'PF'
  MkFile "$pf\PrismLauncher\prismlauncher.exe" 'all-users prism'
  $before = Tree
  $extra = @{ SCOOP = "$HOME_\Tools\scoop"; FRESH_PRISM_INSTALLDIR = "$HOME_\Applications\Client" }
  if ((Run @('aside', 'prism', '--yes') $extra) -eq 0) { Ok 'aside prism succeeds (I1)' } else { Bad 'aside prism failed'; Show }
  if (-not (Test-Path -LiteralPath "$HOME_\Tools\scoop\apps\prismlauncher") -and -not (Test-Path -LiteralPath "$HOME_\Tools\scoop\persist\prismlauncher") -and -not (Test-Path -LiteralPath "$HOME_\Applications\Client")) {
    Ok 'a custom Scoop Prism and a registry-recorded Prism with UserData are set aside'
  } else { Bad 'custom Scoop or registry Prism stayed' }
  if ((Test-Path -LiteralPath "$pf\PrismLauncher\prismlauncher.exe") -and (OutHas 'left installed, outside your user folder')) { Ok 'an all-users Prism outside the profile is left installed and named' } else { Bad 'all-users Prism handling'; Show }
  if ((Run @('restore', '--yes') $extra) -ne 0) { Bad 'restore failed'; Show }
  ExpectTree $before 'round trip with Scoop elsewhere and a registry Prism'
  Remove-Item -LiteralPath "$pf\PrismLauncher" -Recurse -Force

  Populate; $before = Tree
  [IO.File]::WriteAllText($PROCS, "C:\Program Files\Eclipse Adoptium\jdk-8.0.442-hotspot\bin\javaw.exe`n", $UTF8)
  if ((Run @('aside', 'all', '--yes')) -ne 0 -and (OutHas 'javaw')) { Ok 'any running Java stops aside (I2)' } else { Bad 'a running Java was ignored'; Show }
  ClearProcs
  ExpectTree $before 'nothing moved'

  Write-Output '== review code-1 I4: a portable Prism installed while testing is swept =='
  Populate; $before = Tree
  if ((Run @('aside', 'all', '--yes')) -ne 0) { Bad 'aside failed' }
  SimulateTesting
  MkFile "$HOME_\Games\NewPrism\prismlauncher.exe" 'new portable'
  MkFile "$HOME_\Games\NewPrism\portable.txt" ''
  MkFile "$HOME_\Games\NewPrism\accounts.json" '{"signed":"in during the test"}'
  MkFile "$HOME_\Games\NewPrism\prismlauncher.cfg" "[General]`nInstanceDir=$($HOME_.Replace('\','/'))/NewInstances"
  MkFile "$HOME_\NewInstances\Cobblify 1.8.9\instance.cfg"
  MkFile "$HOME_\.cobblify\prism-location.json" ('{"exe":"' + "$HOME_\Games\NewPrism\prismlauncher.exe".Replace('\','\\') + '"}')
  if ((Run @('restore', '--yes')) -eq 0) { Ok 'restore succeeds' } else { Bad 'restore failed'; Show }
  ExpectTree $before 'the new portable Prism, its data and its instances are gone from the profile'
  if ((Test-Path -LiteralPath "$(Stash)\new\1\home\Games\NewPrism\accounts.json") -and (Test-Path -LiteralPath "$(Stash)\new\1\home\NewInstances")) { Ok '...and are under new\' } else { Bad 'new portable Prism not in new\' }

  Write-Output '== review code-1 I6: one run at a time; never two active stashes =='
  Populate; $before = Tree
  [void][IO.Directory]::CreateDirectory("$HOME_\CobblifyStash")
  $held = New-Object IO.FileStream("$HOME_\CobblifyStash\.lock", [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
  try {
    $rc = Run @('aside', 'all', '--yes')
    if ($rc -ne 0 -and (OutHas 'another fresh-win.ps1 is running')) { Ok 'a second run waits its turn' } else { Bad "concurrent aside ran (exit $rc)"; Show }
  } finally { $held.Dispose() }
  ExpectTree $before 'nothing moved'
  foreach ($n in @('a1', 'a2')) { MkFile "$HOME_\CobblifyStash\$n\state" 'active' }
  if ((Run @('restore', '--yes')) -ne 0 -and (OutHas 'more than one stash is active')) { Ok 'two active stashes are named, not guessed between' } else { Bad 'two active stashes accepted'; Show }
  Remove-Item -LiteralPath "$HOME_\CobblifyStash\a1", "$HOME_\CobblifyStash\a2" -Recurse -Force

  Write-Output "== Weave's jar is found at the home-based path, whatever %APPDATA% is =="
  Populate; $before = Tree
  $roaming = Join-Path $T 'Redirected\Roaming'
  [void][IO.Directory]::CreateDirectory($roaming)
  if ((Run @('aside', 'lunar', '--yes') @{ FRESH_APPDATA = $roaming }) -eq 0) { Ok 'aside succeeds with %APPDATA% elsewhere' } else { Bad 'aside failed'; Show }
  if (-not (Test-Path -LiteralPath "$A_\.minecraft\versions\1.8.9")) { Ok "the home-based 1.8.9 folder is set aside" } else { Bad '1.8.9 folder stayed' }
  if ((Run @('restore', '--yes') @{ FRESH_APPDATA = $roaming }) -ne 0) { Bad 'restore failed'; Show }
  ExpectTree $before 'round trip with %APPDATA% elsewhere'
} finally {
  foreach ($k in @('FRESH_HOME', 'FRESH_APPDATA', 'FRESH_LOCALAPPDATA', 'FRESH_DESKTOP', 'FRESH_PROCS', 'FRESH_NO_REGISTRY', 'FRESH_STAMP',
                   'FRESH_PROGRAMFILES', 'FRESH_PROGRAMDATA', 'FRESH_PRISM_INSTALLDIR')) {
    [Environment]::SetEnvironmentVariable($k, $null)
  }
  try { Wipe $T } catch { Write-Output "  (could not remove $T)" }
}

Write-Output ''
Write-Output "$($script:pass) passed, $($script:failed) failed"
if ($script:failed -eq 0) { exit 0 } else { exit 1 }
