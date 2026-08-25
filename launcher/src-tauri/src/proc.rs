//! Process inspection - the privacy boundary.
//!
//! This is the ONLY file in the crate allowed to `use sysinfo`. Lunar's game JVM carries a
//! live Minecraft `--accessToken` in its argv, so the launcher must never hold a process
//! command line. The snapshot below requests the executable path and nothing else; Phase 0
//! proved empirically that this leaves `Process::cmd()` empty (see the canary test below).
//!
//! `System::new_all()`, `System::refresh_all()` and `ProcessRefreshKind::everything()` all
//! populate argv and must never appear in non-test code.
//!
//! Path identity is per-platform: macOS keeps exact comparison; Windows compares
//! component-wise and case-insensitively, because NTFS treats `C:\Users\JANE` and
//! `C:\Users\Jane` as the same path and a case-sensitive match would silently break
//! both Lunar detection and the dashboard's game gate.

use std::ffi::OsStr;
use std::path::{Path, PathBuf};

use sysinfo::{ProcessRefreshKind, ProcessesToUpdate, System, UpdateKind};

/// Verified 2026-08-04: `pgrep -f` on this exact path returned one PID. Loose substring
/// matching hits 4+ Electron helper processes (GPU, renderer, network, crashpad), so the
/// comparison below is exact equality.
#[cfg(target_os = "macos")]
pub const LUNAR_EXE: &str = "/Applications/Lunar Client.app/Contents/MacOS/Lunar Client";

/// The default process snapshot shape: executable path only. The scan path
/// additionally refreshes the owning user (`scan_snapshot`) - and nothing
/// else, ever: argv carries the game's live access token (see the module
/// header, `exe_only_snapshot_leaves_argv_empty`, and
/// `scan_snapshot_leaves_argv_empty`).
fn exe_snapshot() -> System {
    let mut system = System::new();
    system.refresh_processes_specifics(
        ProcessesToUpdate::All,
        true,
        ProcessRefreshKind::nothing().with_exe(UpdateKind::Always),
    );
    system
}

/// Every pid whose executable path satisfies `matches`, from one snapshot.
fn pids_where(matches: impl Fn(&Path) -> bool) -> Vec<u32> {
    exe_snapshot()
        .processes()
        .iter()
        .filter(|(_, process)| process.exe().is_some_and(&matches))
        .map(|(pid, _)| pid.as_u32())
        .collect()
}

/// True when the Lunar Client Electron app is running. Lunar rewrites
/// `launcher.json` on exit, so setup must not touch that file while it is up.
#[cfg(target_os = "macos")]
pub fn is_lunar_running() -> bool {
    lunar_launcher_pid().is_some()
}

/// The launcher's PID when it is running, by the same exact executable-path
/// match. Consumed by the post-launch hide worker, which re-verifies the
/// pid's bundle identity before acting on it. The game JVM can never be
/// returned here: it runs from `~/.lunarclient/jre/<...>/bin/java`.
#[cfg(target_os = "macos")]
pub fn lunar_launcher_pid() -> Option<u32> {
    let wanted = Path::new(LUNAR_EXE);
    exe_snapshot()
        .processes()
        .iter()
        .find(|(_, process)| process.exe() == Some(wanted))
        .map(|(pid, _)| pid.as_u32())
}

/// Whether Lunar's launcher is running, or `Err` when that cannot be
/// determined. The distinction is load-bearing: `lunar_config::register`
/// refuses to touch `launcher.json` on `Err`, because an undeterminable state
/// collapsed into "not running" would let the writer race Lunar's own
/// exit-time rewrite of that file. `Ok(false)` is reserved for a completed
/// lookup that found no matching process.
#[cfg(target_os = "macos")]
pub fn lunar_running_checked() -> Result<bool, String> {
    Ok(is_lunar_running())
}

#[cfg(windows)]
pub fn lunar_running_checked() -> Result<bool, String> {
    Ok(!lunar_launcher_pids()?.is_empty())
}

/// Every Lunar launcher pid: the Electron main process AND its helpers, which
/// on Windows all run from the SAME executable path (unlike macOS, where the
/// helpers have their own binaries). The hide worker wants all of them - any
/// one of them can own a top-level window.
///
/// `Err` means "cannot determine", never "not running": `lunar_config::register`
/// refuses to touch `launcher.json` on `Err`, because an undeterminable state
/// collapsed into "not running" would let the writer race Lunar's own exit-time
/// rewrite of that file.
#[cfg(windows)]
pub fn lunar_launcher_pids() -> Result<Vec<u32>, String> {
    let root = lunar_programs_root(std::env::var_os("LOCALAPPDATA").as_deref())
        .map_err(|e| format!("Cannot tell whether Lunar is running: {e}"))?;
    Ok(pids_where(|exe| is_lunar_launcher_exe(exe, &root)))
}

#[cfg(windows)]
pub fn prism_launcher_pids() -> Vec<u32> {
    let Some(local) = std::env::var_os("LOCALAPPDATA") else {
        return Vec::new();
    };
    let wanted = PathBuf::from(local).join("Programs/PrismLauncher/prismlauncher.exe");
    pids_where(|exe| caseless_ends_with(exe, &wanted))
}

/// `%LOCALAPPDATA%\Programs`, rejecting every UNUSABLE value rather than only
/// an absent one: an empty or relative base would join into a path that can
/// never match a real process, the scan would complete as `false`, and the
/// config writer would fail open. Pure and injectable so the refusal matrix is
/// unit-testable on any platform.
#[cfg_attr(not(windows), allow(dead_code))]
fn lunar_programs_root(local_app_data: Option<&OsStr>) -> Result<PathBuf, String> {
    let base = local_app_data.ok_or("LOCALAPPDATA is not set.")?;
    if base.is_empty() {
        return Err("LOCALAPPDATA is empty.".to_string());
    }
    let base = Path::new(base);
    if !base.is_absolute() {
        return Err(format!(
            "LOCALAPPDATA is not an absolute path: {}",
            base.display()
        ));
    }
    Ok(base.join("Programs"))
}

/// Lunar's install DIRECTORY is not stable, so identity is
/// "`Lunar Client.exe` somewhere under `%LOCALAPPDATA%\Programs`" rather than
/// one hard-coded path. Measured 2026-08-14 on a live Windows 11 machine:
/// Lunar 3.7.15-ow installs to `...\Programs\Lunar Client\Lunar Client.exe`,
/// while this code previously required `...\Programs\lunarclient\...` - a
/// folder that did not exist, so the scan always completed as "not running"
/// and the `launcher.json` guard never fired.
///
/// The file name is still exact (caseless): `Uninstall Lunar Client.exe` and
/// `resources\elevate.exe` sit in that same folder and must never match.
#[cfg_attr(not(windows), allow(dead_code))]
fn is_lunar_launcher_exe(exe: &Path, programs_root: &Path) -> bool {
    caseless_has_prefix(exe, programs_root)
        && exe
            .file_name()
            .is_some_and(|name| caseless_component_eq(name, OsStr::new("Lunar Client.exe")))
}

/// True when Lunar's GAME JVM is running: any process whose executable lives
/// under `~/.lunarclient/jre/` and whose final components are the game java
/// binary (`bin/java` on macOS, `bin\javaw.exe` or `bin\java.exe` on Windows;
/// component-wise, so `notjava` can never match). Identity comes from the
/// executable path alone - the game argv carries the live access token and is
/// never requested, per this file's privacy contract. Consumed by
/// `lobby_state`: once the game quits, the mod stops writing `lobby.json`, so
/// the last roster on disk describes a world that no longer exists and must
/// not be rendered.
pub fn game_jvm_running(home: &Path) -> bool {
    !game_jvm_pids(home).is_empty()
}

/// The game JVM's pids, by the identity `game_jvm_running` describes. The
/// Windows hide worker uses them to recognise a CONSOLE window that belongs to
/// the game - and, just as importantly, to leave every OTHER window of that
/// same process (the Minecraft window itself) alone.
pub fn game_jvm_pids(home: &Path) -> Vec<u32> {
    game_jvm_scan(home).pids
}

/// A game-JVM enumeration that also answers for its own completeness.
pub struct GameJvmScan {
    pub pids: Vec<u32>,
    /// Whether "no game JVM was found" may be trusted as authoritative.
    /// Proven, not assumed, by two independent checks:
    ///
    /// 1. Reconciliation: every pid a SEPARATE raw kernel enumeration saw
    ///    must either appear in the snapshot or be provably dead
    ///    (`process_exists == DefinitelyGone`). A snapshot that silently
    ///    truncated (sysinfo's Windows iteration stops on ANY error) loses
    ///    live pids and fails this.
    /// 2. Per-process readability: a SAME-USER process whose executable
    ///    lookup failed could itself be the game JVM, so unless it is
    ///    provably dead the snapshot may not claim absence. Other users'
    ///    processes cannot be our game and are exempt (on Windows their
    ///    exe lookup routinely fails).
    ///
    /// `complete: false` never blocks discovery - found pids are still
    /// returned - it only downgrades "nothing found" to uncertainty.
    pub complete: bool,
}

pub fn game_jvm_scan(home: &Path) -> GameJvmScan {
    // Raw list FIRST: a pid that exits between the two enumerations probes
    // dead and is excused; one spawned between them appears only in the
    // snapshot and costs nothing.
    let raw = raw_pid_list();
    let jre_root = home.join(".lunarclient/jre");
    let system = scan_snapshot();
    let pids = system
        .processes()
        .iter()
        .filter(|(_, process)| {
            process
                .exe()
                .is_some_and(|exe| path_has_prefix(exe, &jre_root) && is_game_java_tail(exe))
        })
        .map(|(pid, _)| pid.as_u32())
        .collect();
    GameJvmScan {
        complete: scan_is_complete(&system, raw),
        pids,
    }
}

/// Snapshot for `game_jvm_scan`: executable path plus owning user, nothing
/// else. Argv stays off-limits exactly as in `exe_snapshot` (the canary
/// test covers this refresh shape too).
fn scan_snapshot() -> System {
    let mut system = System::new();
    system.refresh_processes_specifics(
        ProcessesToUpdate::All,
        true,
        ProcessRefreshKind::nothing()
            .with_exe(UpdateKind::Always)
            .with_user(UpdateKind::Always),
    );
    system
}

fn scan_is_complete(system: &System, raw: Option<Vec<u32>>) -> bool {
    use crate::process_liveness::{process_exists, IdentityCheck};
    let own_pid = sysinfo::Pid::from_u32(std::process::id());
    let Some(own) = system.process(own_pid) else {
        return false;
    };
    if own.exe().is_none_or(|e| e.as_os_str().is_empty()) {
        return false;
    }
    let Some(own_uid) = own.user_id() else {
        return false;
    };
    for (pid, process) in system.processes() {
        if *pid == own_pid {
            continue;
        }
        // PID 4 is Windows' kernel System process. It has neither a normal
        // user token nor an executable path, and it cannot be a Lunar JVM.
        // Treating that expected shape as an unreadable same-user process
        // makes every otherwise-complete Windows scan permanently uncertain.
        #[cfg(windows)]
        if is_windows_system_pid(pid.as_u32()) {
            continue;
        }
        // Only a KNOWN different user is exempt; a failed uid lookup could
        // be our own process and must fail closed like a failed exe lookup.
        let provably_other_user = process.user_id().is_some_and(|uid| uid != own_uid);
        if provably_other_user {
            continue;
        }
        if process.exe().is_none_or(|e| e.as_os_str().is_empty())
            && process_exists(pid.as_u32()) != IdentityCheck::DefinitelyGone
        {
            return false;
        }
    }
    let Some(raw) = raw else {
        return false;
    };
    raw.into_iter().all(|pid| {
        system.process(sysinfo::Pid::from_u32(pid)).is_some()
            || process_exists(pid) == IdentityCheck::DefinitelyGone
    })
}

#[cfg(windows)]
fn is_windows_system_pid(pid: u32) -> bool {
    pid == 4
}

/// Pid enumeration independent of the sysinfo snapshot - the reconciliation
/// source for `scan_is_complete`. Returns None on any failure (fails the
/// scan closed).
#[cfg(target_os = "macos")]
fn raw_pid_list() -> Option<Vec<u32>> {
    // libproc's byte-counted two-call pattern; same header family as the
    // proc_pidinfo binding in `process_liveness`.
    extern "C" {
        fn proc_listallpids(
            buffer: *mut std::ffi::c_void,
            buffersize: std::ffi::c_int,
        ) -> std::ffi::c_int;
    }
    const PID_BYTES: usize = std::mem::size_of::<i32>();
    unsafe {
        // BOTH returns are PID COUNTS: Apple's wrapper divides the kernel's
        // byte count by sizeof(int) before returning (libproc.c,
        // proc_listallpids). Only the buffersize ARGUMENT is bytes.
        let needed = proc_listallpids(std::ptr::null_mut(), 0);
        if needed <= 0 {
            return None;
        }
        // Headroom for processes spawned between the two calls.
        let cap = needed as usize + 64;
        let mut buf = vec![0i32; cap];
        let got = proc_listallpids(buf.as_mut_ptr().cast(), (cap * PID_BYTES) as std::ffi::c_int);
        if got <= 0 {
            return None;
        }
        let count = (got as usize).min(cap);
        Some(buf[..count].iter().filter(|p| **p > 0).map(|p| *p as u32).collect())
    }
}

#[cfg(windows)]
fn raw_pid_list() -> Option<Vec<u32>> {
    use windows_sys::Win32::System::ProcessStatus::K32EnumProcesses;
    let mut cap = 1024usize;
    loop {
        let mut buf = vec![0u32; cap];
        let mut used_bytes = 0u32;
        let ok = unsafe { K32EnumProcesses(buf.as_mut_ptr(), (cap * 4) as u32, &mut used_bytes) };
        if ok == 0 {
            return None;
        }
        let count = used_bytes as usize / 4;
        if count < cap {
            buf.truncate(count);
            buf.retain(|p| *p > 0);
            return Some(buf);
        }
        cap *= 2;
    }
}

#[cfg(all(not(windows), not(target_os = "macos")))]
fn raw_pid_list() -> Option<Vec<u32>> {
    // Any entry error fails the whole enumeration closed - a partial raw
    // list would silently weaken the reconciliation it exists to serve.
    let mut pids = Vec::new();
    for entry in std::fs::read_dir("/proc").ok()? {
        let entry = entry.ok()?;
        if let Some(pid) = entry.file_name().to_str().and_then(|n| n.parse::<u32>().ok()) {
            pids.push(pid);
        }
    }
    Some(pids)
}

// Per-platform path identity. macOS keeps the exact std comparisons it has
// always used; Windows dispatches to the caseless component logic below.

#[cfg(not(windows))]
fn path_has_prefix(path: &Path, prefix: &Path) -> bool {
    path.starts_with(prefix)
}

#[cfg(windows)]
fn path_has_prefix(path: &Path, prefix: &Path) -> bool {
    caseless_has_prefix(path, prefix)
}

#[cfg(not(windows))]
fn is_game_java_tail(exe: &Path) -> bool {
    exe.ends_with("bin/java")
}

#[cfg(windows)]
fn is_game_java_tail(exe: &Path) -> bool {
    caseless_ends_with(exe, Path::new("bin/javaw.exe"))
        || caseless_ends_with(exe, Path::new("bin/java.exe"))
}

// The caseless component logic itself is platform-neutral so its tests run
// everywhere; only the dispatch above is cfg'd. Unicode lowercasing can
// diverge from NTFS's own casing table for exotic non-ASCII names - accepted
// in PLAN.md - and is strictly safer than exact matching on Windows.

#[cfg_attr(not(windows), allow(dead_code))]
fn caseless_component_eq(a: &OsStr, b: &OsStr) -> bool {
    a.to_string_lossy().to_lowercase() == b.to_string_lossy().to_lowercase()
}

#[cfg_attr(not(windows), allow(dead_code))]
fn caseless_has_prefix(path: &Path, prefix: &Path) -> bool {
    let mut components = path.components();
    for want in prefix.components() {
        match components.next() {
            Some(got) if caseless_component_eq(got.as_os_str(), want.as_os_str()) => {}
            _ => return false,
        }
    }
    true
}

#[cfg_attr(not(windows), allow(dead_code))]
fn caseless_ends_with(path: &Path, tail: &Path) -> bool {
    let path: Vec<_> = path.components().collect();
    let tail: Vec<_> = tail.components().collect();
    if tail.len() > path.len() {
        return false;
    }
    path[path.len() - tail.len()..]
        .iter()
        .zip(&tail)
        .all(|(a, b)| caseless_component_eq(a.as_os_str(), b.as_os_str()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::process::Command;
    use sysinfo::Pid;

    const CANARY: &str = "COBBLIFY_CANARY_7F2A9C4E";

    /// Kills and reaps the canary child on every exit path, panic included.
    /// The child is single-process by construction on both platforms, so
    /// killing the tracked PID is complete cleanup - nothing to orphan.
    struct KillOnDrop {
        child: std::process::Child,
        /// Unix only: the held write end of the child's stdin pipe. While it
        /// lives, the shell stays blocked in its `read` builtin.
        _stdin: Option<std::process::ChildStdin>,
    }

    impl Drop for KillOnDrop {
        fn drop(&mut self) {
            let _ = self.child.kill();
            let _ = self.child.wait();
        }
    }

    /// The shell blocks on its own `read` BUILTIN against our pipe: one
    /// process, the token in its own argv, no external command is ever
    /// spawned, nothing can be exec-replaced or orphaned.
    #[cfg(unix)]
    fn spawn_canary() -> KillOnDrop {
        let mut child = Command::new("/bin/sh")
            .arg("-c")
            .arg(format!("{CANARY}=1; read _l"))
            .stdin(std::process::Stdio::piped())
            .spawn()
            .expect("spawn canary child");
        let stdin = child.stdin.take();
        KillOnDrop {
            child,
            _stdin: stdin,
        }
    }

    /// Single-process sleeper: `Start-Sleep` is an in-process cmdlet, the
    /// token rides in the tracked PID's own `-Command` argument, and there
    /// are no descendants (`cmd /C ping` was rejected for exactly that -
    /// killing the parent orphans `ping.exe`).
    #[cfg(windows)]
    fn spawn_canary() -> KillOnDrop {
        let child = Command::new("powershell")
            .args([
                "-NoProfile",
                "-Command",
                &format!("${CANARY}=1; Start-Sleep -Seconds 30"),
            ])
            .spawn()
            .expect("spawn canary child");
        KillOnDrop {
            child,
            _stdin: None,
        }
    }

    /// Regression test for the Phase 0 finding (H8): an exe-only refresh does not populate
    /// argv. It spawns its own throwaway child carrying a unique token in its command line,
    /// takes the production snapshot, and asserts the token is not reachable.
    ///
    /// This test calls `Process::cmd()` - the one place in the crate that may - precisely
    /// because it is proving the ABSENCE of argv. The positive control below refreshes argv
    /// deliberately, scoped to this test's own child PID only, and must surface the EXACT
    /// token, so that the main assertion cannot pass vacuously (e.g. if the OS simply
    /// refused to hand out any command line, or the child never carried the token at all).
    #[test]
    fn exe_only_snapshot_leaves_argv_empty() {
        let guard = spawn_canary();
        let pid = Pid::from_u32(guard.child.id());

        // Give the kernel a moment to publish the new process.
        std::thread::sleep(std::time::Duration::from_millis(250));

        let mut system = System::new();
        system.refresh_processes_specifics(
            ProcessesToUpdate::All,
            true,
            ProcessRefreshKind::nothing().with_exe(UpdateKind::Always),
        );
        let seen = system
            .process(pid)
            .expect("canary child must appear in the snapshot");
        assert!(
            seen.cmd().is_empty(),
            "exe-only refresh leaked argv: {:?}",
            seen.cmd()
        );

        // Positive control, this test's own child only, exact token required.
        let mut full = System::new();
        full.refresh_processes_specifics(
            ProcessesToUpdate::Some(&[pid]),
            true,
            ProcessRefreshKind::everything(),
        );
        let control = full.process(pid).expect("canary child still alive");
        assert!(
            control
                .cmd()
                .iter()
                .any(|arg| arg.to_string_lossy().contains(CANARY)),
            "positive control did not surface the canary token, so the main \
             assertion proves nothing: {:?}",
            control.cmd()
        );
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn lunar_detection_does_not_panic() {
        // Whatever the answer is on this machine, the call must be side-effect free.
        let _ = is_lunar_running();
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn pid_lookup_agrees_with_the_boolean() {
        assert_eq!(is_lunar_running(), lunar_launcher_pid().is_some());
    }

    #[test]
    fn running_check_is_side_effect_free() {
        // On macOS this wraps the exact-path scan; on a Windows CI runner
        // LOCALAPPDATA is set, so the real caseless scan runs. Either way the
        // call must complete without panicking or mutating anything.
        let _ = lunar_running_checked();
    }

    #[test]
    fn game_jvm_detection_is_side_effect_free_and_false_off_lunar_jres() {
        // A home dir that owns no ~/.lunarclient/jre can never host the game
        // JVM, whatever else runs on this machine.
        let empty = std::env::temp_dir().join("cobblify-no-such-home");
        assert!(!game_jvm_running(&empty));
    }

    #[test]
    fn lunar_root_rejects_every_unusable_localappdata() {
        // None, empty, and relative are all "cannot determine", never a
        // completed lookup - the fail-open the config writer must never see.
        assert!(lunar_programs_root(None).is_err());
        assert!(lunar_programs_root(Some(OsStr::new(""))).is_err());
        assert!(lunar_programs_root(Some(OsStr::new("AppData/Local"))).is_err());
    }

    #[test]
    fn lunar_root_joins_an_absolute_base() {
        #[cfg(windows)]
        let base = "C:\\Users\\Jane\\AppData\\Local";
        #[cfg(not(windows))]
        let base = "/Users/jane/AppData/Local";
        let root = lunar_programs_root(Some(OsStr::new(base))).unwrap();
        assert!(root.ends_with("Programs"), "{}", root.display());
    }

    /// Lunar's install folder name is not stable, so identity is the exe NAME
    /// under `%LOCALAPPDATA%\Programs`. Both observed layouts must match, and
    /// the neighbours that share that folder must not.
    #[test]
    fn lunar_launcher_exe_matches_every_observed_install_layout() {
        let root = Path::new("C:\\Users\\Jane\\AppData\\Local\\Programs");
        // Measured on a live machine, Lunar 3.7.15-ow:
        assert!(is_lunar_launcher_exe(
            Path::new("C:\\Users\\JANE\\AppData\\Local\\Programs\\Lunar Client\\Lunar Client.exe"),
            root
        ));
        // The older lowercase folder name this code used to hard-code:
        assert!(is_lunar_launcher_exe(
            Path::new("C:\\Users\\Jane\\AppData\\Local\\Programs\\lunarclient\\Lunar Client.exe"),
            root
        ));
        // Neighbours in that same folder, and anything outside Programs:
        assert!(!is_lunar_launcher_exe(
            Path::new("C:\\Users\\Jane\\AppData\\Local\\Programs\\Lunar Client\\Uninstall Lunar Client.exe"),
            root
        ));
        assert!(!is_lunar_launcher_exe(
            Path::new("C:\\Users\\Jane\\AppData\\Local\\Programs\\Lunar Client\\resources\\elevate.exe"),
            root
        ));
        assert!(!is_lunar_launcher_exe(
            Path::new("C:\\Games\\Lunar Client\\Lunar Client.exe"),
            root
        ));
    }

    #[test]
    fn caseless_predicates_ignore_component_case_only() {
        assert!(caseless_component_eq(
            OsStr::new("Lunar Client.EXE"),
            OsStr::new("lunar client.exe")
        ));
        assert!(!caseless_component_eq(
            OsStr::new("Lunar Clients.exe"),
            OsStr::new("lunar client.exe")
        ));
        assert!(caseless_has_prefix(
            Path::new("Users/JANE/.lunarclient/jre/x"),
            Path::new("users/jane/.lunarclient/jre")
        ));
        assert!(!caseless_has_prefix(
            Path::new("users/janet/.lunarclient/jre/x"),
            Path::new("users/jane")
        ));
        assert!(caseless_ends_with(
            Path::new("jre/x/bin/JAVAW.EXE"),
            Path::new("bin/javaw.exe")
        ));
        assert!(!caseless_ends_with(
            Path::new("jre/x/bin/notjavaw.exe"),
            Path::new("bin/javaw.exe")
        ));
    }

    /// Positive identity controls over real absolute Windows paths - these
    /// fail under the old case-sensitive `==`/`starts_with`/`ends_with` code
    /// and run for real in the Windows CI job.
    #[cfg(windows)]
    #[test]
    fn windows_identity_is_case_insensitive_for_absolute_paths() {
        let exe = Path::new("C:\\Users\\JANE\\.lunarclient\\jre\\abc\\bin\\javaw.exe");
        assert!(path_has_prefix(
            exe,
            Path::new("c:\\users\\Jane\\.lunarclient\\jre")
        ));
        assert!(is_game_java_tail(exe));
        assert!(is_game_java_tail(Path::new("C:\\x\\bin\\JAVA.EXE")));
        assert!(!is_game_java_tail(Path::new("C:\\x\\bin\\notjava.exe")));
        assert!(is_lunar_launcher_exe(
            Path::new("C:\\Users\\JANE\\AppData\\Local\\Programs\\lunarclient\\Lunar Client.exe"),
            &lunar_programs_root(Some(OsStr::new("c:\\users\\jane\\appdata\\local"))).unwrap()
        ));
    }

    #[cfg(windows)]
    #[test]
    fn the_kernel_system_pid_is_not_a_candidate_user_process() {
        assert!(is_windows_system_pid(4));
        assert!(!is_windows_system_pid(std::process::id()));
    }

    /// The completeness proof is live, not scripted: a real scan must pass
    /// the raw-list reconciliation and the same-user readability check on
    /// this host. If either enumeration degrades, `complete` goes false and
    /// the witness path reads Indeterminate instead of proving absence.
    #[test]
    fn a_real_scan_reconciles_to_complete_on_this_host() {
        let scan = game_jvm_scan(Path::new("/nonexistent-home-for-this-test"));
        assert!(scan.complete, "reconciled real snapshot reads complete");
        assert!(scan.pids.is_empty(), "no game JVM under a nonexistent home");
    }

    /// The reconciliation source must be a real, independent enumeration:
    /// it has to contain this very process.
    #[test]
    fn the_raw_pid_list_sees_this_process() {
        let raw = raw_pid_list().expect("raw enumeration available");
        assert!(raw.contains(&std::process::id()));
    }

    /// Cardinality lock for the raw enumeration's count semantics: it must
    /// be the same order of magnitude as the snapshot, not a divided prefix
    /// (`proc_listallpids` returns PID counts, not bytes - the confusion
    /// this test exists to catch).
    #[test]
    fn the_raw_pid_list_matches_snapshot_cardinality() {
        let raw = raw_pid_list().expect("raw enumeration available").len();
        let snapshot = scan_snapshot().processes().len();
        assert!(
            raw * 2 > snapshot,
            "raw enumeration ({raw}) is a divided prefix of the snapshot ({snapshot})"
        );
    }

    /// The argv ban holds for the scan snapshot's exe+user refresh shape
    /// exactly as it does for the exe-only shape (same canary discipline).
    #[test]
    fn scan_snapshot_leaves_argv_empty() {
        let guard = spawn_canary();
        let pid = Pid::from_u32(guard.child.id());
        std::thread::sleep(std::time::Duration::from_millis(250));
        let system = scan_snapshot();
        let seen = system
            .process(pid)
            .expect("canary child visible in the scan snapshot");
        assert!(
            seen.cmd().is_empty(),
            "exe+user refresh must never populate argv"
        );
    }
}
