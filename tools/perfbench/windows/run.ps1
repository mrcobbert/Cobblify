param([string]$PlanFile = "plan.json")
# Runs a benchmark plan in the logged-in desktop session (started by a scheduled task with /IT).
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$plan = Get-Content (Join-Path $root $PlanFile) -Raw | ConvertFrom-Json
$profiles = Get-Content (Join-Path $root "profiles.json") -Raw | ConvertFrom-Json
$res = Join-Path $root ("results\" + $plan.name)
New-Item -ItemType Directory -Force $res | Out-Null
$log = Join-Path $res "runner.log"
function Log($m) { $line = (Get-Date -Format "HH:mm:ss") + " " + $m; Add-Content -Path $log -Value $line }

Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class W {
  [DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint f);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int c);
  [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
  [DllImport("user32.dll")] public static extern bool SystemParametersInfo(uint a, uint b, IntPtr c, uint d);
  [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr h);
  [DllImport("user32.dll")] public static extern void SwitchToThisWindow(IntPtr h, bool alt);
  [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint a, uint b, bool attach);
  [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, System.Text.StringBuilder s, int n);
  [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetClassName(IntPtr h, System.Text.StringBuilder s, int n);
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc f, IntPtr l);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }

  // The process's largest visible top-level window (javaw can own several, e.g. Compose/OneConfig helpers).
  public static IntPtr GameWindow(int pid) {
    IntPtr best = IntPtr.Zero; long area = 0;
    EnumWindows(delegate(IntPtr h, IntPtr l) {
      uint wp; GetWindowThreadProcessId(h, out wp);
      if (wp == (uint)pid && IsWindowVisible(h)) {
        RECT r; GetWindowRect(h, out r);
        long a = (long)(r.R - r.L) * (r.B - r.T);
        if (a > area) { area = a; best = h; }
      }
      return true;
    }, IntPtr.Zero);
    return best;
  }

  public static string List(int pid) {
    var sb = new System.Text.StringBuilder();
    EnumWindows(delegate(IntPtr h, IntPtr l) {
      uint wp; GetWindowThreadProcessId(h, out wp);
      if (wp == (uint)pid) {
        RECT r; GetWindowRect(h, out r);
        var t = new System.Text.StringBuilder(200); GetWindowText(h, t, 200);
        var c = new System.Text.StringBuilder(200); GetClassName(h, c, 200);
        sb.Append(string.Format("[{0} '{1}' {2}x{3} vis={4}] ", c, t, r.R - r.L, r.B - r.T, IsWindowVisible(h)));
      }
      return true;
    }, IntPtr.Zero);
    return sb.ToString();
  }

  public static bool Focus(IntPtr h) {
    if (h == IntPtr.Zero) return false;
    if (GetForegroundWindow() == h) return true;
    keybd_event(0x12, 0, 0, UIntPtr.Zero);
    keybd_event(0x12, 0, 2, UIntPtr.Zero);
    uint ignored;
    uint fg = GetWindowThreadProcessId(GetForegroundWindow(), out ignored);
    uint me = GetCurrentThreadId();
    bool attached = fg != 0 && fg != me && AttachThreadInput(me, fg, true);
    ShowWindow(h, 5);
    BringWindowToTop(h);
    SetForegroundWindow(h);
    if (attached) AttachThreadInput(me, fg, false);
    if (GetForegroundWindow() != h) SwitchToThisWindow(h, true);
    if (GetForegroundWindow() != h) { ShowWindow(h, 6); ShowWindow(h, 9); SetForegroundWindow(h); }
    return GetForegroundWindow() == h;
  }
}
"@
# Task Scheduler starts tasks at BelowNormal and children inherit it; a game launched normally runs at Normal.
(Get-Process -Id $PID).PriorityClass = "Normal"
# Keep the display on and the screen saver off while the plan runs.
[W]::SetThreadExecutionState([uint32]"0x80000003") | Out-Null
[W]::SystemParametersInfo(0x0011, 0, [IntPtr]::Zero, 0) | Out-Null

# Other apps in this session (e.g. mouse software that starts at login) hold the foreground; minimise them.
function ClearDesktop() {
  foreach ($o in Get-Process | Where-Object { $_.MainWindowHandle -ne 0 -and $_.ProcessName -ne "explorer" -and $_.ProcessName -ne "javaw" }) {
    [W]::ShowWindow($o.MainWindowHandle, 6) | Out-Null
  }
}

function Focus($p) {
  return [W]::Focus([W]::GameWindow($p.Id))
}

function Sub($s, $cfgName) {
  $game = Join-Path $root ("inst\" + $cfgName)
  return $s.Replace("{root}", $root).Replace("{game}", $game).Replace("{mods}", (Join-Path $game "mods")).Replace("{natives}", (Join-Path $root $profiles.($plan.configs.$cfgName.profile).nativesDir.Replace("/", "\")))
}

$script:resW = $plan.width; $script:resH = $plan.height; $script:full = $false

function Launch($cfgName, $label, $mode) {
  $c = $plan.configs.$cfgName
  $prof = $profiles.($c.profile)
  $game = Join-Path $root ("inst\" + $cfgName)
  $opt = Join-Path $game "options.txt"
  $fs = $(if ($script:full) { "fullscreen:true" } else { "fullscreen:false" })
  $lines = Get-Content $opt | ForEach-Object { if ($_ -like "fullscreen:*") { $fs } else { $_ } }
  Set-Content -Path $opt -Value $lines -Encoding ASCII
  if ($mode -eq "run") {
    $w = Join-Path $game "saves\bench"
    if (Test-Path $w) { Remove-Item -Recurse -Force $w }
    New-Item -ItemType Directory -Force (Join-Path $game "saves") | Out-Null
    Copy-Item -Recurse (Join-Path $root "template\bench") $w
  }
  $cp = ($prof.classpath | ForEach-Object { Join-Path $root $_.Replace("/", "\") }) -join ";"
  $a = @()
  foreach ($x in $c.jvm) { $a += (Sub $x $cfgName) }
  $a += "-Dfabric.addMods=" + (Join-Path $root "bench\cobbench.jar")
  $a += "-Dcobbench.mode=$mode"; $a += "-Dcobbench.out=$res"; $a += "-Dcobbench.label=$label"
  foreach ($k in $plan.benchProps.PSObject.Properties) { $a += ("-Dcobbench." + $k.Name + "=" + $k.Value) }
  $a += "-cp"; $a += $cp; $a += $prof.mainClass
  $a += @("--username", "Bench", "--version", "1.8.9", "--gameDir", $game, "--assetsDir", (Join-Path $root "game\assets"),
          "--assetIndex", "1.8", "--uuid", "4e2b1f0c9a7d4e3f8b6a5c4d3e2f1a0b", "--accessToken", "0",
          "--userProperties", "{}", "--userType", "legacy", "--width", "$($script:resW)", "--height", "$($script:resH)")
  if ($prof.tweakers) { foreach ($t in $prof.tweakers) { $a += "--tweakClass"; $a += $t } }
  foreach ($k in $c.env.PSObject.Properties) { Set-Item -Path ("env:" + $k.Name) -Value $k.Value }
  $java = Join-Path $root $c.java.Replace("/", "\")
  ClearDesktop
  $p = Start-Process -FilePath $java -ArgumentList $a -WorkingDirectory $game -PassThru `
        -RedirectStandardOutput (Join-Path $res "$label.out.log") -RedirectStandardError (Join-Path $res "$label.err.log")
  foreach ($k in $c.env.PSObject.Properties) { Remove-Item -Path ("env:" + $k.Name) -ErrorAction SilentlyContinue }
  Log "start $label ($cfgName, $mode, $($script:resW)x$($script:resH) full=$($script:full)) pid $($p.Id)"
  $meta = Join-Path $res "$label-meta.json"
  $t0 = Get-Date
  $focusFails = 0
  $prioLogged = $false
  while (-not $p.HasExited) {
    Start-Sleep -Milliseconds 1500
    if (Test-Path $meta) { break }
    if (-not (Focus $p)) { $focusFails++ }
    if (-not $prioLogged -and ((Get-Date) - $t0).TotalSeconds -gt 45) { $prioLogged = $true; try { $p.Refresh(); Log "priority $label $($p.PriorityClass) threads=$($p.Threads.Count) fg=$([W]::GetForegroundWindow() -eq [W]::GameWindow($p.Id)) windows: $([W]::List($p.Id))" } catch { Log "priority $label ? $_" } }
    if (((Get-Date) - $t0).TotalSeconds -gt $plan.timeoutSeconds) { Log "timeout $label"; break }
  }
  $t1 = Get-Date
  while (-not $p.HasExited -and ((Get-Date) - $t1).TotalSeconds -lt 30) { Start-Sleep -Milliseconds 500 }
  if (-not $p.HasExited) { Log "kill $label"; Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue; Start-Sleep 2 }
  Log ("end $label exit=" + $(if ($p.HasExited) { $p.ExitCode } else { "killed" }) + " meta=" + (Test-Path $meta) + " focusFails=$focusFails secs=" + [int]((Get-Date) - $t0).TotalSeconds)
}

Log "plan $($plan.name) start"
foreach ($step in $plan.steps) {
  $parts = $step.Split(":")
  $mode = $parts[0]; $cfgName = $parts[1]; $label = $parts[2]
  if ($mode -eq "res") {
    $script:resW = [int]$parts[1]; $script:resH = [int]$parts[2]; $script:full = ($parts[3] -eq "full")
    Log "resolution $($script:resW)x$($script:resH) full=$($script:full)"
    continue
  }
  if ($mode -eq "template") {
    $src = Join-Path $root ("inst\" + $cfgName + "\saves\bench")
    $dst = Join-Path $root "template\bench"
    if (Test-Path $dst) { Remove-Item -Recurse -Force $dst }
    New-Item -ItemType Directory -Force (Join-Path $root "template") | Out-Null
    Copy-Item -Recurse $src $dst
    Remove-Item -Force -ErrorAction SilentlyContinue (Join-Path $dst "session.lock")
    Log "template from $cfgName"
    continue
  }
  Launch $cfgName $label $mode
  Start-Sleep -Seconds $plan.pauseSeconds
}
Log "plan $($plan.name) done"
Set-Content -Path (Join-Path $res "DONE") -Value "done"
