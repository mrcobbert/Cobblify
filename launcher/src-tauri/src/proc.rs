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
    let mut system = System::new();
    system.refresh_processes_specifics(
        ProcessesToUpdate::All,
        true,
        ProcessRefreshKind::nothing().with_exe(UpdateKind::Always),
    );
    let wanted = Path::new(LUNAR_EXE);
    system
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
    let wanted = lunar_exe_from(std::env::var_os("LOCALAPPDATA").as_deref())
        .map_err(|e| format!("Cannot tell whether Lunar is running: {e}"))?;
    let mut system = System::new();
    system.refresh_processes_specifics(
        ProcessesToUpdate::All,
        true,
        ProcessRefreshKind::nothing().with_exe(UpdateKind::Always),
    );
    Ok(system
        .processes()
        .iter()
        .any(|(_, process)| process.exe().is_some_and(|exe| paths_equal(exe, &wanted))))
}

/// Resolves the Lunar launcher executable under `%LOCALAPPDATA%`, rejecting
/// every UNUSABLE value rather than only an absent one: an empty or relative
/// base would join into a path that can never match a real process, the scan
/// would complete as `false`, and the config writer would fail open. Pure and
/// injectable so the refusal matrix is unit-testable on any platform.
#[cfg_attr(not(windows), allow(dead_code))]
fn lunar_exe_from(local_app_data: Option<&OsStr>) -> Result<PathBuf, String> {
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
    Ok(base
        .join("Programs")
        .join("lunarclient")
        .join("Lunar Client.exe"))
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
    let jre_root = home.join(".lunarclient/jre");
    let mut system = System::new();
    system.refresh_processes_specifics(
        ProcessesToUpdate::All,
        true,
        ProcessRefreshKind::nothing().with_exe(UpdateKind::Always),
    );
    system.processes().iter().any(|(_, process)| {
        process
            .exe()
            .is_some_and(|exe| path_has_prefix(exe, &jre_root) && is_game_java_tail(exe))
    })
}

// Per-platform path identity. macOS keeps the exact std comparisons it has
// always used; Windows dispatches to the caseless component logic below.

#[cfg(windows)]
fn paths_equal(a: &Path, b: &Path) -> bool {
    caseless_paths_equal(a, b)
}

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
fn caseless_paths_equal(a: &Path, b: &Path) -> bool {
    let (mut ca, mut cb) = (a.components(), b.components());
    loop {
        match (ca.next(), cb.next()) {
            (None, None) => return true,
            (Some(x), Some(y)) if caseless_component_eq(x.as_os_str(), y.as_os_str()) => {}
            _ => return false,
        }
    }
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
    fn lunar_exe_rejects_every_unusable_localappdata() {
        // None, empty, and relative are all "cannot determine", never a
        // completed lookup - the fail-open the config writer must never see.
        assert!(lunar_exe_from(None).is_err());
        assert!(lunar_exe_from(Some(OsStr::new(""))).is_err());
        assert!(lunar_exe_from(Some(OsStr::new("AppData/Local"))).is_err());
    }

    #[test]
    fn lunar_exe_joins_an_absolute_base() {
        #[cfg(windows)]
        let base = "C:\\Users\\Jane\\AppData\\Local";
        #[cfg(not(windows))]
        let base = "/Users/jane/AppData/Local";
        let exe = lunar_exe_from(Some(OsStr::new(base))).unwrap();
        assert!(exe.ends_with("Lunar Client.exe"), "{}", exe.display());
    }

    #[test]
    fn caseless_predicates_ignore_component_case_only() {
        assert!(caseless_paths_equal(
            Path::new("a/B/c.TXT"),
            Path::new("A/b/C.txt")
        ));
        assert!(!caseless_paths_equal(Path::new("a/b"), Path::new("a/b/c")));
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
        assert!(paths_equal(
            Path::new("C:\\Users\\JANE\\AppData\\Local\\Programs\\lunarclient\\Lunar Client.exe"),
            &lunar_exe_from(Some(OsStr::new("c:\\users\\jane\\appdata\\local"))).unwrap()
        ));
    }
}
