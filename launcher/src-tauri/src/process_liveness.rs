//! PID birth time and liveness — privacy-safe: no argv, no enumeration beyond one PID.

use std::time::{SystemTime, UNIX_EPOCH};

/// Normalized Unix-epoch nanoseconds for comparisons against launch baselines.
pub type EpochNs = i128;

pub const JVM_START_UPPER_NS: i128 = 120_000_000_000;
pub const JVM_START_FUTURE_TOLERANCE_NS: i128 = 2_000_000_000;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Liveness {
    Alive,
    Dead,
    Unavailable,
}

/// One native observation of a PID: birth time and liveness from the same handle/query.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ProcessObservation {
    pub birth_ns: EpochNs,
    pub liveness: Liveness,
}

/// Bound-writer poll outcome: absent starts/continues grace; unavailable is not death.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BoundPresence {
    Present,
    Absent,
    Unavailable,
}

/// Single observation of process birth and liveness (one handle on Windows).
pub fn observe_process(pid: u32) -> Option<ProcessObservation> {
    #[cfg(target_os = "macos")]
    {
        macos::observe(pid)
    }
    #[cfg(windows)]
    {
        windows::observe(pid)
    }
    #[cfg(all(not(windows), not(target_os = "macos")))]
    {
        linux_dev::observe(pid)
    }
}

/// Query whether `pid` is alive and its OS birth time matches `expected_birth_ns`.
pub fn verify_process(pid: u32, expected_birth_ns: EpochNs) -> Liveness {
    match observe_process(pid) {
        Some(obs) if obs.birth_ns == expected_birth_ns => obs.liveness,
        Some(_) => Liveness::Dead,
        None => Liveness::Unavailable,
    }
}

pub fn is_alive(pid: u32) -> Liveness {
    observe_process(pid)
        .map(|obs| obs.liveness)
        .unwrap_or(Liveness::Unavailable)
}

pub fn process_birth_ns(pid: u32) -> Option<EpochNs> {
    observe_process(pid).map(|obs| obs.birth_ns)
}

/// Three-way identity check: distinguishes definitive nonexistence (or PID
/// reuse) from a query the OS refused to answer. `Indeterminate` is always
/// the conservative bucket - callers must treat it as possibly-alive.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum IdentityCheck {
    AliveSameIdentity,
    DefinitelyGone,
    Indeterminate,
}

/// Pure errno mapping for Unix-family lookups: ESRCH (3, macOS
/// proc_pidinfo) and ENOENT (2, /proc reads on the Linux dev shim) are the
/// OS saying "no such process"; anything else is a refused/failed query.
pub(crate) fn identity_from_unix_errno(errno: Option<i32>) -> IdentityCheck {
    match errno {
        Some(2) | Some(3) => IdentityCheck::DefinitelyGone,
        _ => IdentityCheck::Indeterminate,
    }
}

/// Pure GetLastError mapping for a failed OpenProcess:
/// ERROR_INVALID_PARAMETER (87) means the PID does not exist;
/// ERROR_ACCESS_DENIED (5) and everything else is indeterminate.
pub(crate) fn identity_from_windows_error(code: u32) -> IdentityCheck {
    match code {
        87 => IdentityCheck::DefinitelyGone,
        _ => IdentityCheck::Indeterminate,
    }
}

/// Identity-aware liveness of `pid` against `expected_birth_ns`.
pub fn check_identity(pid: u32, expected_birth_ns: EpochNs) -> IdentityCheck {
    #[cfg(target_os = "macos")]
    {
        macos::check_identity(pid, expected_birth_ns)
    }
    #[cfg(windows)]
    {
        windows::check_identity(pid, expected_birth_ns)
    }
    #[cfg(all(not(windows), not(target_os = "macos")))]
    {
        linux_dev::check_identity(pid, expected_birth_ns)
    }
}

/// Three-way existence of a PID with no identity expectation, for the
/// scan-completeness reconciliation: `DefinitelyGone` is the ONLY answer
/// that may excuse a pid missing from a process snapshot; a refused or
/// ambiguous query is `Indeterminate` and fails the snapshot closed.
pub fn process_exists(pid: u32) -> IdentityCheck {
    #[cfg(target_os = "macos")]
    {
        macos::exists(pid)
    }
    #[cfg(windows)]
    {
        windows::exists(pid)
    }
    #[cfg(all(not(windows), not(target_os = "macos")))]
    {
        linux_dev::exists(pid)
    }
}

/// Presence of an already-bound writer PID, on the same identity semantics
/// as the reset guards: only definitive nonexistence (or PID reuse) counts
/// as absent; a refused query never feeds the death latch.
pub fn bound_writer_presence(pid: u32, expected_birth_ns: EpochNs) -> BoundPresence {
    match check_identity(pid, expected_birth_ns) {
        IdentityCheck::AliveSameIdentity => BoundPresence::Present,
        IdentityCheck::DefinitelyGone => BoundPresence::Absent,
        IdentityCheck::Indeterminate => BoundPresence::Unavailable,
    }
}

pub fn system_time_to_ns(time: SystemTime) -> Option<EpochNs> {
    time.duration_since(UNIX_EPOCH)
        .ok()
        .and_then(|d| i128::try_from(d.as_nanos()).ok())
}

pub fn millis_to_ns(ms: i64) -> Option<EpochNs> {
    if ms <= 0 {
        return None;
    }
    ms.checked_mul(1_000_000).map(|v| v as i128)
}

/// Strict JVM start clock predicate from the approved plan.
pub fn jvm_start_matches_os_birth(jvm_start_ms: i64, os_birth_ns: EpochNs, now_ns: EpochNs) -> bool {
    let Some(jvm_start_ns) = millis_to_ns(jvm_start_ms) else {
        return false;
    };
    if jvm_start_ns <= os_birth_ns {
        return false;
    }
    if jvm_start_ns > os_birth_ns.checked_add(JVM_START_UPPER_NS).unwrap_or(EpochNs::MAX) {
        return false;
    }
    if jvm_start_ns > now_ns.checked_add(JVM_START_FUTURE_TOLERANCE_NS).unwrap_or(EpochNs::MAX) {
        return false;
    }
    true
}

pub fn os_birth_at_or_after_baseline(os_birth_ns: EpochNs, baseline_ns: EpochNs) -> bool {
    os_birth_ns >= baseline_ns
}

#[cfg(target_os = "macos")]
mod macos {
    use super::EpochNs;
    use super::Liveness;
    use super::ProcessObservation;
    use std::mem::MaybeUninit;

    const PROC_PIDTBSDINFO: i32 = 3;
    const SZOMB: i32 = 5;

    #[repr(C)]
    struct ProcBsdInfo {
        pbi_flags: u32,
        pbi_status: u32,
        pbi_xstatus: u32,
        pbi_pid: u32,
        pbi_ppid: u32,
        pbi_uid: u32,
        pbi_gid: u32,
        pbi_ruid: u32,
        pbi_rgid: u32,
        pbi_svuid: u32,
        pbi_svgid: u32,
        pbi_rfu_1: u32,
        pbi_comm: [i8; 16],
        pbi_name: [i8; 32],
        pbi_nfiles: u32,
        pbi_pgid: u32,
        pbi_pjobc: u32,
        e_tdev: u32,
        e_tpgid: u32,
        pbi_nice: i32,
        pbi_start_tvsec: u64,
        pbi_start_tvusec: u64,
    }

    extern "C" {
        fn proc_pidinfo(
            pid: i32,
            flavor: i32,
            arg: u64,
            buffer: *mut std::ffi::c_void,
            buffersize: i32,
        ) -> i32;
    }

    enum BsdInfoOutcome {
        Info(ProcBsdInfo),
        /// The OS answered definitively: no such process (ESRCH), a zombie
        /// (exited, awaiting reap), or the invalid pid 0.
        Gone,
        /// The query itself failed (permissions, transient error).
        Unavailable,
    }

    fn bsd_info_checked(pid: u32) -> BsdInfoOutcome {
        if pid == 0 {
            return BsdInfoOutcome::Gone;
        }
        let mut info = MaybeUninit::<ProcBsdInfo>::uninit();
        let size = std::mem::size_of::<ProcBsdInfo>() as i32;
        let got = unsafe {
            proc_pidinfo(
                pid as i32,
                PROC_PIDTBSDINFO,
                0,
                info.as_mut_ptr().cast(),
                size,
            )
        };
        if got != size {
            let errno = std::io::Error::last_os_error().raw_os_error();
            return match super::identity_from_unix_errno(errno) {
                super::IdentityCheck::DefinitelyGone => BsdInfoOutcome::Gone,
                _ => BsdInfoOutcome::Unavailable,
            };
        }
        let info = unsafe { info.assume_init() };
        if info.pbi_status as i32 == SZOMB {
            return BsdInfoOutcome::Gone;
        }
        BsdInfoOutcome::Info(info)
    }

    fn bsd_info(pid: u32) -> Option<ProcBsdInfo> {
        match bsd_info_checked(pid) {
            BsdInfoOutcome::Info(info) => Some(info),
            _ => None,
        }
    }

    pub fn check_identity(pid: u32, expected_birth_ns: super::EpochNs) -> super::IdentityCheck {
        match bsd_info_checked(pid) {
            BsdInfoOutcome::Gone => super::IdentityCheck::DefinitelyGone,
            BsdInfoOutcome::Unavailable => super::IdentityCheck::Indeterminate,
            BsdInfoOutcome::Info(info) => {
                let sec = i128::from(info.pbi_start_tvsec);
                let usec = i128::from(info.pbi_start_tvusec);
                let birth_ns = sec
                    .checked_mul(1_000_000_000)
                    .and_then(|v| v.checked_add(usec.checked_mul(1_000)?));
                match birth_ns {
                    Some(b) if b == expected_birth_ns => super::IdentityCheck::AliveSameIdentity,
                    // A different birth is PID reuse: OUR writer is gone.
                    Some(_) => super::IdentityCheck::DefinitelyGone,
                    None => super::IdentityCheck::Indeterminate,
                }
            }
        }
    }

    pub fn observe(pid: u32) -> Option<ProcessObservation> {
        let info = bsd_info(pid)?;
        let sec = i128::from(info.pbi_start_tvsec);
        let usec = i128::from(info.pbi_start_tvusec);
        let birth_ns = sec
            .checked_mul(1_000_000_000)?
            .checked_add(usec.checked_mul(1_000)?)?;
        Some(ProcessObservation {
            birth_ns,
            liveness: Liveness::Alive,
        })
    }

    pub fn birth_ns(pid: u32) -> Option<EpochNs> {
        observe(pid).map(|obs| obs.birth_ns)
    }

    pub fn is_alive(pid: u32) -> Liveness {
        match bsd_info(pid) {
            Some(_) => Liveness::Alive,
            None => Liveness::Dead,
        }
    }

    /// Three-way existence: a zombie counts as gone (its game is over), a
    /// refused query is indeterminate, never gone.
    pub fn exists(pid: u32) -> super::IdentityCheck {
        match bsd_info_checked(pid) {
            BsdInfoOutcome::Info(_) => super::IdentityCheck::AliveSameIdentity,
            BsdInfoOutcome::Gone => super::IdentityCheck::DefinitelyGone,
            BsdInfoOutcome::Unavailable => super::IdentityCheck::Indeterminate,
        }
    }
}

#[cfg(windows)]
mod windows {
    use super::EpochNs;
    use super::Liveness;
    use super::ProcessObservation;
    use windows_sys::Win32::Foundation::{
        CloseHandle, FILETIME, HANDLE, WAIT_FAILED, WAIT_OBJECT_0, WAIT_TIMEOUT,
    };
    // SYNCHRONIZE is a generic access right (needed to wait on the handle), but windows-sys 0.61
    // only emits it under Win32_Storage_FileSystem, typed as FILE_ACCESS_RIGHTS. It is NOT in
    // Win32::System::Threading beside the process rights, where it reads as if it belongs.
    // FILE_ACCESS_RIGHTS and PROCESS_ACCESS_RIGHTS are both `u32` aliases, so the OR below is
    // well typed regardless of which module the constant came from.
    use windows_sys::Win32::Storage::FileSystem::SYNCHRONIZE;
    use windows_sys::Win32::System::Threading::{
        GetProcessTimes, OpenProcess, WaitForSingleObject, PROCESS_QUERY_LIMITED_INFORMATION,
    };

    pub(crate) fn filetime_to_ns(ft: FILETIME) -> Option<EpochNs> {
        let low = ft.dwLowDateTime as u64;
        let high = ft.dwHighDateTime as u64;
        let hundred_ns = ((high << 32) | low) as i128;
        const EPOCH_DIFF_100NS: i128 = 116_444_736_000_000_000;
        let unix_100ns = hundred_ns.checked_sub(EPOCH_DIFF_100NS)?;
        unix_100ns.checked_mul(100)
    }

    fn open_query(pid: u32) -> Option<HANDLE> {
        if pid == 0 {
            return None;
        }
        let handle = unsafe { OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION | SYNCHRONIZE, 0, pid) };
        if handle.is_null() {
            return None;
        }
        Some(handle)
    }

    /// Birth and liveness from one opened handle — avoids PID-reuse races between calls.
    pub fn observe(pid: u32) -> Option<ProcessObservation> {
        let handle = open_query(pid)?;
        let mut creation = FILETIME {
            dwLowDateTime: 0,
            dwHighDateTime: 0,
        };
        let mut exit = creation;
        let mut kernel = creation;
        let mut user = creation;
        let ok = unsafe {
            GetProcessTimes(
                handle,
                &mut creation,
                &mut exit,
                &mut kernel,
                &mut user,
            )
        };
        if ok == 0 {
            unsafe { CloseHandle(handle) };
            return None;
        }
        let birth_ns = match filetime_to_ns(creation) {
            Some(v) => v,
            None => {
                unsafe { CloseHandle(handle) };
                return None;
            }
        };
        let wait = unsafe { WaitForSingleObject(handle, 0) };
        unsafe { CloseHandle(handle) };
        let liveness = match wait {
            WAIT_TIMEOUT => Liveness::Alive,
            WAIT_OBJECT_0 => Liveness::Dead,
            WAIT_FAILED => Liveness::Unavailable,
            _ => Liveness::Unavailable,
        };
        Some(ProcessObservation {
            birth_ns,
            liveness,
        })
    }

    pub fn birth_ns(pid: u32) -> Option<EpochNs> {
        observe(pid).map(|obs| obs.birth_ns)
    }

    pub fn is_alive(pid: u32) -> Liveness {
        observe(pid)
            .map(|obs| obs.liveness)
            .unwrap_or(Liveness::Unavailable)
    }

    /// Three-way existence: an openable process object counts as existing
    /// (conservative - a terminated-but-held object reads as present, which
    /// can only fail a snapshot closed); a refused open maps through the
    /// same error split as identity checks.
    pub fn exists(pid: u32) -> super::IdentityCheck {
        if pid == 0 {
            return super::IdentityCheck::DefinitelyGone;
        }
        match open_query(pid) {
            Some(handle) => {
                unsafe { CloseHandle(handle) };
                super::IdentityCheck::AliveSameIdentity
            }
            None => {
                let code = unsafe { windows_sys::Win32::Foundation::GetLastError() };
                super::identity_from_windows_error(code)
            }
        }
    }

    pub fn check_identity(pid: u32, expected_birth_ns: EpochNs) -> super::IdentityCheck {
        if pid == 0 {
            return super::IdentityCheck::DefinitelyGone;
        }
        let handle = match open_query(pid) {
            Some(h) => h,
            None => {
                let code = unsafe { windows_sys::Win32::Foundation::GetLastError() };
                return super::identity_from_windows_error(code);
            }
        };
        let result = (|| {
            let mut creation = FILETIME {
                dwLowDateTime: 0,
                dwHighDateTime: 0,
            };
            let mut exit = creation;
            let mut kernel = creation;
            let mut user = creation;
            let ok = unsafe {
                GetProcessTimes(handle, &mut creation, &mut exit, &mut kernel, &mut user)
            };
            if ok == 0 {
                return super::IdentityCheck::Indeterminate;
            }
            let Some(birth_ns) = filetime_to_ns(creation) else {
                return super::IdentityCheck::Indeterminate;
            };
            if birth_ns != expected_birth_ns {
                // PID reuse: OUR writer is gone.
                return super::IdentityCheck::DefinitelyGone;
            }
            match unsafe { WaitForSingleObject(handle, 0) } {
                WAIT_TIMEOUT => super::IdentityCheck::AliveSameIdentity,
                WAIT_OBJECT_0 => super::IdentityCheck::DefinitelyGone,
                _ => super::IdentityCheck::Indeterminate,
            }
        })();
        unsafe { CloseHandle(handle) };
        result
    }
}

#[cfg(all(not(windows), not(target_os = "macos")))]
mod linux_dev {
    use super::EpochNs;
    use super::Liveness;
    use super::ProcessObservation;
    use std::fs;

    pub fn observe(pid: u32) -> Option<ProcessObservation> {
        let birth_ns = birth_ns(pid)?;
        Some(ProcessObservation {
            birth_ns,
            liveness: is_alive(pid),
        })
    }

    pub fn birth_ns(pid: u32) -> Option<EpochNs> {
        let stat = fs::read_to_string(format!("/proc/{pid}/stat")).ok()?;
        let end = stat.rfind(')')?;
        let rest = stat[end + 2..].split_whitespace().collect::<Vec<_>>();
        if rest.len() < 20 {
            return None;
        }
        let start_ticks: u64 = rest.get(19)?.parse().ok()?;
        let clk_tck = clock_ticks_per_sec();
        let sec = start_ticks / clk_tck;
        let rem = start_ticks % clk_tck;
        let boot = boot_epoch_sec()?;
        let birth_sec = boot.checked_add(sec)?;
        let ns = i128::from(birth_sec)
            .checked_mul(1_000_000_000)?
            .checked_add(i128::from(rem * (1_000_000_000 / clk_tck)))?;
        Some(ns)
    }

    pub fn is_alive(pid: u32) -> Liveness {
        let stat = match fs::read_to_string(format!("/proc/{pid}/stat")) {
            Ok(s) => s,
            Err(_) => return Liveness::Dead,
        };
        if stat.contains(" Z ") || stat.contains(") Z ") {
            return Liveness::Dead;
        }
        Liveness::Alive
    }

    pub fn check_identity(pid: u32, expected_birth_ns: EpochNs) -> super::IdentityCheck {
        if pid == 0 {
            return super::IdentityCheck::DefinitelyGone;
        }
        match fs::read_to_string(format!("/proc/{pid}/stat")) {
            Err(e) => super::identity_from_unix_errno(e.raw_os_error()),
            Ok(stat) => {
                if stat.contains(" Z ") || stat.contains(") Z ") {
                    return super::IdentityCheck::DefinitelyGone;
                }
                match birth_ns(pid) {
                    Some(b) if b == expected_birth_ns => super::IdentityCheck::AliveSameIdentity,
                    Some(_) => super::IdentityCheck::DefinitelyGone,
                    None => super::IdentityCheck::Indeterminate,
                }
            }
        }
    }

    /// Three-way existence; zombies are gone, an unreadable stat maps
    /// through the same errno split as identity checks.
    pub fn exists(pid: u32) -> super::IdentityCheck {
        if pid == 0 {
            return super::IdentityCheck::DefinitelyGone;
        }
        match fs::read_to_string(format!("/proc/{pid}/stat")) {
            Err(e) => super::identity_from_unix_errno(e.raw_os_error()),
            Ok(stat) => {
                if stat.contains(" Z ") || stat.contains(") Z ") {
                    return super::IdentityCheck::DefinitelyGone;
                }
                super::IdentityCheck::AliveSameIdentity
            }
        }
    }

    fn clock_ticks_per_sec() -> u64 {
        100
    }

    fn boot_epoch_sec() -> Option<u64> {
        let uptime = fs::read_to_string("/proc/uptime").ok()?;
        let up_secs: f64 = uptime.split_whitespace().next()?.parse().ok()?;
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .ok()?
            .as_secs();
        Some(now.saturating_sub(up_secs as u64))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn millis_to_ns_rejects_non_positive() {
        assert_eq!(millis_to_ns(0), None);
        assert_eq!(millis_to_ns(-1), None);
        assert_eq!(millis_to_ns(1_700_000_000_000), Some(1_700_000_000_000_000_000));
    }

    #[test]
    fn jvm_start_boundary_one_nanosecond_inside_and_outside() {
        let os = 1_000_000_000_000_000i128;
        let now = os + 1_000_000_000_000;
        let ms_inside = ((os + 2_000_000) / 1_000_000) as i64;
        assert!(jvm_start_matches_os_birth(ms_inside, os, now));
        let ms_equal = (os / 1_000_000) as i64;
        assert!(!jvm_start_matches_os_birth(ms_equal, os, now));
    }

    #[test]
    fn jvm_start_rejects_future_beyond_tolerance() {
        let os = 1_000_000_000_000_000i128;
        let now = os;
        let far_ns = now
            .checked_add(JVM_START_FUTURE_TOLERANCE_NS)
            .and_then(|v| v.checked_add(2_000_000))
            .unwrap();
        let far = (far_ns / 1_000_000) as i64;
        assert!(!jvm_start_matches_os_birth(far, os, now));
    }

    #[test]
    fn baseline_comparison_is_exact() {
        let baseline = 5_000_000_000i128;
        assert!(os_birth_at_or_after_baseline(baseline, baseline));
        assert!(!os_birth_at_or_after_baseline(baseline - 1, baseline));
    }

    #[cfg(windows)]
    #[test]
    fn windows_filetime_conversion_roundtrip_known_epoch() {
        // The Unix epoch as a FILETIME: 116_444_736_000_000_000 hundred-nanosecond ticks after
        // 1601-01-01, which is exactly EPOCH_DIFF_100NS, so this must convert to 0. Split as
        // high = value >> 32 = 0x019DB1DE, low = value & 0xFFFFFFFF = 0xD53E8000.
        let ft = windows_sys::Win32::Foundation::FILETIME {
            dwLowDateTime: 0xD53E_8000,
            dwHighDateTime: 0x019D_B1DE,
        };
        let ns = windows::filetime_to_ns(ft).expect("convert");
        assert_eq!(ns, 0);
    }

    #[test]
    fn bound_writer_presence_marks_missing_pid_absent() {
        assert_eq!(
            bound_writer_presence(999_999_999, 1),
            BoundPresence::Absent
        );
    }

    #[test]
    fn errno_mapping_is_conservative() {
        assert_eq!(identity_from_unix_errno(Some(3)), IdentityCheck::DefinitelyGone);
        assert_eq!(identity_from_unix_errno(Some(2)), IdentityCheck::DefinitelyGone);
        assert_eq!(identity_from_unix_errno(Some(1)), IdentityCheck::Indeterminate); // EPERM
        assert_eq!(identity_from_unix_errno(Some(13)), IdentityCheck::Indeterminate); // EACCES
        assert_eq!(identity_from_unix_errno(None), IdentityCheck::Indeterminate);
        assert_eq!(identity_from_windows_error(87), IdentityCheck::DefinitelyGone);
        assert_eq!(identity_from_windows_error(5), IdentityCheck::Indeterminate);
        assert_eq!(identity_from_windows_error(0), IdentityCheck::Indeterminate);
    }

    /// Native branch: a definitively nonexistent PID must map to gone, not
    /// indeterminate - a guard held on it would otherwise never prune.
    #[test]
    fn check_identity_native_branches() {
        assert_eq!(
            check_identity(999_999_999, 1),
            IdentityCheck::DefinitelyGone,
            "nonexistent pid is definitive on this OS"
        );
        let pid = std::process::id();
        let birth = process_birth_ns(pid).expect("self birth");
        assert_eq!(check_identity(pid, birth), IdentityCheck::AliveSameIdentity);
        assert_eq!(
            check_identity(pid, birth - 1),
            IdentityCheck::DefinitelyGone,
            "birth mismatch is PID reuse: our writer is gone"
        );
    }

    #[test]
    fn bound_writer_presence_marks_birth_mismatch_absent() {
        let pid = std::process::id();
        let birth = process_birth_ns(pid).expect("self birth");
        assert_eq!(
            bound_writer_presence(pid, birth - 1),
            BoundPresence::Absent
        );
    }

    #[test]
    fn verify_process_uses_single_observation_for_self() {
        let pid = std::process::id();
        let birth = process_birth_ns(pid).expect("self birth");
        assert_eq!(verify_process(pid, birth), Liveness::Alive);
        assert_eq!(verify_process(pid, birth - 1), Liveness::Dead);
    }

    #[test]
    fn current_process_birth_is_queryable_on_this_host() {
        let pid = std::process::id();
        let obs = observe_process(pid);
        assert!(obs.is_some(), "birth time for self");
        assert!(matches!(obs.unwrap().liveness, Liveness::Alive));
    }
}
