//! Politely quitting Lunar's launcher, for setup's "Closing Lunar": Lunar must be quit before
//! Cobblify writes its `launcher.json`.
//!
//! Cobblify never hides, minimises or quits a Lunar window anywhere else.
//!
//! Identity is verified twice before anything is asked to quit: the pid comes from
//! `proc::lunar_launcher_pid()` (exact executable-path match) and the JXA snippet re-checks
//! that pid's bundle id before calling `terminate`. The game JVM fails both checks by
//! construction: it runs from a bare `~/.lunarclient/jre/<...>/bin/java` binary with no bundle.

use std::process::{Command, Stdio};

use crate::proc;

/// The polite quit request on its own: exe-verified pid, OS birth revalidated just before
/// the send, bundle id re-checked in the snippet. It does NOT wait - setup waits on its own
/// clock (15 s, re-checking before every write), which keeps the wait testable. The
/// terminate is a normal AppleEvent quit, the same thing Lunar's own "close launcher after
/// launch" setting does.
///
/// `Ok(())` = no launcher was running, or the request was delivered to it. `Err` = it
/// could not be delivered; the caller re-checks whether Lunar is still running before
/// treating that as a failure (the launcher may simply have exited meanwhile).
pub fn request_lunar_quit() -> Result<(), String> {
    let Some(pid) = proc::lunar_launcher_pid() else {
        return Ok(());
    };
    let Some(birth) = crate::process_liveness::process_birth_ns(pid) else {
        return Err("Cobblify cannot identify the running Lunar.".to_string());
    };
    match crate::process_liveness::check_identity(pid, birth) {
        crate::process_liveness::IdentityCheck::AliveSameIdentity => {}
        crate::process_liveness::IdentityCheck::DefinitelyGone => return Ok(()),
        crate::process_liveness::IdentityCheck::Indeterminate => {
            return Err("Cobblify cannot identify the running Lunar.".to_string())
        }
    }
    quit_request_result(terminate_pass(pid))
}

/// The snippet prints 1 when it asked exactly that pid to quit, 0 when no Lunar app
/// with that pid exists (any more); anything else is a failed osascript run.
fn quit_request_result(count: Option<u32>) -> Result<(), String> {
    match count {
        Some(1) => Ok(()),
        Some(_) => Err("Lunar could not be asked to quit.".to_string()),
        None => Err("Cobblify could not ask Lunar to quit.".to_string()),
    }
}

fn terminate_pass(pid: u32) -> Option<u32> {
    let out = Command::new("/usr/bin/osascript")
        .args(["-l", "JavaScript", "-e", &terminate_snippet(pid)])
        .stdin(Stdio::null())
        .output() // pipes stdout/stderr - nothing is inherited
        .ok()?;
    parse_count(&out.stdout)
}

/// `pid` is an integer straight from sysinfo - no injection surface.
///
/// Enumerates Lunar's bundle-matched applications and terminates ONLY the entry whose pid
/// equals the exe-verified one. (`runningApplicationWithProcessIdentifier` returns nil for
/// EVERY process via this bridge - measured against a live Lunar pid and against Finder.)
fn terminate_snippet(pid: u32) -> String {
    format!(
        r#"ObjC.import("AppKit"); const a = $.NSRunningApplication.runningApplicationsWithBundleIdentifier("com.moonsworth.client"); let n = 0; for (let i = 0; i < a.count; i++) {{ const p = a.objectAtIndex(i); if (p.processIdentifier === {pid}) {{ p.terminate; n = 1; }} }} n"#
    )
}

fn parse_count(stdout: &[u8]) -> Option<u32> {
    std::str::from_utf8(stdout).ok()?.trim().parse().ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_count_reads_the_snippet_output() {
        assert_eq!(parse_count(b"0\n"), Some(0));
        assert_eq!(parse_count(b"1\n"), Some(1));
        assert_eq!(parse_count(b" 1 \n"), Some(1));
        assert_eq!(parse_count(b""), None);
        assert_eq!(parse_count(b"garbage"), None);
        assert_eq!(parse_count(&[0xFF, 0xFE]), None);
    }

    /// The quit snippet only terminates - it never hides - and still acts on nothing but the
    /// exact exe-verified pid.
    #[test]
    fn the_quit_snippet_terminates_without_hiding_and_keeps_the_pid_guard() {
        let s = terminate_snippet(4242);
        assert!(!s.contains("hide"), "the quit snippet never hides");
        assert!(s.contains("p.terminate"), "the quit snippet terminates");
        assert!(s.contains("p.processIdentifier === 4242"), "pid guard kept");
        assert!(s.contains("com.moonsworth.client"), "bundle-id gate kept");
    }

    /// End-to-end check of the JXA-to-Rust numeric contract without Lunar: a pid far outside
    /// macOS's pid range matches nothing, so this is safe to run live and proves the snippet
    /// still parses and prints a parseable 0.
    #[test]
    fn quit_snippet_reports_zero_for_a_dead_pid() {
        assert_eq!(terminate_pass(99_999_999), Some(0));
    }

    /// Setup's request-only quit reports delivery exactly: 1 = asked, 0 = no such Lunar
    /// app, no output = osascript failed.
    #[test]
    fn the_setup_quit_request_reports_delivery() {
        assert_eq!(quit_request_result(Some(1)), Ok(()));
        assert!(quit_request_result(Some(0)).is_err());
        assert!(quit_request_result(None).is_err());
    }
}
