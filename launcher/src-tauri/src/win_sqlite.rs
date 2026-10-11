//! Read-only SQLite queries on Windows, through the SQLite that ships with Windows
//! (`System32\winsqlite3.dll`, Windows 10 1607 and later): no new crate, and no `sqlite3`
//! program (Windows has none). The library is loaded from System32 only, never from the
//! search path.
//!
//! The one caller is `lunar_profile`, which reads Lunar's `profiles.db` as the macOS build
//! does with `/usr/bin/sqlite3 -readonly`: the connection is opened read-only, a busy
//! database answers at once (no busy handler, so it never waits on Lunar), and every failure
//! reads as "unknown" (`None`).

use std::ffi::{c_char, c_int, c_uchar, c_void, CStr, CString};
use std::sync::OnceLock;

use windows_sys::Win32::System::LibraryLoader::{GetProcAddress, LoadLibraryExW, LOAD_LIBRARY_SEARCH_SYSTEM32};

const SQLITE_OK: c_int = 0;
const SQLITE_ROW: c_int = 100;
const SQLITE_DONE: c_int = 101;
const SQLITE_OPEN_READONLY: c_int = 0x0000_0001;
#[cfg(test)]
const SQLITE_OPEN_READWRITE: c_int = 0x0000_0002;
#[cfg(test)]
const SQLITE_OPEN_CREATE: c_int = 0x0000_0004;
const SQLITE_OPEN_URI: c_int = 0x0000_0040;

type Db = c_void;
type Stmt = c_void;

/// The C API, as winsqlite3 exports it ("system" = stdcall on 32-bit, the C ABI on 64-bit).
struct Api {
    open_v2: unsafe extern "system" fn(*const c_char, *mut *mut Db, c_int, *const c_char) -> c_int,
    prepare_v2: unsafe extern "system" fn(*mut Db, *const c_char, c_int, *mut *mut Stmt, *mut *const c_char) -> c_int,
    step: unsafe extern "system" fn(*mut Stmt) -> c_int,
    column_text: unsafe extern "system" fn(*mut Stmt, c_int) -> *const c_uchar,
    finalize: unsafe extern "system" fn(*mut Stmt) -> c_int,
    close_v2: unsafe extern "system" fn(*mut Db) -> c_int,
    #[cfg(test)]
    exec: unsafe extern "system" fn(*mut Db, *const c_char, *const c_void, *mut c_void, *mut *mut c_char) -> c_int,
}

fn api() -> Option<&'static Api> {
    static API: OnceLock<Option<Api>> = OnceLock::new();
    API.get_or_init(load).as_ref()
}

fn load() -> Option<Api> {
    let name: Vec<u16> = "winsqlite3.dll\0".encode_utf16().collect();
    // SAFETY: a valid NUL-terminated name; System32 only. The module is never freed.
    let module = unsafe { LoadLibraryExW(name.as_ptr(), std::ptr::null_mut(), LOAD_LIBRARY_SEARCH_SYSTEM32) };
    if module.is_null() {
        return None;
    }
    // SAFETY: each symbol is looked up by its exact exported name and cast to the signature
    // SQLite documents for it.
    unsafe {
        macro_rules! sym {
            ($name:literal) => {
                std::mem::transmute(GetProcAddress(module, concat!($name, "\0").as_ptr())?)
            };
        }
        Some(Api {
            open_v2: sym!("sqlite3_open_v2"),
            prepare_v2: sym!("sqlite3_prepare_v2"),
            step: sym!("sqlite3_step"),
            column_text: sym!("sqlite3_column_text"),
            finalize: sym!("sqlite3_finalize"),
            close_v2: sym!("sqlite3_close_v2"),
            #[cfg(test)]
            exec: sym!("sqlite3_exec"),
        })
    }
}

/// An open connection, closed when dropped.
struct Conn<'a> {
    api: &'a Api,
    db: *mut Db,
}

impl Drop for Conn<'_> {
    fn drop(&mut self) {
        // SAFETY: `db` came from open_v2 (close_v2 accepts a failed open's handle too).
        unsafe { (self.api.close_v2)(self.db) };
    }
}

fn open(target: &str, flags: c_int) -> Option<Conn<'static>> {
    let api = api()?;
    let target = CString::new(target).ok()?;
    let mut db: *mut Db = std::ptr::null_mut();
    // SAFETY: valid NUL-terminated name; `db` is written, and freed by Conn's drop.
    let rc = unsafe { (api.open_v2)(target.as_ptr(), &mut db, flags, std::ptr::null()) };
    let conn = Conn { api, db };
    (rc == SQLITE_OK && !db.is_null()).then_some(conn)
}

/// Runs one read-only query against `target` (a file name, or a `file:` URI) and returns
/// the first column of every row, one per line, as `sqlite3 -list -noheader` prints them
/// (NULL as an empty line). `None` on any failure.
pub fn query(target: &str, sql: &str) -> Option<String> {
    let conn = open(target, SQLITE_OPEN_READONLY | SQLITE_OPEN_URI)?;
    let api = conn.api;
    let sql = CString::new(sql).ok()?;
    let mut stmt: *mut Stmt = std::ptr::null_mut();
    // SAFETY: an open connection and a valid NUL-terminated statement.
    let rc = unsafe { (api.prepare_v2)(conn.db, sql.as_ptr(), -1, &mut stmt, std::ptr::null_mut()) };
    if rc != SQLITE_OK || stmt.is_null() {
        return None;
    }
    let mut out = String::new();
    let ok = loop {
        // SAFETY: `stmt` is a prepared statement of this connection.
        match unsafe { (api.step)(stmt) } {
            SQLITE_ROW => {
                // SAFETY: column 0 of the current row; the pointer lives until the next step.
                let text = unsafe { (api.column_text)(stmt, 0) };
                if !text.is_null() {
                    // SAFETY: SQLite returns a NUL-terminated UTF-8 string.
                    out.push_str(&unsafe { CStr::from_ptr(text.cast()) }.to_string_lossy());
                }
                out.push('\n');
            }
            SQLITE_DONE => break true,
            _ => break false,
        }
    };
    // SAFETY: finalizing the statement prepared above, once.
    unsafe { (api.finalize)(stmt) };
    ok.then_some(out)
}

/// Writes a database for the tests (Lunar's own file is never written).
#[cfg(test)]
pub fn exec_for_tests(path: &std::path::Path, sql: &str) {
    let conn = open(&path.to_string_lossy(), SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE).expect("open for writing");
    let sql = CString::new(sql).unwrap();
    // SAFETY: an open connection and a valid NUL-terminated script.
    let rc = unsafe { (conn.api.exec)(conn.db, sql.as_ptr(), std::ptr::null(), std::ptr::null_mut(), std::ptr::null_mut()) };
    assert_eq!(rc, SQLITE_OK, "exec failed");
}

/// Like `exec_for_tests`, but keeps the connection open until the returned guard drops, so
/// a WAL database keeps its rows in the `-wal` file (no checkpoint at close).
#[cfg(test)]
pub fn hold_open_for_tests(path: &std::path::Path, sql: &str) -> Box<dyn std::any::Any> {
    let conn = open(&path.to_string_lossy(), SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE).expect("open for writing");
    let sql = CString::new(sql).unwrap();
    // SAFETY: as in exec_for_tests.
    let rc = unsafe { (conn.api.exec)(conn.db, sql.as_ptr(), std::ptr::null(), std::ptr::null_mut(), std::ptr::null_mut()) };
    assert_eq!(rc, SQLITE_OK, "exec failed");
    Box::new(conn)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn windows_ships_sqlite_and_it_answers_a_query() {
        assert!(api().is_some(), "winsqlite3.dll loads from System32");
        let dir = tempfile::tempdir().unwrap();
        let db = dir.path().join("t.db");
        exec_for_tests(&db, "CREATE TABLE t (v TEXT); INSERT INTO t VALUES ('a'), (NULL), ('ü');");
        assert_eq!(query(&db.to_string_lossy(), "SELECT v FROM t ORDER BY rowid;").as_deref(), Some("a\n\nü\n"));
        assert_eq!(query(&db.to_string_lossy(), "SELECT v FROM t WHERE 0;").as_deref(), Some(""));
    }

    #[test]
    fn failures_read_as_unknown_and_nothing_is_created() {
        let dir = tempfile::tempdir().unwrap();
        let missing = dir.path().join("missing.db");
        assert_eq!(query(&missing.to_string_lossy(), "SELECT 1;"), None);
        assert!(!missing.exists(), "read-only never creates the file");
        let garbage = dir.path().join("garbage.db");
        std::fs::write(&garbage, b"not a database at all, not even close........").unwrap();
        assert_eq!(query(&garbage.to_string_lossy(), "SELECT 1;"), None);
        let db = dir.path().join("t.db");
        exec_for_tests(&db, "CREATE TABLE t (v TEXT);");
        assert_eq!(query(&db.to_string_lossy(), "SELECT nope FROM t;"), None, "a bad statement");
        assert_eq!(query(&db.to_string_lossy(), "INSERT INTO t VALUES ('x');"), None, "a write is refused");
    }
}
