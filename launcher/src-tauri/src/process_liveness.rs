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

/// Presence of an already-bound writer PID. Birth mismatch is absent (PID reuse).
pub fn bound_writer_presence(pid: u32, expected_birth_ns: EpochNs) -> BoundPresence {
    match observe_process(pid) {
        None => BoundPresence::Absent,
        Some(obs) if obs.birth_ns != expected_birth_ns => BoundPresence::Absent,
        Some(obs) => match obs.liveness {
            Liveness::Alive => BoundPresence::Present,
            Liveness::Dead => BoundPresence::Absent,
            Liveness::Unavailable => BoundPresence::Unavailable,
        },
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

    fn bsd_info(pid: u32) -> Option<ProcBsdInfo> {
        if pid == 0 {
            return None;
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
            return None;
        }
        let info = unsafe { info.assume_init() };
        if info.pbi_status as i32 == SZOMB {
            return None;
        }
        Some(info)
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
        let ft = windows_sys::Win32::Foundation::FILETIME {
            dwLowDateTime: 0xD1C0_3E00,
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
