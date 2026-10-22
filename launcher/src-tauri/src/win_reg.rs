//! Read-only registry lookups (Windows): where an installer recorded a folder. Nothing here
//! ever writes the registry. Every failure (a missing key, a wrong type, access denied)
//! reads as nothing found.

use std::ffi::OsString;
use std::os::windows::ffi::{OsStrExt, OsStringExt};

use windows_sys::Win32::System::Registry::{
    RegCloseKey, RegEnumKeyExW, RegGetValueW, RegOpenKeyExW, HKEY, KEY_READ, KEY_WOW64_64KEY,
    RRF_RT_REG_SZ, RRF_SUBKEY_WOW6464KEY,
};

pub use windows_sys::Win32::System::Registry::{HKEY_CURRENT_USER, HKEY_LOCAL_MACHINE};

const ERROR_SUCCESS: u32 = 0;
const ERROR_MORE_DATA: u32 = 234;

fn wide(s: &str) -> Vec<u16> {
    std::ffi::OsStr::new(s).encode_wide().chain(std::iter::once(0)).collect()
}

/// A `REG_SZ` value, read from the 64-bit view.
pub fn string(root: HKEY, subkey: &str, value: &str) -> Option<String> {
    let subkey = wide(subkey);
    let value = wide(value);
    let flags = RRF_RT_REG_SZ | RRF_SUBKEY_WOW6464KEY;
    let mut bytes: u32 = 0;
    // SAFETY: valid NUL-terminated strings; a null buffer asks for the size only.
    let rc = unsafe {
        RegGetValueW(root, subkey.as_ptr(), value.as_ptr(), flags, std::ptr::null_mut(), std::ptr::null_mut(), &mut bytes)
    };
    if rc != ERROR_SUCCESS || bytes == 0 || bytes > 64 * 1024 {
        return None;
    }
    let mut buf = vec![0u16; (bytes as usize).div_ceil(2) + 1];
    let mut len = (buf.len() * 2) as u32;
    // SAFETY: `buf` holds `len` bytes.
    let rc = unsafe {
        RegGetValueW(root, subkey.as_ptr(), value.as_ptr(), flags, std::ptr::null_mut(), buf.as_mut_ptr().cast(), &mut len)
    };
    if rc != ERROR_SUCCESS {
        return None;
    }
    let units = (len as usize / 2).min(buf.len());
    let text = &buf[..units];
    let end = text.iter().position(|&c| c == 0).unwrap_or(text.len());
    let s = OsString::from_wide(&text[..end]).to_string_lossy().trim().to_string();
    (!s.is_empty()).then_some(s)
}

/// The names of a key's subkeys (64-bit view), or empty.
pub fn subkeys(root: HKEY, subkey: &str) -> Vec<String> {
    let path = wide(subkey);
    let mut key: HKEY = std::ptr::null_mut();
    // SAFETY: valid NUL-terminated path; `key` is written on success and closed below.
    let rc = unsafe { RegOpenKeyExW(root, path.as_ptr(), 0, KEY_READ | KEY_WOW64_64KEY, &mut key) };
    if rc != ERROR_SUCCESS {
        return Vec::new();
    }
    let mut out = Vec::new();
    for index in 0..1024u32 {
        // Key names are at most 255 characters.
        let mut name = [0u16; 256];
        let mut len = name.len() as u32;
        // SAFETY: `name` holds `len` UTF-16 units; the optional arguments are null.
        let rc = unsafe {
            RegEnumKeyExW(
                key,
                index,
                name.as_mut_ptr(),
                &mut len,
                std::ptr::null(),
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                std::ptr::null_mut(),
            )
        };
        if rc == ERROR_MORE_DATA {
            continue;
        }
        if rc != ERROR_SUCCESS {
            break;
        }
        out.push(OsString::from_wide(&name[..len as usize]).to_string_lossy().into_owned());
    }
    // SAFETY: `key` was opened above.
    unsafe { RegCloseKey(key) };
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Every Windows install has these; reading them proves both calls work end to end.
    #[test]
    fn reads_a_value_and_lists_subkeys_that_always_exist() {
        let root = string(
            HKEY_LOCAL_MACHINE,
            r"SOFTWARE\Microsoft\Windows NT\CurrentVersion",
            "SystemRoot",
        )
        .expect("SystemRoot");
        assert!(root.to_ascii_lowercase().ends_with("windows"), "{root}");
        let keys = subkeys(HKEY_LOCAL_MACHINE, r"SOFTWARE\Microsoft");
        assert!(keys.iter().any(|k| k.eq_ignore_ascii_case("Windows")), "{keys:?}");
    }

    #[test]
    fn anything_missing_reads_as_nothing() {
        assert_eq!(string(HKEY_CURRENT_USER, r"Software\Cobblify\No\Such\Key", "X"), None);
        assert_eq!(string(HKEY_LOCAL_MACHINE, r"SOFTWARE\Microsoft\Windows NT\CurrentVersion", "NoSuchValue"), None);
        // A DWORD value is not a string.
        assert_eq!(string(HKEY_LOCAL_MACHINE, r"SOFTWARE\Microsoft\Windows NT\CurrentVersion", "InstallDate"), None);
        assert!(subkeys(HKEY_CURRENT_USER, r"Software\Cobblify\No\Such\Key").is_empty());
    }
}
