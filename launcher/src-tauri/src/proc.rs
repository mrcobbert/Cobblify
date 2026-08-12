//! Process inspection - the privacy boundary.
//!
//! This is the ONLY file in the crate allowed to `use sysinfo`. Lunar's game JVM carries a
//! live Minecraft `--accessToken` in its argv, so the launcher must never hold a process
//! command line. The snapshot below requests the executable path and nothing else; Phase 0
//! proved empirically that this leaves `Process::cmd()` empty (see the canary test below).
//!
//! `System::new_all()`, `System::refresh_all()` and `ProcessRefreshKind::everything()` all
//! populate argv and must never appear in non-test code.

use std::path::Path;

use sysinfo::{ProcessRefreshKind, ProcessesToUpdate, System, UpdateKind};

/// Verified 2026-08-04: `pgrep -f` on this exact path returned one PID. Loose substring
/// matching hits 4+ Electron helper processes (GPU, renderer, network, crashpad), so the
/// comparison below is exact equality.
pub const LUNAR_EXE: &str = "/Applications/Lunar Client.app/Contents/MacOS/Lunar Client";

/// True when the Lunar Client Electron app is running. Lunar rewrites
/// `launcher.json` on exit, so setup must not touch that file while it is up.
pub fn is_lunar_running() -> bool {
    lunar_launcher_pid().is_some()
}

/// The launcher's PID when it is running, by the same exact executable-path
/// match. Consumed by the post-launch hide worker, which re-verifies the
/// pid's bundle identity before acting on it. The game JVM can never be
/// returned here: it runs from `~/.lunarclient/jre/<...>/bin/java`.
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

#[cfg(test)]
mod tests {
    use super::*;
    use std::process::Command;
    use sysinfo::Pid;

    /// Regression test for the Phase 0 finding (H8): an exe-only refresh does not populate
    /// argv. It spawns its own throwaway child carrying a unique token in its command line,
    /// takes the production snapshot, and asserts the token is not reachable.
    ///
    /// This test calls `Process::cmd()` - the one place in the crate that may - precisely
    /// because it is proving the ABSENCE of argv. The positive control below refreshes argv
    /// deliberately, scoped to this test's own child PID only, so that the main assertion
    /// cannot pass vacuously (e.g. if macOS simply refused to hand out any command line).
    #[test]
    fn exe_only_snapshot_leaves_argv_empty() {
        let mut child = Command::new("/bin/sh")
            .arg("-c")
            .arg("COBBLIFY_CANARY_7F2A9C4E=1; sleep 10")
            .spawn()
            .expect("spawn canary child");
        let pid = Pid::from_u32(child.id());

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

        // Positive control, this test's own child only.
        let mut full = System::new();
        full.refresh_processes_specifics(
            ProcessesToUpdate::Some(&[pid]),
            true,
            ProcessRefreshKind::everything(),
        );
        let control = full.process(pid).expect("canary child still alive");
        assert!(
            !control.cmd().is_empty(),
            "positive control saw no argv, so the main assertion proves nothing"
        );

        // Kill only our own child.
        let _ = child.kill();
        let _ = child.wait();
    }

    #[test]
    fn lunar_detection_does_not_panic() {
        // Whatever the answer is on this machine, the call must be side-effect free.
        let _ = is_lunar_running();
    }

    #[test]
    fn pid_lookup_agrees_with_the_boolean() {
        assert_eq!(is_lunar_running(), lunar_launcher_pid().is_some());
    }
}
