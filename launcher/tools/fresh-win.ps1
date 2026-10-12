#
# Sets this PC's Lunar / Prism / Cobblify state aside so a launcher test build
# meets a brand-new player, then puts everything back exactly.
#
#   fresh-win.ps1 status
#   fresh-win.ps1 aside <lunar|prism|all> [--data-only] [--yes]
#   fresh-win.ps1 restore [--yes]
#
# Run it in your own Windows account, from PowerShell, with Lunar, Prism, their
# games and every Cobblify launcher closed:
#   powershell -ExecutionPolicy Bypass -File C:\Users\Public\CobblifyNext\fresh-win.ps1 status
# It never deletes anything:
#   - aside MOVES items into %USERPROFILE%\CobblifyStash\<timestamp>\items\;
#   - restore moves anything created while testing into <stash>\new\, moves the
#     originals back, and checks every file against what was recorded at aside.
# You delete the stash folder yourself once you are happy.
#
# Every mode also sets aside Cobblify's own state (.weave, .cobblify,
# .bedwarsqol, the installed Cobblify Launcher and its webview data, its
# shortcuts): a new player has none of it, and a test build writes there the
# moment it opens. --data-only leaves the Lunar / Prism programs installed (an
# installed but never opened client).
#
# Safety model (the same as fresh-mac.sh):
#   - inventory.tsv records every known path as present / absent / kept /
#     untouched at aside. Restore sweeps a path that was absent and now exists.
#   - content\<n>.tsv records every entry of every stashed item (path, kind, size,
#     attributes, link target, sha256). Restore verifies the returned items against
#     it. Explorer's desktop.ini and Thumbs.db are ignored. A junction or link is
#     recorded as a link and never followed.
#   - journal.tsv is append-only: an "intent" line before each move and a "done"
#     line after it. Restore first reconciles any move a crash interrupted, then
#     sweeps, then returns, so it can be re-run until it succeeds.
#
# Environment overrides exist for the test suite only (test-fresh-win.ps1):
# FRESH_HOME, FRESH_APPDATA, FRESH_LOCALAPPDATA, FRESH_DESKTOP, FRESH_PROCS,
# FRESH_NO_REGISTRY, FRESH_STAMP, FRESH_FAIL_DST, FRESH_CRASH_DST, FRESH_JOURNAL_RO_DST.

Set-StrictMode -Version 2
$ErrorActionPreference = 'Stop'

function EnvOr([string]$name, [string]$default) {
  $v = [Environment]::GetEnvironmentVariable($name)
  if ($v) { return $v } else { return $default }
}

# A path with any `..` or `.` resolved, no trailing separator; $null when it isn't a usable
# absolute path. Every containment decision below compares such paths (review code-1 B1).
function Full([string]$p) {
  if (-not $p) { return $null }
  try {
    if (-not [IO.Path]::IsPathRooted($p)) { return $null }
    $f = [IO.Path]::GetFullPath($p)
    if ($f.Length -gt 3) { $f = $f.TrimEnd('\') }
    return $f
  } catch { return $null }
}

$HomeDir = Full (EnvOr 'FRESH_HOME' $env:USERPROFILE)
$RoamingDir = Full (EnvOr 'FRESH_APPDATA' $env:APPDATA)
$LocalDir = Full (EnvOr 'FRESH_LOCALAPPDATA' $env:LOCALAPPDATA)
$DesktopDir = Full (EnvOr 'FRESH_DESKTOP' ([Environment]::GetFolderPath('Desktop')))
if (-not $DesktopDir) { $DesktopDir = Join-Path $HomeDir 'Desktop' }
$ProgramFilesDirs = @((EnvOr 'FRESH_PROGRAMFILES' (@($env:ProgramFiles, ${env:ProgramFiles(x86)}) -join ';')).Split(';') | ForEach-Object { Full $_ } | Where-Object { $_ })
$ProgramDataDir = Full (EnvOr 'FRESH_PROGRAMDATA' $env:ProgramData)
if (-not $HomeDir -or -not $RoamingDir -or -not $LocalDir) { [Console]::Error.WriteLine('fresh-win: USERPROFILE, APPDATA or LOCALAPPDATA is not a usable folder'); exit 1 }
$StartMenuDir = Join-Path $RoamingDir 'Microsoft\Windows\Start Menu\Programs'
$STASH_ROOT = Join-Path $HomeDir 'CobblifyStash'
$TAB = "`t"
$UTF8 = New-Object System.Text.UTF8Encoding $false
$script:STASH = $null
$script:yes = $false

function Say([string]$s) { [Console]::Out.WriteLine($s) }
function Warn([string]$s) { [Console]::Error.WriteLine($s) }
function Die([string]$s) { Warn "fresh-win: $s"; exit 1 }

# ---------------------------------------------------------------------------
# What exists

function SameText([string]$a, [string]$b) { return [string]::Equals($a, $b, [StringComparison]::OrdinalIgnoreCase) }

# Whether $path is $root or inside it (caseless, as NTFS is).
function IsUnder([string]$path, [string]$root) {
  $r = $root.TrimEnd('\')
  return (SameText $path $r) -or $path.StartsWith($r + '\', [StringComparison]::OrdinalIgnoreCase)
}

# group, path, app (an installed program or its shortcut: kept by --data-only).
function Inventory {
  $items = New-Object System.Collections.Generic.List[object]
  function Add([string]$g, [string]$p, [bool]$app) { $items.Add([pscustomobject]@{ Group = $g; Path = $p; App = $app }) }
  Add cobblify (Join-Path $HomeDir '.cobblify') $false
  Add cobblify (Join-Path $HomeDir '.weave') $false
  Add cobblify (Join-Path $HomeDir '.bedwarsqol') $false
  Add cobblify (Join-Path $LocalDir 'Cobblify Launcher') $false
  Add cobblify (Join-Path $LocalDir 'club.cobblify.launcher') $false
  Add cobblify (Join-Path $LocalDir 'club.cobblify.launcher.next') $false
  Add cobblify (Join-Path $StartMenuDir 'Cobblify Launcher.lnk') $false
  Add cobblify (Join-Path $DesktopDir 'Cobblify Launcher.lnk') $false
  Add lunar (Join-Path $LocalDir 'Programs\Lunar Client') $true
  Add lunar (Join-Path $LocalDir 'Programs\lunarclient') $true
  Add lunar (Join-Path $StartMenuDir 'Lunar Client.lnk') $true
  Add lunar (Join-Path $DesktopDir 'Lunar Client.lnk') $true
  Add lunar (Join-Path $HomeDir '.lunarclient') $false
  Add lunar (Join-Path $RoamingDir 'lunarclient') $false
  Add lunar (Join-Path $LocalDir 'lunarclient-updater') $false
  # Weave's vanilla 1.8.9 jar (the one Cobblify downloads), at the home-based path Weave reads, not %APPDATA%;
  # the rest of .minecraft is the player's own.
  Add lunar (Join-Path $HomeDir 'AppData\Roaming\.minecraft\versions\1.8.9') $false
  Add prism (Join-Path $LocalDir 'Programs\PrismLauncher') $true
  Add prism (Join-Path $HomeDir 'scoop\apps\prismlauncher') $true
  Add prism (Join-Path $StartMenuDir 'Prism Launcher.lnk') $true
  Add prism (Join-Path $StartMenuDir 'Scoop Apps\Prism Launcher.lnk') $true
  Add prism (Join-Path $DesktopDir 'Prism Launcher.lnk') $true
  Add prism (Join-Path $RoamingDir 'PrismLauncher') $false
  Add prism (Join-Path $LocalDir 'PrismLauncher') $false
  Add prism (Join-Path $HomeDir 'scoop\persist\prismlauncher') $false
  return $items
}

# Every Prism the launcher could find (prism.rs windows_candidates, plus the exe Cobblify
# remembered), what each one uses for data (Prism's own rule: PRISMLAUNCHER_DATA_DIR, the exe's
# UserData folder, the exe's folder beside portable.txt, else %APPDATA%\PrismLauncher) and the
# absolute InstanceDir / JavaDir in each data folder's prismlauncher.cfg - minus what the fixed
# list covers. App = a program folder holding no data (--data-only keeps it; one outside the
# profile is left installed). Data outside the profile stops aside before anything moves.
function PrismExtras {
  $found = New-Object System.Collections.Generic.List[object]
  function AddX([string]$p, [bool]$app) {
    $f = Full $p
    if ($f) { $found.Add([pscustomobject]@{ Group = 'prism'; Path = $f; App = $app }) }
  }
  $exeDirs = @()
  $dataDirs = @((Join-Path $RoamingDir 'PrismLauncher'))
  $envData = [Environment]::GetEnvironmentVariable('PRISMLAUNCHER_DATA_DIR')
  if ($envData) { AddX $envData $false; $dataDirs += $envData }
  $installDir = [Environment]::GetEnvironmentVariable('FRESH_PRISM_INSTALLDIR')
  if (-not $installDir -and -not [Environment]::GetEnvironmentVariable('FRESH_NO_REGISTRY')) {
    try { $installDir = [string](Get-ItemProperty -Path 'HKCU:\Software\PrismLauncher' -Name InstallDir -ErrorAction Stop).InstallDir } catch { }
  }
  if ($installDir) { $exeDirs += $installDir }
  $exeDirs += (Join-Path $LocalDir 'Programs\PrismLauncher')
  foreach ($pf in $ProgramFilesDirs) { $exeDirs += (Join-Path $pf 'PrismLauncher') }
  $scoops = @()
  $scoopUser = EnvOr 'SCOOP' (Join-Path $HomeDir 'scoop')
  $scoops += $scoopUser
  $scoopGlobal = EnvOr 'SCOOP_GLOBAL' $(if ($ProgramDataDir) { Join-Path $ProgramDataDir 'scoop' } else { '' })
  if ($scoopGlobal) { $scoops += $scoopGlobal }
  # Scoop: its app folder (versions + the "current" junction) and its persisted data, known by
  # the app folder alone (nothing is read through the junction).
  foreach ($sc in $scoops) {
    if ([IO.Directory]::Exists((Join-Path $sc 'apps\prismlauncher'))) {
      AddX (Join-Path $sc 'apps\prismlauncher') $false
      AddX (Join-Path $sc 'persist\prismlauncher') $false
      $dataDirs += (Join-Path $sc 'persist\prismlauncher')
    }
  }
  $record = Join-Path $HomeDir '.cobblify\prism-location.json'
  if ([IO.File]::Exists($record)) {
    try { $exe = [string](Get-Content -LiteralPath $record -Raw | ConvertFrom-Json).exe } catch { $exe = '' }
    if ($exe) { $exeDirs += [IO.Path]::GetDirectoryName($exe) }
  }
  foreach ($d in $exeDirs) {
    $dir = Full $d
    if (-not $dir -or -not [IO.File]::Exists((Join-Path $dir 'prismlauncher.exe'))) { continue }
    $userData = Join-Path $dir 'UserData'
    if ([IO.Directory]::Exists($userData)) {
      # The program, with its data in its UserData folder: --data-only keeps the one and moves
      # the other (review code-2 I1).
      AddX $dir $true
      AddX $userData $false
      $dataDirs += $userData
    } elseif ([IO.File]::Exists((Join-Path $dir 'portable.txt'))) {
      # Portable: the data is the folder itself, so it always moves whole.
      AddX $dir $false
      $dataDirs += $dir
    } else {
      AddX $dir $true
    }
  }
  foreach ($data in $dataDirs) {
    $dfull = Full $data
    if (-not $dfull) { continue }
    $cfg = Join-Path $dfull 'prismlauncher.cfg'
    if (-not [IO.File]::Exists($cfg)) { continue }
    $general = $true
    foreach ($line in [IO.File]::ReadAllLines($cfg)) {
      $t = $line.Trim()
      if ($t.StartsWith('[') -and $t.EndsWith(']')) { $general = ($t -eq '[General]'); continue }
      if (-not $general) { continue }
      if ($t -match '^(InstanceDir|JavaDir)\s*=\s*(.*)$') {
        $v = $Matches[2].Trim().Trim('"').Replace('\\', '\').Replace('/', '\')
        if ($v -match '^[A-Za-z]:\\' -or $v.StartsWith('\\')) { AddX $v $false }
      }
    }
  }
  # Drop what the fixed list (or an earlier extra) already covers: the same path, or one inside
  # a folder that moves whole. Data inside a program folder stays listed (--data-only moves it
  # out of the kept program; otherwise aside sees it inside a moved item and skips it).
  $cover = @(Inventory)
  $out = @()
  foreach ($x in $found) {
    $covered = $false
    foreach ($c in ($cover + $out)) {
      if ((SameText $x.Path $c.Path) -or ((IsUnder $x.Path $c.Path) -and -not $c.App)) { $covered = $true; break }
    }
    if (-not $covered) { $out += $x }
  }
  return $out
}

# Inside the profile (never the profile itself), by normalized path, and reached without
# passing through a junction or link: a folder below a link may really be anywhere (review
# code-2 B1). The item itself may be a link; it is moved as one.
function InProfile([string]$p) {
  if (-not (IsUnder $p $HomeDir) -or (SameText $p $HomeDir)) { return $false }
  $d = [IO.Path]::GetDirectoryName($p)
  while ($d -and -not (SameText $d $HomeDir)) {
    try {
      if ([IO.File]::GetAttributes($d) -band [IO.FileAttributes]::ReparsePoint) { return $false }
    } catch { }
    $d = [IO.Path]::GetDirectoryName($d)
  }
  return $true
}

function GroupOfName([string]$path) {
  $n = (Split-Path -Leaf $path).ToLowerInvariant()
  if ($n.Contains('lunar') -or $n.Contains('moonsworth')) { return 'lunar' }
  if ($n.Contains('prism')) { return 'prism' }
  if ($n.Contains('cobblify')) { return 'cobblify' }
  return 'other'
}

function Exists([string]$p) {
  try { [void][IO.File]::GetAttributes($p); return $true } catch { return $false }
}

function IsDirEntry([string]$p) {
  return ([IO.File]::GetAttributes($p) -band [IO.FileAttributes]::Directory) -ne 0
}

function Children([string]$dir) {
  if (-not [IO.Directory]::Exists($dir)) { return @() }
  try { return @([IO.Directory]::GetFileSystemEntries($dir)) } catch { return @() }
}

# Name discovery: anything named like Lunar / Moonsworth / Prism / Cobblify directly
# in %APPDATA%, %LOCALAPPDATA%, %LOCALAPPDATA%\Programs and the Start menu (one
# folder down there), minus what the fixed inventory covers. Windows' own folders
# (Temp, Microsoft, Packages) are never searched.
function Discover {
  $cover = @(Inventory | ForEach-Object { $_.Path })
  $found = New-Object System.Collections.Generic.List[object]
  $roots = @($RoamingDir, $LocalDir, (Join-Path $LocalDir 'Programs'), $StartMenuDir)
  foreach ($sub in (Children $StartMenuDir)) { if ([IO.Directory]::Exists($sub)) { $roots += $sub } }
  $seen = @{}
  foreach ($root in $roots) {
    foreach ($p in (Children $root)) {
      $leaf = Split-Path -Leaf $p
      if (@('Temp', 'Microsoft', 'Packages') -contains $leaf) { continue }
      $g = GroupOfName $p
      if ($g -eq 'other') { continue }
      $covered = $false
      foreach ($c in $cover) { if (IsUnder $p $c) { $covered = $true; break } }
      if ($covered) { continue }
      $k = $p.ToLowerInvariant()
      if ($seen.ContainsKey($k)) { continue }
      $seen[$k] = $true
      $found.Add([pscustomobject]@{ Group = $g; Path = $p; App = $false })
    }
  }
  return $found
}

# Related processes that must not run while files move, described by pid and
# executable path. Never a command line: a signed-in Lunar game's arguments carry
# the account's access token.
function Running {
  $dirs = @((Join-Path $LocalDir 'Programs\Lunar Client'), (Join-Path $LocalDir 'Programs\lunarclient'),
            (Join-Path $HomeDir '.lunarclient'), (Join-Path $LocalDir 'Programs\PrismLauncher'),
            (Join-Path $RoamingDir 'PrismLauncher'), (Join-Path $HomeDir 'scoop\apps\prismlauncher'),
            (Join-Path $HomeDir 'scoop\persist\prismlauncher'), (Join-Path $LocalDir 'Cobblify Launcher'))
  # Any Java program counts: Minecraft runs on whatever Java its launcher picked, from any folder.
  $names = @('Lunar Client.exe', 'prismlauncher.exe', 'cobblify-launcher.exe', 'java.exe', 'javaw.exe')
  $procs = @()
  $fake = [Environment]::GetEnvironmentVariable('FRESH_PROCS')
  if ($fake) {
    if ([IO.File]::Exists($fake)) {
      $i = 0
      foreach ($line in [IO.File]::ReadAllLines($fake)) {
        if ($line) { $i++; $procs += [pscustomobject]@{ Id = $i; Path = $line } }
      }
    }
  } else {
    $procs = @(Get-Process | ForEach-Object {
      $path = $null
      try { $path = $_.Path } catch { }
      if ($path) { [pscustomobject]@{ Id = $_.Id; Path = $path } }
    })
  }
  $out = @()
  foreach ($p in $procs) {
    $leaf = Split-Path -Leaf $p.Path
    $hit = $false
    foreach ($n in $names) { if (SameText $leaf $n) { $hit = $true } }
    if ($leaf -like 'Cobblify Launcher*.exe') { $hit = $true }
    foreach ($d in $dirs) { if (IsUnder $p.Path $d) { $hit = $true } }
    if ($hit) { $out += ('{0} {1}' -f $p.Id, $p.Path) }
  }
  return $out
}

function RefuseIfRunning {
  $r = @(Running)
  if ($r.Count -gt 0) {
    Die ("close these first (Lunar, Prism, Minecraft and any other Java program, every Cobblify launcher;`n" +
         "Lunar may be in the system tray - right-click its icon by the clock and quit it):`n" + ($r -join "`n"))
  }
}

# Login-time starters, for the player to re-check by hand: the Run key and the
# Startup folder. Read only; nothing here ever writes the registry.
function Startup {
  $out = @()
  if (-not [Environment]::GetEnvironmentVariable('FRESH_NO_REGISTRY')) {
    try {
      $run = Get-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' -ErrorAction Stop
      foreach ($prop in $run.PSObject.Properties) {
        if ($prop.Name -like 'PS*') { continue }
        if ((GroupOfName $prop.Name) -ne 'other' -or (GroupOfName ([string]$prop.Value)) -ne 'other') {
          $out += ('Run: {0} = {1}' -f $prop.Name, $prop.Value)
        }
      }
    } catch { $out += '(could not read the Run key; check Task Manager > Startup apps by hand)' }
  }
  foreach ($p in (Children (Join-Path $StartMenuDir 'Startup'))) {
    if ((GroupOfName $p) -ne 'other') { $out += ('Startup folder: ' + $p) }
  }
  if ($out.Count -eq 0) { $out = @('(none found)') }
  return $out
}

function ActiveStash {
  $active = @()
  foreach ($d in (Children $STASH_ROOT)) {
    $s = Join-Path $d 'state'
    if ([IO.File]::Exists($s) -and ([IO.File]::ReadAllText($s).Trim() -eq 'active')) { $active += $d }
  }
  if ($active.Count -gt 1) {
    Die ("more than one stash is active:`n  " + ($active -join "`n  ") + "`nCheck them by hand: each holds originals.")
  }
  if ($active.Count -eq 1) { return $active[0] }
  return $null
}

# One aside or restore at a time (review code-1 I6): held until this run ends.
function Lock {
  [void][IO.Directory]::CreateDirectory($STASH_ROOT)
  try {
    $script:lock = New-Object IO.FileStream((Join-Path $STASH_ROOT '.lock'), [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
  } catch { Die 'another fresh-win.ps1 is running; let it finish first' }
}

# Where an original lives inside a stash area (items\ or new\<n>\).
function Enc([string]$p) {
  if (-not (IsUnder $p $HomeDir) -or (SameText $p $HomeDir)) { Die "path outside the managed root: $p" }
  return 'home' + $p.Substring($HomeDir.TrimEnd('\').Length)
}

# ---------------------------------------------------------------------------
# Content record: one line per entry, sorted, relative to the item's parent so it
# is identical before and after the round trip.
# path <TAB> kind <TAB> size <TAB> attributes <TAB> link target <TAB> sha256

$IGNORED = @('desktop.ini', 'thumbs.db')

function Sha256([string]$file) {
  $sha = [Security.Cryptography.SHA256]::Create()
  $fs = New-Object IO.FileStream($file, [IO.FileMode]::Open, [IO.FileAccess]::Read, ([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
  try { $h = $sha.ComputeHash($fs) } finally { $fs.Dispose(); $sha.Dispose() }
  return -join ($h | ForEach-Object { $_.ToString('x2') })
}

function AttrFlags([IO.FileAttributes]$a) {
  $s = ''
  if ($a -band [IO.FileAttributes]::ReadOnly) { $s += 'R' }
  if ($a -band [IO.FileAttributes]::Hidden) { $s += 'H' }
  if ($a -band [IO.FileAttributes]::System) { $s += 'S' }
  if ($s -eq '') { $s = '-' }
  return $s
}

function LinkTarget([string]$p) {
  try {
    $t = (Get-Item -LiteralPath $p -Force).Target
    if ($t) { return (@($t) -join '|') }
  } catch { }
  return '?'
}

function ContentOf([string]$item) {
  $lines = New-Object System.Collections.Generic.List[string]
  $parent = [IO.Path]::GetDirectoryName($item)
  $script:contentError = $null
  $stack = New-Object System.Collections.Generic.Stack[string]
  $stack.Push($item)
  while ($stack.Count -gt 0) {
    $p = $stack.Pop()
    $rel = $p.Substring($parent.TrimEnd('\').Length + 1)
    try {
      $a = [IO.File]::GetAttributes($p)
      $flags = AttrFlags $a
      if ($a -band [IO.FileAttributes]::ReparsePoint) {
        $lines.Add(($rel, 'link', '-', $flags, (LinkTarget $p), '-') -join $TAB)
      } elseif ($a -band [IO.FileAttributes]::Directory) {
        $lines.Add(($rel, 'dir', '-', $flags, '-', '-') -join $TAB)
        foreach ($c in [IO.Directory]::GetFileSystemEntries($p)) {
          # Explorer writes these into folders it shows; they are not the player's files.
          if (($IGNORED -contains (Split-Path -Leaf $c).ToLowerInvariant()) -and [IO.File]::Exists($c)) { continue }
          $stack.Push($c)
        }
      } else {
        $len = (New-Object IO.FileInfo $p).Length
        $lines.Add(($rel, 'file', [string]$len, $flags, '-', (Sha256 $p)) -join $TAB)
      }
    } catch {
      $script:contentError = "$p : $($_.Exception.Message)"
      return @('UNREADABLE')
    }
  }
  $arr = $lines.ToArray()
  [Array]::Sort($arr, [StringComparer]::Ordinal)
  return $arr
}

function WriteContent([string]$item, [string]$file) {
  $c = @(ContentOf $item)
  if ($c.Count -eq 1 -and $c[0] -eq 'UNREADABLE') {
    Die "could not read every file in $item ($($script:contentError)); fix that, then run aside again"
  }
  [IO.File]::WriteAllText($file, (($c -join "`n") + "`n"), $UTF8)
}

# ---------------------------------------------------------------------------
# Journal

function J { return (Join-Path $script:STASH 'journal.tsv') }

function JLines {
  $j = J
  if (-not [IO.File]::Exists($j)) { return @() }
  return @([IO.File]::ReadAllLines($j, $UTF8) | Where-Object { $_ -ne '' } | ForEach-Object {
    $f = $_.Split("`t")
    [pscustomobject]@{ Seq = [int]$f[0]; Op = $f[1]; State = $f[2]; Src = $f[3]; Dst = $f[4] }
  })
}

function JNext { return (@(JLines).Count + 1) }

function JWrite($seq, $op, $state, $src, $dst) {
  try {
    [IO.File]::AppendAllText((J), (($seq, $op, $state, $src, $dst) -join $TAB) + "`n", $UTF8)
    return $true
  } catch { return $false }
}

# The journal is the only record of what moved: when it cannot be written, nothing else moves.
function JFail { Warn "fresh-win: cannot write the stash journal $(J). Nothing more is moved. Free some disk space or fix its permissions, then run: fresh-win.ps1 restore" }

# Test hooks, each a regex on the move's destination: fail it; do it and then die
# before its "done" line (a crash); do it and then make the journal refuse writes.
function Hook([string]$name, [string]$dst) {
  $re = [Environment]::GetEnvironmentVariable($name)
  return [bool]($re -and ($dst -match $re))
}

# A move by rename on one volume. Never a copy: Directory.Move refuses another volume.
function RawMove([string]$src, [string]$dst) {
  if (Hook 'FRESH_FAIL_DST' $dst) { throw 'injected failure (FRESH_FAIL_DST)' }
  if (IsDirEntry $src) { [IO.Directory]::Move($src, $dst) } else { [IO.File]::Move($src, $dst) }
  if (Hook 'FRESH_CRASH_DST' $dst) { [Console]::Out.Flush(); [Environment]::Exit(7) }
  if (Hook 'FRESH_JOURNAL_RO_DST' $dst) { [IO.File]::SetAttributes((J), [IO.FileAttributes]::ReadOnly) }
}

# Moves src to dst through the journal. Returns $false on failure. The outcome of a
# failed move is read from the disk rather than assumed.
function JMove([string]$op, [string]$src, [string]$dst) {
  $seq = JNext
  $parent = [IO.Path]::GetDirectoryName($dst)
  try { [void][IO.Directory]::CreateDirectory($parent) } catch { Warn "fresh-win: cannot create $parent"; return $false }
  if (Exists $dst) { Warn "fresh-win: refusing to overwrite $dst"; return $false }
  if (-not (SameText ([IO.Path]::GetPathRoot($src)) ([IO.Path]::GetPathRoot($dst)))) {
    Warn "fresh-win: $src and $dst are on different drives; refusing a copying move"; return $false
  }
  if (-not (JWrite $seq $op 'intent' $src $dst)) { JFail; return $false }
  $err = $null
  try { RawMove $src $dst } catch { $err = $_.Exception.Message }
  if (-not $err) {
    if (-not (JWrite $seq $op 'done' $src $dst)) { JFail; return $false }
    return $true
  }
  if ((Exists $dst) -and -not (Exists $src)) {
    if (-not (JWrite $seq $op 'done' $src $dst)) { JFail; return $false }
    return $true
  }
  if ((Exists $src) -and -not (Exists $dst)) {
    if (-not (JWrite $seq $op 'void' $src $dst)) { JFail }
  } else {
    Warn "fresh-win: a failed move left both or neither of $src and $dst; the journal keeps it open for restore"
  }
  Warn "fresh-win: could not move ${src}: $err"
  if ($err -match 'being used by another process|denied') {
    Warn "fresh-win: something still has it open. Close Lunar, Prism, Minecraft and Cobblify (check the system tray), then run this again."
  }
  return $false
}

function DirsKnown { return @([IO.File]::ReadAllLines((Join-Path $script:STASH 'dirs.txt'), $UTF8) | Where-Object { $_ }) }

# Folders testing created only to hold what was swept: removed when empty and absent at aside.
function PruneNewDirs([string[]]$dirs) {
  $known = @{}
  foreach ($k in (DirsKnown)) { $known[$k.ToLowerInvariant()] = $true }
  foreach ($d0 in $dirs) {
    $d = $d0
    while ($d -and -not (SameText $d $HomeDir) -and (IsUnder $d $HomeDir)) {
      if ($known.ContainsKey($d.ToLowerInvariant())) { break }
      if (-not [IO.Directory]::Exists($d) -or @(Children $d).Count -gt 0) { break }
      try { [IO.Directory]::Delete($d, $false) } catch { break }
      Say ("  removed empty folder " + $d.Substring($HomeDir.TrimEnd('\').Length + 1))
      $d = [IO.Path]::GetDirectoryName($d)
    }
  }
}

# Finishes the record of any move a crash interrupted, by looking at which side of
# it exists. Must run before anything is classified.
function Reconcile {
  $open = [ordered]@{}
  foreach ($e in (JLines)) { if ($e.State -eq 'intent') { $open[[string]$e.Seq] = $e } else { $open.Remove([string]$e.Seq) } }
  foreach ($e in $open.Values) {
    if ((Exists $e.Dst) -and -not (Exists $e.Src)) { if (-not (JWrite $e.Seq $e.Op 'done' $e.Src $e.Dst)) { JFail; return $false } }
    elseif ((Exists $e.Src) -and -not (Exists $e.Dst)) { if (-not (JWrite $e.Seq $e.Op 'void' $e.Src $e.Dst)) { JFail; return $false } }
    else {
      Warn ("fresh-win: an interrupted move left both or neither of:`n  $($e.Src)`n  $($e.Dst)`n" +
            "Nothing was changed. Check both paths by hand, then run restore again.")
      return $false
    }
  }
  return $true
}

# Originals whose stash move completed and whose return has not: original -> stashed copy.
function StashedNotReturned {
  $stashed = [ordered]@{}
  $returned = @{}
  foreach ($e in (JLines)) {
    if ($e.State -ne 'done') { continue }
    if ($e.Op -eq 'stash') { $stashed[$e.Src.ToLowerInvariant()] = $e }
    if ($e.Op -eq 'return') { $returned[$e.Dst.ToLowerInvariant()] = $true }
  }
  $out = @()
  foreach ($k in $stashed.Keys) { if (-not $returned.ContainsKey($k)) { $out += $stashed[$k] } }
  return $out
}

function Returned([string]$p) {
  foreach ($e in (JLines)) { if ($e.State -eq 'done' -and $e.Op -eq 'return' -and (SameText $e.Dst $p)) { return $true } }
  return $false
}

# Puts every stashed-not-returned item back.
function ReturnPass {
  $ok = $true
  foreach ($e in (StashedNotReturned)) {
    if (Exists $e.Src) { Warn "fresh-win: $($e.Src) exists, cannot return its original"; $ok = $false; continue }
    if (JMove 'return' $e.Dst $e.Src) { Say ("  returned  " + $e.Src) } else { $ok = $false }
  }
  return $ok
}

function InventoryRows {
  return @([IO.File]::ReadAllLines((Join-Path $script:STASH 'inventory.tsv'), $UTF8) | Where-Object { $_ } | ForEach-Object {
    $f = $_.Split("`t")
    [pscustomobject]@{ Group = $f[0]; State = $f[1]; Path = $f[2]; N = $f[3] }
  })
}

# Checks every stashed item against its record and every recorded path against its state at aside.
function Verify {
  $ok = $true
  foreach ($r in (InventoryRows)) {
    switch ($r.State) {
      'present' {
        if (-not (Exists $r.Path)) { Warn "  MISSING    $($r.Path)"; $ok = $false; break }
        $want = @([IO.File]::ReadAllLines((Join-Path $script:STASH "content\$($r.N).tsv"), $UTF8) | Where-Object { $_ -ne '' })
        $got = @(ContentOf $r.Path)
        $diff = @(Compare-Object -ReferenceObject $want -DifferenceObject $got -CaseSensitive -SyncWindow ([Math]::Max($want.Count, $got.Count) + 1))
        if ($diff.Count -gt 0) {
          Warn "  CHANGED    $($r.Path)"
          foreach ($d in ($diff | Select-Object -First 12)) {
            $side = if ($d.SideIndicator -eq '<=') { 'was' } else { 'now' }
            Warn ("             {0}: {1}" -f $side, $d.InputObject)
          }
          $ok = $false
        }
      }
      'absent' { if (Exists $r.Path) { Warn "  UNEXPECTED $($r.Path)"; $ok = $false } }
      'kept' { if (-not (Exists $r.Path)) { Warn "  MISSING    $($r.Path)"; $ok = $false } }
    }
  }
  return $ok
}

function SetState([string]$s) { [IO.File]::WriteAllText((Join-Path $script:STASH 'state'), "$s`n", $UTF8) }

function Confirm([string]$q) {
  if ($script:yes) { return $true }
  $a = Read-Host "$q [y/N]"
  return @('y', 'yes') -contains $a.Trim().ToLowerInvariant()
}

# Drops any path inside another one in the list.
function TopMost([string[]]$paths) {
  $sorted = @($paths | Sort-Object -Unique)
  $kept = @()
  foreach ($p in $sorted) {
    $inside = $false
    foreach ($k in $kept) { if (IsUnder $p $k) { $inside = $true; break } }
    if (-not $inside) { $kept += $p }
  }
  return $kept
}

# ---------------------------------------------------------------------------
# Commands

function CmdStatus {
  Say 'Known paths (state now):'
  foreach ($i in (Inventory)) {
    $s = if (Exists $i.Path) { 'present' } else { 'absent ' }
    Say "  $s  [$($i.Group)] $($i.Path)"
  }
  Say 'Also found:'
  foreach ($i in (@(PrismExtras) + @(Discover))) { Say "  [$($i.Group)] $($i.Path)" }
  Say 'Running related processes:'
  $r = @(Running)
  if ($r.Count -eq 0) { Say '  none' } else { foreach ($x in $r) { Say "  $x" } }
  Say 'Login-time starters:'
  foreach ($x in (Startup)) { Say "  $x" }
  $s = ActiveStash
  if ($s) { Say "Active stash: $s (run: fresh-win.ps1 restore)" } else { Say 'No active stash.' }
}

function CmdAside([string[]]$argv) {
  $usage = 'usage: fresh-win.ps1 aside <lunar|prism|all> [--data-only] [--yes]'
  $mode = $null; $dataOnly = $false
  foreach ($a in $argv) {
    switch ($a) {
      { @('lunar', 'prism', 'all') -contains $_ } { $mode = $a; break }
      '--data-only' { $dataOnly = $true; break }
      '--yes' { $script:yes = $true; break }
      default { Die $usage }
    }
  }
  if (-not $mode) { Die $usage }
  Lock
  $s = ActiveStash
  if ($s) { Die "a stash is already active: $s. Run: fresh-win.ps1 restore" }
  RefuseIfRunning

  $inMode = { param($g) ($g -eq 'cobblify') -or ($g -eq $mode) -or ($mode -eq 'all' -and ($g -eq 'lunar' -or $g -eq 'prism')) }
  $extras = @(PrismExtras)
  $leftInstalled = @()
  foreach ($x in $extras) {
    if (-not (& $inMode $x.Group) -or -not (Exists $x.Path) -or (InProfile $x.Path)) { continue }
    if ($x.App) { $leftInstalled += $x.Path; continue }
    Die ("Prism keeps data in $($x.Path), outside your user folder; this tool only moves things inside it.`n" +
         "Close Prism, move that folder aside by hand (rename it), then run aside again. Nothing was moved.")
  }
  $plan = @()
  foreach ($i in (@(Inventory) + $extras + @(Discover))) {
    # A path outside the profile (a Desktop redirected to another drive, say) is never moved.
    if ($i.Group -eq 'other' -or -not (& $inMode $i.Group) -or -not (InProfile $i.Path)) { $state = 'untouched' }
    elseif (-not (Exists $i.Path)) { $state = 'absent' }
    elseif ($dataOnly -and $i.App -and $i.Group -ne 'cobblify') { $state = 'kept' }
    else { $state = 'present' }
    $plan += [pscustomobject]@{ Group = $i.Group; State = $state; Path = $i.Path }
  }
  # Something inside an item that moves whole moves with it (and its record covers it).
  foreach ($p in $plan) {
    if ($p.State -ne 'present') { continue }
    foreach ($q in $plan) {
      if ($q -ne $p -and $q.State -eq 'present' -and (IsUnder $p.Path $q.Path) -and -not (SameText $p.Path $q.Path)) { $p.State = 'untouched'; break }
    }
  }
  $present = @($plan | Where-Object { $_.State -eq 'present' })
  Say 'This will MOVE (not delete) these into %USERPROFILE%\CobblifyStash:'
  foreach ($p in $present) { Say "  $($p.Path)" }
  foreach ($p in $leftInstalled) { Say "  (left installed, outside your user folder: $p - its data is set aside)" }
  if ($present.Count -eq 0) { Die 'nothing to set aside' }
  if (-not (Confirm 'Set these aside?')) { Die 'nothing was moved' }

  $stamp = EnvOr 'FRESH_STAMP' (Get-Date -Format 'yyyyMMdd-HHmmss')
  $script:STASH = Join-Path $STASH_ROOT $stamp
  if (Exists $script:STASH) { Die "$($script:STASH) already exists" }
  [void][IO.Directory]::CreateDirectory((Join-Path $script:STASH 'content'))
  [void][IO.Directory]::CreateDirectory((Join-Path $script:STASH 'items'))
  [IO.File]::WriteAllText((J), '', $UTF8)
  [IO.File]::WriteAllText((Join-Path $script:STASH 'mode'), "$mode`n$([int]$dataOnly)`n", $UTF8)
  $n = 0
  $rows = foreach ($p in $plan) {
    $num = '-'
    if ($p.State -eq 'present') { $n++; $num = [string]$n }
    (($p.Group, $p.State, $p.Path, $num) -join $TAB)
  }
  [IO.File]::WriteAllText((Join-Path $script:STASH 'inventory.tsv'), (($rows -join "`n") + "`n"), $UTF8)
  [IO.File]::WriteAllText((Join-Path $script:STASH 'startup.txt'), ((@(Startup) -join "`n") + "`n"), $UTF8)
  # Every folder that existed at aside and could hold a swept path.
  $dirs = @{}
  foreach ($root in @($HomeDir, $RoamingDir, $LocalDir, (Join-Path $LocalDir 'Programs'), $StartMenuDir, $DesktopDir)) {
    if ([IO.Directory]::Exists($root)) { $dirs[$root] = $true }
    foreach ($c in (Children $root)) { if ([IO.Directory]::Exists($c)) { $dirs[$c] = $true } }
  }
  foreach ($p in $plan) {
    $d = [IO.Path]::GetDirectoryName($p.Path)
    while ($d) { if ([IO.Directory]::Exists($d)) { $dirs[$d] = $true }; $d = [IO.Path]::GetDirectoryName($d) }
  }
  [IO.File]::WriteAllText((Join-Path $script:STASH 'dirs.txt'), ((@($dirs.Keys | Sort-Object) -join "`n") + "`n"), $UTF8)

  Say 'Recording every file (large folders take a while)...'
  foreach ($r in (InventoryRows)) {
    if ($r.State -ne 'present') { continue }
    Say "  recording $($r.Path)"
    WriteContent $r.Path (Join-Path $script:STASH "content\$($r.N).tsv")
  }
  # Active only once every item is recorded: an item that cannot be recorded stops aside
  # with nothing moved and no stash to restore.
  SetState 'active'

  $failed = $false
  foreach ($r in (InventoryRows)) {
    if ($r.State -ne 'present') { continue }
    if (JMove 'stash' $r.Path (Join-Path (Join-Path $script:STASH 'items') (Enc $r.Path))) { Say "  set aside $($r.Path)" }
    else { $failed = $true; break }
  }
  if ($failed) {
    Say 'Putting back what was moved...'
    if ((Reconcile) -and (ReturnPass) -and (Verify)) {
      SetState 'aborted'
      Die "aside failed and everything was put back. Stash record kept in $($script:STASH)"
    }
    Die 'aside failed AND putting things back failed. Fix the cause above, then run: fresh-win.ps1 restore'
  }
  Say ''
  Say "Done. Stash: $($script:STASH)"
  Say 'Login-time starters at the time (re-check them after restore):'
  foreach ($x in [IO.File]::ReadAllLines((Join-Path $script:STASH 'startup.txt'))) { if ($x) { Say "  $x" } }
  Say "Run 'fresh-win.ps1 restore' when finished."
}

function CmdRestore([string[]]$argv) {
  foreach ($a in $argv) { if ($a -eq '--yes') { $script:yes = $true } else { Die 'usage: fresh-win.ps1 restore [--yes]' } }
  if (-not [IO.Directory]::Exists($STASH_ROOT)) { Die "no active stash under $STASH_ROOT" }
  Lock
  $script:STASH = ActiveStash
  if (-not $script:STASH) { Die "no active stash under $STASH_ROOT" }
  RefuseIfRunning
  if (-not (Confirm "Restore from $($script:STASH)? Anything created while testing moves into $($script:STASH)\new\.")) { Die 'nothing was changed' }

  if (-not (Reconcile)) { exit 1 }

  $mode = ([IO.File]::ReadAllLines((Join-Path $script:STASH 'mode')))[0]
  $attempt = 1
  while (Exists (Join-Path $script:STASH "new\$attempt")) { $attempt++ }
  $newdir = Join-Path $script:STASH "new\$attempt"

  # Sweep: what testing created. Never a returned original.
  $pending = @{}
  foreach ($e in (StashedNotReturned)) { $pending[$e.Src.ToLowerInvariant()] = $true }
  $sweep = @()
  $recorded = @()
  foreach ($r in (InventoryRows)) {
    $recorded += $r.Path
    if ($r.State -eq 'absent' -and (Exists $r.Path)) { $sweep += $r.Path }
    if ($r.State -eq 'present' -and (Exists $r.Path) -and -not (Returned $r.Path) -and $pending.ContainsKey($r.Path.ToLowerInvariant())) { $sweep += $r.Path }
  }
  # What testing installed that the fixed list can't know: a Prism the test Cobblify remembered
  # (read now, before its .cobblify is swept), its data and settings folders; and name discovery.
  $outsideNew = @()
  foreach ($i in (@(PrismExtras) + @(Discover))) {
    $want = ($i.Group -eq 'cobblify') -or ($i.Group -eq $mode) -or ($mode -eq 'all' -and ($i.Group -eq 'lunar' -or $i.Group -eq 'prism'))
    if (-not $want -or -not (Exists $i.Path)) { continue }
    $known = $false
    foreach ($p in $recorded) { if (SameText $p $i.Path) { $known = $true } }
    if ($known) { continue }
    if (InProfile $i.Path) { $sweep += $i.Path } else { $outsideNew += $i.Path }
  }

  # What this restore found to sweep is written down before anything moves: a crash after
  # .cobblify is swept loses the record that pointed at a Prism installed while testing, and a
  # retry must still sweep it (review code-2 I2).
  $foundFile = Join-Path $script:STASH 'found.txt'
  if ($sweep.Count -gt 0) { [IO.File]::AppendAllText($foundFile, (($sweep -join "`n") + "`n"), $UTF8) }
  if ([IO.File]::Exists($foundFile)) {
    foreach ($p in [IO.File]::ReadAllLines($foundFile, $UTF8)) {
      # Recorded paths are classified above from the record itself; only the rest come from here.
      if ($p -and (Exists $p) -and (InProfile $p) -and -not ($sweep -contains $p)) {
        $isRecorded = $false
        foreach ($r in $recorded) { if (SameText $r $p) { $isRecorded = $true } }
        if (-not $isRecorded) { $sweep += $p }
      }
    }
  }
  $failed = $false
  if ($sweep.Count -gt 0) {
    foreach ($p in (TopMost $sweep)) {
      if (JMove 'sweep' $p (Join-Path $newdir (Enc $p))) { Say "  moved to new\  $p" } else { $failed = $true }
    }
  }
  # Every sweep the journal records, this attempt's and any an interrupted one finished: their
  # parents may be folders testing created.
  $parents = @(JLines | Where-Object { $_.Op -eq 'sweep' -and $_.State -eq 'done' } | ForEach-Object { [IO.Path]::GetDirectoryName($_.Src) } | Sort-Object -Unique -Descending)
  PruneNewDirs $parents
  if ($failed) { Die 'could not move everything created while testing. Fix the cause above and run restore again.' }

  if (-not (ReturnPass)) { Die 'some originals could not be returned. Fix the cause above and run restore again.' }

  Say 'Checking every returned file against the record...'
  if (-not (Verify)) { Die 'restore finished moving but the check above found differences. The stash stays active; nothing was deleted.' }
  SetState 'restored'
  Say ''
  Say 'Restored. Every returned file matches the record taken at aside.'
  Say "Anything created while testing is in: $newdir"
  foreach ($p in $outsideNew) { Say "Testing left this outside your user folder (not moved; remove it yourself if you don't want it): $p" }
  Say 'Login-time starters when you set things aside (re-check them):'
  foreach ($x in [IO.File]::ReadAllLines((Join-Path $script:STASH 'startup.txt'))) { if ($x) { Say "  $x" } }
  Say "Delete $($script:STASH) yourself once you are happy."
}

$cmd = if ($args.Count -gt 0) { [string]$args[0] } else { '' }
$rest = @()
if ($args.Count -gt 1) { $rest = @($args[1..($args.Count - 1)] | ForEach-Object { [string]$_ }) }
switch ($cmd) {
  'status' { CmdStatus }
  'aside' { CmdAside $rest }
  'restore' { CmdRestore $rest }
  default {
    Say 'fresh-win.ps1 status | aside <lunar|prism|all> [--data-only] [--yes] | restore [--yes]'
    Say 'Run: powershell -ExecutionPolicy Bypass -File fresh-win.ps1 <command>'
    exit 2
  }
}
exit 0
