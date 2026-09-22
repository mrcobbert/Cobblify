use std::fs;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use base64::Engine;
use futures_util::StreamExt;
use minisign_verify::{PublicKey, Signature};
use reqwest::header::RANGE;
use semver::Version;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use tauri::{AppHandle, Emitter};
use tauri_plugin_updater::{Update, UpdaterExt};
use tokio::io::AsyncWriteExt;

use crate::preferences::{self, UpdateChannel};

const EVENT_NAME: &str = "updater://status";

/// How long the download may spend opening the connection.
const DOWNLOAD_CONNECT_TIMEOUT: Duration = Duration::from_secs(15);
/// How long the download may go without receiving any bytes. This is deliberately a per-read
/// budget, not a deadline on the whole transfer: a big bundle on a slow line is not a failure.
const DOWNLOAD_READ_TIMEOUT: Duration = Duration::from_secs(60);
/// How often the download loop may stop to scan for a running game and emit progress.
const CHUNK_REPORT_INTERVAL: Duration = Duration::from_millis(500);
/// ...and how much progress forces a report before that interval is up.
const CHUNK_REPORT_BYTES: u64 = 1024 * 1024;

#[derive(Clone)]
pub struct UpdaterService {
    inner: Arc<Mutex<Inner>>,
    pause_requested: Arc<AtomicBool>,
    home: PathBuf,
    endpoint: Option<&'static str>,
    token: Option<&'static str>,
    pubkey: Option<&'static str>,
}

#[derive(Clone)]
struct Inner {
    status: UpdateStatus,
    update: Option<Update>,
    task_running: bool,
    deferred_version: Option<String>,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateStatus {
    pub state: UpdateState,
    pub current_version: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub available_version: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub notes: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub release_notes_url: Option<String>,
    pub downloaded_bytes: u64,
    pub size_bytes: u64,
    pub critical: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub pause_reason: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub diagnostic_code: Option<String>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum UpdateState {
    Idle,
    Checking,
    Current,
    Available,
    Downloading,
    Paused,
    Ready,
    Installing,
    Error,
    CriticalRequired,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct SignedPolicy {
    minimum_supported_version: String,
}

#[derive(Debug)]
struct TrustedMetadata {
    sha256: String,
    size_bytes: u64,
    notes: Option<String>,
    release_notes_url: Option<String>,
    minimum_supported_version: String,
}

impl UpdaterService {
    pub fn new(home: PathBuf, current_version: impl Into<String>) -> Self {
        let current_version = current_version.into();
        Self {
            inner: Arc::new(Mutex::new(Inner {
                status: UpdateStatus {
                    state: UpdateState::Idle,
                    current_version,
                    available_version: None,
                    notes: None,
                    release_notes_url: None,
                    downloaded_bytes: 0,
                    size_bytes: 0,
                    critical: false,
                    pause_reason: None,
                    diagnostic_code: None,
                },
                update: None,
                task_running: false,
                deferred_version: None,
            })),
            pause_requested: Arc::new(AtomicBool::new(false)),
            home,
            endpoint: option_env!("COBBLIFY_UPDATE_URL"),
            token: option_env!("COBBLIFY_UPDATE_TOKEN"),
            pubkey: option_env!("COBBLIFY_UPDATER_PUBKEY"),
        }
    }

    pub fn status(&self) -> UpdateStatus {
        self.inner
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .status
            .clone()
    }

    pub fn blocks_launch(&self) -> bool {
        let status = self.status();
        status.critical && status.state != UpdateState::Installing
    }

    fn configured(&self) -> Result<(&'static str, &'static str, &'static str), &'static str> {
        match (self.endpoint, self.token, self.pubkey) {
            (Some(endpoint), Some(token), Some(pubkey))
                if endpoint.starts_with("https://") && !token.is_empty() && !pubkey.is_empty() =>
            {
                Ok((endpoint, token, pubkey))
            }
            _ => Err("updater_unconfigured"),
        }
    }

    fn cache_path(&self, version: &str) -> PathBuf {
        self.home
            .join(".cobblify/updates")
            .join(format!("{version}.bundle"))
    }

    fn partial_path(&self, version: &str) -> PathBuf {
        self.home
            .join(".cobblify/updates")
            .join(format!("{version}.partial"))
    }

    fn installed_marker_path(&self) -> PathBuf {
        self.home.join(".cobblify/updates/pending-version")
    }

    pub fn report_completed_install(&self) {
        let current_version = self.status().current_version;
        // Startup is the one moment we know which version is actually running, so it is where
        // bundles for that version and older ones stop being worth their disk space.
        if let Ok(current) = Version::parse(&current_version) {
            prune_update_cache(&self.home.join(".cobblify/updates"), &current);
        }
        let marker = self.installed_marker_path();
        let Ok(version) = fs::read_to_string(&marker) else {
            return;
        };
        if version.trim() == current_version {
            self.send_event("post_update_started");
            let _ = fs::remove_file(marker);
        }
    }

    fn set_status(&self, app: &AppHandle, mutate: impl FnOnce(&mut UpdateStatus)) -> UpdateStatus {
        let snapshot = {
            let mut inner = self.inner.lock().unwrap_or_else(|e| e.into_inner());
            mutate(&mut inner.status);
            inner.status.clone()
        };
        let _ = app.emit(EVENT_NAME, &snapshot);
        snapshot
    }

    fn send_event(&self, event: &'static str) {
        let (endpoint, token, _) = match self.configured() {
            Ok(config) => config,
            Err(_) => return,
        };
        let status = self.status();
        let url = format!("{}/launcher/update-event", endpoint.trim_end_matches('/'));
        let platform = if cfg!(target_os = "macos") {
            "darwin"
        } else {
            std::env::consts::OS
        };
        let body = serde_json::json!({
            "event": event,
            "currentVersion": status.current_version,
            "targetVersion": status.available_version,
            "platform": platform,
            "arch": std::env::consts::ARCH,
        });
        tauri::async_runtime::spawn(async move {
            let _ = reqwest::Client::new()
                .post(url)
                .header("X-BedwarsQol-Token", token)
                .json(&body)
                .send()
                .await;
        });
    }

    async fn check(&self, app: &AppHandle, manual: bool) -> UpdateStatus {
        if self
            .inner
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .task_running
        {
            return self.status();
        }
        self.set_status(app, |status| {
            status.state = UpdateState::Checking;
            status.diagnostic_code = None;
        });
        let (endpoint, token, pubkey) = match self.configured() {
            Ok(config) => config,
            Err(code) => {
                return self.set_status(app, |status| {
                    status.state = if manual {
                        UpdateState::Error
                    } else {
                        UpdateState::Current
                    };
                    status.diagnostic_code = Some(code.into());
                });
            }
        };
        // Read the channel on every check so the checkbox takes effect without a restart.
        let channel = preferences::update_channel(&self.home);
        let endpoint = match endpoint_for(endpoint, channel).parse() {
            Ok(value) => value,
            Err(_) => return self.check_fail(app, "invalid_update_endpoint"),
        };
        let updater = match app
            .updater_builder()
            .pubkey(pubkey)
            .header("X-BedwarsQol-Token", token)
            .and_then(|builder| builder.endpoints(vec![endpoint]))
            .and_then(|builder| builder.build())
        {
            Ok(updater) => updater,
            Err(_) => return self.check_fail(app, "updater_build_failed"),
        };
        let update = match updater.check().await {
            Ok(Some(update)) => update,
            Ok(None) => {
                let mut inner = self.inner.lock().unwrap_or_else(|e| e.into_inner());
                inner.update = None;
                inner.status.state = UpdateState::Current;
                inner.status.available_version = None;
                inner.status.critical = false;
                inner.status.diagnostic_code = None;
                let snapshot = inner.status.clone();
                drop(inner);
                let _ = app.emit(EVENT_NAME, &snapshot);
                self.send_event("check_ok");
                return snapshot;
            }
            Err(_) => return self.check_fail(app, "check_failed"),
        };
        let trusted = match trusted_metadata(&update, pubkey) {
            Ok(metadata) => metadata,
            Err(code) => return self.check_fail(app, code),
        };
        let current = self.status().current_version;
        let critical =
            version_is_below(&current, &trusted.minimum_supported_version).unwrap_or(false);
        let cache = self.cache_path(&update.version);
        let cached_ready = verify_cached(&cache, &trusted.sha256, &update.signature, pubkey)
            .ok()
            .flatten()
            .is_some();
        let downloaded = if cached_ready {
            trusted.size_bytes
        } else {
            partial_len(&self.partial_path(&update.version))
        };
        let snapshot = {
            let mut inner = self.inner.lock().unwrap_or_else(|e| e.into_inner());
            inner.update = Some(update.clone());
            let deferred = suppress_deferred(
                inner.deferred_version.as_deref(),
                &update.version,
                critical,
                manual,
            );
            inner.status.state = if deferred {
                UpdateState::Current
            } else if cached_ready {
                UpdateState::Ready
            } else if critical {
                UpdateState::CriticalRequired
            } else {
                UpdateState::Available
            };
            inner.status.available_version = Some(update.version.clone());
            inner.status.notes = trusted.notes;
            inner.status.release_notes_url = trusted.release_notes_url;
            inner.status.downloaded_bytes = downloaded;
            inner.status.size_bytes = trusted.size_bytes;
            inner.status.critical = critical;
            inner.status.pause_reason = None;
            inner.status.diagnostic_code = None;
            inner.status.clone()
        };
        let _ = app.emit(EVENT_NAME, &snapshot);
        self.send_event("check_ok");
        snapshot
    }

    fn fail(&self, app: &AppHandle, code: &str) -> UpdateStatus {
        self.set_status(app, |status| {
            status.state = UpdateState::Error;
            status.diagnostic_code = Some(code.into());
        })
    }

    fn check_fail(&self, app: &AppHandle, code: &str) -> UpdateStatus {
        let snapshot = self.fail(app, code);
        self.send_event("check_failed");
        snapshot
    }
}

/// The metadata URL the Tauri updater expands. Stable launchers send exactly what they always
/// have; only the opt-in dev channel adds a query parameter, which the Worker reads to answer
/// with the newer of stable and dev.
fn endpoint_for(base: &str, channel: UpdateChannel) -> String {
    let mut endpoint = format!(
        "{}/launcher/update/{{{{target}}}}/{{{{arch}}}}/{{{{current_version}}}}",
        base.trim_end_matches('/')
    );
    if channel == UpdateChannel::Dev {
        endpoint.push_str("?channel=dev");
    }
    endpoint
}

fn version_is_below(current: &str, minimum: &str) -> Result<bool, ()> {
    let current = Version::parse(current).map_err(|_| ())?;
    let minimum = Version::parse(minimum).map_err(|_| ())?;
    Ok(current < minimum)
}

fn suppress_deferred(
    deferred: Option<&str>,
    available: &str,
    critical: bool,
    manual: bool,
) -> bool {
    !critical && !manual && deferred == Some(available)
}

fn decode_public_key(encoded: &str) -> Result<PublicKey, &'static str> {
    let decoded = base64::engine::general_purpose::STANDARD
        .decode(encoded)
        .map_err(|_| "invalid_public_key")?;
    let text = std::str::from_utf8(&decoded).map_err(|_| "invalid_public_key")?;
    PublicKey::decode(text).map_err(|_| "invalid_public_key")
}

fn decode_signature(encoded: &str) -> Result<Signature, &'static str> {
    let decoded = base64::engine::general_purpose::STANDARD
        .decode(encoded)
        .map_err(|_| "invalid_signature")?;
    let text = std::str::from_utf8(&decoded).map_err(|_| "invalid_signature")?;
    Signature::decode(text).map_err(|_| "invalid_signature")
}

fn trusted_metadata(update: &Update, pubkey: &str) -> Result<TrustedMetadata, &'static str> {
    let policy = update
        .raw_json
        .get("policy")
        .and_then(|v| v.as_str())
        .ok_or("missing_policy")?;
    let policy_signature = update
        .raw_json
        .get("policySignature")
        .and_then(|v| v.as_str())
        .ok_or("missing_policy_signature")?;
    let key = decode_public_key(pubkey)?;
    let signature = decode_signature(policy_signature)?;
    key.verify(policy.as_bytes(), &signature, true)
        .map_err(|_| "untrusted_policy")?;
    let signed: SignedPolicy = serde_json::from_str(policy).map_err(|_| "invalid_policy")?;
    let stated_minimum = update
        .raw_json
        .get("minimumSupportedVersion")
        .and_then(|v| v.as_str())
        .ok_or("missing_minimum_version")?;
    if signed.minimum_supported_version != stated_minimum || Version::parse(stated_minimum).is_err()
    {
        return Err("policy_mismatch");
    }
    let sha256 = update
        .raw_json
        .get("sha256")
        .and_then(|v| v.as_str())
        .ok_or("missing_sha256")?;
    if sha256.len() != 64
        || !sha256
            .bytes()
            .all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
    {
        return Err("invalid_sha256");
    }
    let size_bytes = update
        .raw_json
        .get("sizeBytes")
        .and_then(|v| v.as_u64())
        .ok_or("missing_size")?;
    Ok(TrustedMetadata {
        sha256: sha256.into(),
        size_bytes,
        notes: update.body.clone(),
        release_notes_url: update
            .raw_json
            .get("releaseNotesUrl")
            .and_then(|v| v.as_str())
            .map(str::to_owned),
        minimum_supported_version: signed.minimum_supported_version,
    })
}

fn partial_len(path: &Path) -> u64 {
    fs::metadata(path).map(|m| m.len()).unwrap_or(0)
}

/// Verifies a cached bundle and hands back the exact bytes that were hashed and signature
/// checked. Callers that install must use these bytes: re-reading the file would install
/// content that nothing verified.
fn verify_cached(
    path: &Path,
    expected_sha: &str,
    encoded_signature: &str,
    encoded_pubkey: &str,
) -> Result<Option<Vec<u8>>, &'static str> {
    let bytes = match fs::read(path) {
        Ok(bytes) => bytes,
        Err(_) => return Ok(None),
    };
    let digest = format!("{:x}", Sha256::digest(&bytes));
    if digest != expected_sha {
        return Ok(None);
    }
    let key = decode_public_key(encoded_pubkey)?;
    let signature = decode_signature(encoded_signature)?;
    key.verify(&bytes, &signature, true)
        .map_err(|_| "artifact_signature_failed")?;
    Ok(Some(bytes))
}

/// L14: drops cached bundles the running launcher can no longer install - anything at or
/// below the running version. Names that are not `<semver>.bundle`/`.partial` are left alone.
fn prune_update_cache(dir: &Path, current: &Version) {
    let Ok(entries) = fs::read_dir(dir) else {
        return;
    };
    for entry in entries.flatten() {
        let name = entry.file_name();
        let Some(name) = name.to_str() else {
            continue;
        };
        let Some(version) = name
            .strip_suffix(".bundle")
            .or_else(|| name.strip_suffix(".partial"))
        else {
            continue;
        };
        let Ok(version) = Version::parse(version) else {
            continue;
        };
        if version <= *current {
            let _ = fs::remove_file(entry.path());
        }
    }
}

/// L3: the one place the download builds its HTTP client. Bounds the connect and the per-read
/// wait; never the whole request, which would kill a download that is slow but alive.
fn download_client(connect: Duration, read: Duration) -> reqwest::Result<reqwest::Client> {
    reqwest::Client::builder()
        .connect_timeout(connect)
        .read_timeout(read)
        .build()
}

/// L12: keeps the per-chunk work in the download loop down to one report per
/// `CHUNK_REPORT_INTERVAL` or per `CHUNK_REPORT_BYTES`, whichever comes first. The first
/// chunk always reports, so a download shows progress (and notices a game) immediately.
struct ChunkThrottle {
    last: Option<Instant>,
    last_bytes: u64,
}

impl ChunkThrottle {
    fn new() -> Self {
        Self {
            last: None,
            last_bytes: 0,
        }
    }

    fn due(&mut self, now: Instant, offset: u64) -> bool {
        let due = match self.last {
            None => true,
            Some(last) => {
                now.saturating_duration_since(last) >= CHUNK_REPORT_INTERVAL
                    || offset.saturating_sub(self.last_bytes) >= CHUNK_REPORT_BYTES
            }
        };
        if due {
            self.last = Some(now);
            self.last_bytes = offset;
        }
        due
    }
}

/// L13: the single decision both pause-for-a-running-game paths take. The download loop and
/// `install_update` must agree, or one of them pauses without anything to wake it up.
fn pause_for_game() -> (UpdateState, &'static str, bool) {
    (UpdateState::Paused, "game_active", true)
}

/// L13: re-checks once the game exits, and resumes the download if there is still one to do.
/// A cached bundle comes back as `Ready`, so this only restarts an unfinished download.
fn arm_game_exit_waiter(service: UpdaterService, app: AppHandle) {
    tauri::async_runtime::spawn(async move {
        while crate::proc::game_jvm_running(&service.home) {
            tokio::time::sleep(Duration::from_secs(2)).await;
        }
        if service.status().pause_reason.as_deref() == Some("game_active") {
            let next = service.check(&app, false).await;
            if matches!(
                next.state,
                UpdateState::Available | UpdateState::CriticalRequired
            ) {
                begin_download(app, &service);
            }
        }
    });
}

async fn download_update(service: UpdaterService, app: AppHandle) {
    let (update, pubkey, expected_sha, expected_size) = {
        let inner = service.inner.lock().unwrap_or_else(|e| e.into_inner());
        let Some(update) = inner.update.clone() else {
            return;
        };
        let Some(pubkey) = service.pubkey else {
            return;
        };
        let Ok(trusted) = trusted_metadata(&update, pubkey) else {
            return;
        };
        (update, pubkey, trusted.sha256, trusted.size_bytes)
    };
    let partial = service.partial_path(&update.version);
    let cache = service.cache_path(&update.version);
    if let Some(parent) = partial.parent() {
        if tokio::fs::create_dir_all(parent).await.is_err() {
            service.fail(&app, "cache_create_failed");
            finish_task(&service);
            return;
        }
    }
    let existing = partial_len(&partial);
    if existing == expected_size
        && verify_cached(&partial, &expected_sha, &update.signature, pubkey)
            .ok()
            .flatten()
            .is_some()
    {
        if cache.exists() {
            let _ = tokio::fs::remove_file(&cache).await;
        }
        if tokio::fs::rename(&partial, &cache).await.is_ok() {
            service.set_status(&app, |status| {
                status.state = UpdateState::Ready;
                status.downloaded_bytes = expected_size;
                status.pause_reason = None;
            });
            service.send_event("download_verified");
            finish_task(&service);
            return;
        }
    }
    let mut offset = if existing < expected_size {
        existing
    } else {
        0
    };
    let client = match download_client(DOWNLOAD_CONNECT_TIMEOUT, DOWNLOAD_READ_TIMEOUT) {
        Ok(client) => client,
        Err(_) => {
            service.fail(&app, "download_client_failed");
            finish_task(&service);
            return;
        }
    };
    let mut request = client.get(update.download_url.clone());
    if offset > 0 {
        request = request.header(RANGE, format!("bytes={offset}-"));
    }
    let response = match request.send().await {
        Ok(response) if response.status().is_success() => response,
        _ => {
            service.fail(&app, "download_failed");
            finish_task(&service);
            return;
        }
    };
    let append = offset > 0 && response.status() == reqwest::StatusCode::PARTIAL_CONTENT;
    if !append {
        offset = 0;
    }
    let mut options = tokio::fs::OpenOptions::new();
    options
        .create(true)
        .write(true)
        .append(append)
        .truncate(!append);
    let mut file = match options.open(&partial).await {
        Ok(file) => file,
        Err(_) => {
            service.fail(&app, "cache_write_failed");
            finish_task(&service);
            return;
        }
    };
    let mut stream = response.bytes_stream();
    let mut throttle = ChunkThrottle::new();
    while let Some(chunk) = stream.next().await {
        if service.pause_requested.load(Ordering::SeqCst) {
            service.set_status(&app, |status| {
                status.state = UpdateState::Paused;
                status.pause_reason = Some("manual".into());
            });
            finish_task(&service);
            return;
        }
        // The game scan and the status event are the expensive part of this loop; a chunk can
        // be a few kilobytes, so both are rate limited rather than run per chunk.
        let report = throttle.due(Instant::now(), offset);
        if report && crate::proc::game_jvm_running(&service.home) {
            let (state, reason, arm_waiter) = pause_for_game();
            service.set_status(&app, |status| {
                status.state = state;
                status.pause_reason = Some(reason.into());
            });
            service.send_event("download_paused");
            finish_task(&service);
            if arm_waiter {
                arm_game_exit_waiter(service.clone(), app.clone());
            }
            return;
        }
        let chunk = match chunk {
            Ok(chunk) => chunk,
            Err(_) => {
                service.fail(&app, "download_failed");
                finish_task(&service);
                return;
            }
        };
        if file.write_all(&chunk).await.is_err() {
            service.fail(&app, "cache_write_failed");
            finish_task(&service);
            return;
        }
        offset += chunk.len() as u64;
        if report {
            service.set_status(&app, |status| status.downloaded_bytes = offset);
        }
    }
    if file.flush().await.is_err() || offset != expected_size {
        service.fail(&app, "download_size_mismatch");
        finish_task(&service);
        return;
    }
    drop(file);
    if verify_cached(&partial, &expected_sha, &update.signature, pubkey)
        .ok()
        .flatten()
        .is_none()
    {
        service.fail(&app, "artifact_verification_failed");
        finish_task(&service);
        return;
    }
    if cache.exists() {
        let _ = tokio::fs::remove_file(&cache).await;
    }
    if tokio::fs::rename(&partial, &cache).await.is_err() {
        service.fail(&app, "cache_commit_failed");
        finish_task(&service);
        return;
    }
    service.set_status(&app, |status| {
        status.state = UpdateState::Ready;
        status.downloaded_bytes = expected_size;
        status.pause_reason = None;
    });
    service.send_event("download_verified");
    finish_task(&service);
}

fn finish_task(service: &UpdaterService) {
    service
        .inner
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .task_running = false;
}

#[tauri::command]
pub fn update_status(service: tauri::State<'_, UpdaterService>) -> UpdateStatus {
    let mut snapshot = service.status();
    if snapshot.state == UpdateState::Idle {
        snapshot.state = UpdateState::Current;
    }
    snapshot
}

#[tauri::command]
pub async fn check_for_update(
    app: AppHandle,
    service: tauri::State<'_, UpdaterService>,
    manual: bool,
) -> Result<UpdateStatus, String> {
    Ok(service.check(&app, manual).await)
}

fn begin_download(app: AppHandle, service: &UpdaterService) -> UpdateStatus {
    service.pause_requested.store(false, Ordering::SeqCst);
    let snapshot = {
        let mut inner = service.inner.lock().unwrap_or_else(|e| e.into_inner());
        if inner.task_running || inner.update.is_none() {
            return inner.status.clone();
        }
        inner.task_running = true;
        inner.status.state = UpdateState::Downloading;
        inner.status.pause_reason = None;
        inner.status.clone()
    };
    let _ = app.emit(EVENT_NAME, &snapshot);
    service.send_event("download_started");
    let owned = service.clone();
    tauri::async_runtime::spawn(download_update(owned, app));
    snapshot
}

#[tauri::command]
pub fn start_update(app: AppHandle, service: tauri::State<'_, UpdaterService>) -> UpdateStatus {
    begin_download(app, &service)
}

#[tauri::command]
pub fn pause_update(app: AppHandle, service: tauri::State<'_, UpdaterService>) -> UpdateStatus {
    if service.status().state != UpdateState::Downloading {
        return service.status();
    }
    service.pause_requested.store(true, Ordering::SeqCst);
    service.set_status(&app, |status| {
        status.state = UpdateState::Paused;
        status.pause_reason = Some("manual".into());
    });
    service.send_event("download_paused");
    service.status()
}

#[tauri::command]
pub async fn resume_update(
    app: AppHandle,
    service: tauri::State<'_, UpdaterService>,
) -> Result<UpdateStatus, String> {
    service.pause_requested.store(false, Ordering::SeqCst);
    if service
        .inner
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .task_running
    {
        return Ok(service.set_status(&app, |status| {
            status.state = UpdateState::Downloading;
            status.pause_reason = None;
        }));
    }
    let next = service.check(&app, false).await;
    if matches!(
        next.state,
        UpdateState::Available | UpdateState::CriticalRequired
    ) {
        Ok(begin_download(app, &service))
    } else {
        Ok(next)
    }
}

#[tauri::command]
pub fn defer_update(app: AppHandle, service: tauri::State<'_, UpdaterService>) -> UpdateStatus {
    let snapshot = {
        let mut inner = service.inner.lock().unwrap_or_else(|e| e.into_inner());
        if !inner.status.critical {
            inner.deferred_version = inner.status.available_version.clone();
            inner.status.state = UpdateState::Current;
        }
        inner.status.clone()
    };
    let _ = app.emit(EVENT_NAME, &snapshot);
    snapshot
}

#[tauri::command]
pub async fn install_update(
    app: AppHandle,
    service: tauri::State<'_, UpdaterService>,
    session: tauri::State<'_, crate::SessionParts>,
) -> Result<UpdateStatus, String> {
    let active_session = matches!(
        session
            .coordinator
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .state(),
        crate::lifecycle::Lifecycle::Launching | crate::lifecycle::Lifecycle::Active
    );
    if active_session || crate::proc::game_jvm_running(&service.home) {
        let (state, reason, arm_waiter) = pause_for_game();
        let snapshot = service.set_status(&app, |status| {
            status.state = state;
            status.pause_reason = Some(reason.into());
        });
        // Without this the row sat on "paused" until the user launched something else.
        if arm_waiter {
            arm_game_exit_waiter(UpdaterService::clone(&service), app);
        }
        return Ok(snapshot);
    }
    let (update, path) = {
        let inner = service.inner.lock().unwrap_or_else(|e| e.into_inner());
        let update = inner
            .update
            .clone()
            .ok_or_else(|| "No verified update is ready.".to_string())?;
        (update.clone(), service.cache_path(&update.version))
    };
    let pubkey = service
        .pubkey
        .ok_or_else(|| "Updater is not configured.".to_string())?;
    let trusted = trusted_metadata(&update, pubkey).map_err(str::to_string)?;
    // The installer gets the bytes that were just hashed and signature checked - reading the
    // file a second time would install whatever is there now, verified or not.
    let Some(bytes) = verify_cached(&path, &trusted.sha256, &update.signature, pubkey)
        .ok()
        .flatten()
    else {
        return Ok(service.fail(&app, "artifact_verification_failed"));
    };
    fs::write(service.installed_marker_path(), &update.version)
        .map_err(|_| "The post-update verification marker could not be written.".to_string())?;
    service.set_status(&app, |status| status.state = UpdateState::Installing);
    service.send_event("install_started");
    update
        .install(bytes)
        .map_err(|_| "The updater could not start installation.".to_string())?;
    // Windows never returns from install: the plugin hands off to the NSIS installer
    // and exits, and the installer relaunches us. macOS swaps the bundle in place and
    // returns, so without this the old process sits on "Installing…" forever.
    app.restart()
}

pub fn plugin() -> tauri::plugin::TauriPlugin<tauri::Wry, tauri_plugin_updater::Config> {
    let mut builder = tauri_plugin_updater::Builder::new();
    if let Some(pubkey) = option_env!("COBBLIFY_UPDATER_PUBKEY") {
        builder = builder.pubkey(pubkey);
    }
    builder.build()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stable_endpoint_is_unchanged_and_dev_adds_the_channel_query() {
        let stable = endpoint_for("https://stats.example/", UpdateChannel::Stable);
        assert_eq!(
            stable,
            "https://stats.example/launcher/update/{{target}}/{{arch}}/{{current_version}}"
        );
        let dev = endpoint_for("https://stats.example", UpdateChannel::Dev);
        assert_eq!(dev, format!("{stable}?channel=dev"));
        // Both are valid URLs once the updater expands the placeholders.
        let expanded = dev
            .replace("{{target}}", "windows")
            .replace("{{arch}}", "x86_64")
            .replace("{{current_version}}", "0.14.0");
        let url: reqwest::Url = expanded.parse().unwrap();
        assert_eq!(url.path(), "/launcher/update/windows/x86_64/0.14.0");
        assert_eq!(url.query(), Some("channel=dev"));
    }

    #[test]
    fn a_dev_build_is_below_its_release_and_above_the_previous_one() {
        assert_eq!(version_is_below("0.14.1-dev.41", "0.14.1"), Ok(true));
        assert_eq!(version_is_below("0.14.0", "0.14.1-dev.41"), Ok(true));
        assert_eq!(version_is_below("0.14.1-dev.41", "0.14.0"), Ok(false));
        assert_eq!(version_is_below("0.14.1-dev.41", "0.14.1-dev.9"), Ok(false));
        assert_eq!(version_is_below("0.14.1-dev.41", "0.0.0"), Ok(false));
    }

    #[test]
    fn bundled_tauri_config_can_initialize_the_updater_plugin() {
        let app_config: serde_json::Value =
            serde_json::from_str(include_str!("../tauri.conf.json")).unwrap();
        let updater_config = app_config
            .pointer("/plugins/updater")
            .cloned()
            .expect("tauri.conf.json must configure plugins.updater");

        assert_eq!(
            updater_config
                .pointer("/windows/installMode")
                .and_then(serde_json::Value::as_str),
            Some("passive"),
            "the Tauri bundler requires plugins.updater.windows to be an object"
        );
        assert!(
            updater_config
                .get("pubkey")
                .and_then(serde_json::Value::as_str)
                .is_some_and(|key| !key.is_empty()),
            "the Tauri bundler requires the updater public key in tauri.conf.json"
        );

        serde_json::from_value::<tauri_plugin_updater::Config>(updater_config)
            .expect("plugins.updater must deserialize before the launcher window starts");
    }

    #[test]
    fn minimum_version_is_semver_not_text_order() {
        assert_eq!(version_is_below("0.9.10", "0.10.0"), Ok(true));
        assert_eq!(version_is_below("0.10.0", "0.9.10"), Ok(false));
        assert!(version_is_below("not-a-version", "1.0.0").is_err());
    }

    #[test]
    fn unconfigured_builds_fail_closed_for_network_but_not_launch() {
        let service = UpdaterService::new(PathBuf::from("/tmp/cobblify-test"), "0.9.1");
        assert!(!service.blocks_launch());
        if option_env!("COBBLIFY_UPDATE_URL").is_none() {
            assert_eq!(service.configured(), Err("updater_unconfigured"));
        }
    }

    // Throwaway test key, generated 2026-09-22 for this cycle's fixtures. It signs nothing
    // that ships; the production key lives only in the release workflow's secrets.
    const TEST_PUBKEY: &str = "dW50cnVzdGVkIGNvbW1lbnQ6IG1pbmlzaWduIHB1YmxpYyBrZXk6IDM4REExMTE2OTZENzU4RDgKUldUWVdOZVdGaEhhT0FxYy9aWkthRVlGOW1xQnJncDdyWFhHTUR6MUxBaHl1YmQwRWI3TllicksK";
    const TEST_BUNDLE_SIGNATURE: &str = "dW50cnVzdGVkIGNvbW1lbnQ6IHNpZ25hdHVyZSBmcm9tIHRhdXJpIHNlY3JldCBrZXkKUlVUWVdOZVdGaEhhT0dnMTBqdnNNTW5DcE5relBaVHBHeVJtbmo4d2FXTEFoNUVXL1RjYnU2amtyZXZUa2Zicng4Q3o4aHNxcWgwQXhGYTdqTkYzdjVIWkVvd0Y2YmxVRlFVPQp0cnVzdGVkIGNvbW1lbnQ6IHRpbWVzdGFtcDoxNzkwMDY1NDM5CWZpbGU6YnVuZGxlLmJpbgo1ZGtQL21rZlRLaWVacFR1UVJMbjJpNlNoWmJOM3BFUzJmYzEvQklsd3BrTHpFbklUSGZEdEd1VkpjRWNweXBPQ3BlOEJRY0k5c1JYYm12N2lQMTFEZz09Cg==";
    const TEST_BUNDLE_SHA256: &str =
        "92439dd4e7652a0414d2cd9d220e8d2767ed71f9005327223fc5d12b7a5065c3";
    const TEST_BUNDLE_BYTES: &[u8] = b"update-bytes";

    /// L3: a body that trickles in over longer than the read timeout, with every gap shorter
    /// than it, must still arrive whole. The PRODUCTION constructor is under test - only its
    /// read timeout is shortened, so a whole-request deadline of the same length would fail.
    #[test]
    fn trickled_body_completes_under_read_timeout() {
        use std::io::{Read, Write};

        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let server = std::thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            let mut request = Vec::new();
            let mut buffer = [0u8; 512];
            loop {
                let read = stream.read(&mut buffer).unwrap();
                if read == 0 {
                    break;
                }
                request.extend_from_slice(&buffer[..read]);
                if request.windows(4).any(|window| window == b"\r\n\r\n") {
                    break;
                }
            }
            stream
                .write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 2000\r\nConnection: close\r\n\r\n")
                .unwrap();
            stream.flush().unwrap();
            for chunk in 0..8 {
                if chunk > 0 {
                    std::thread::sleep(Duration::from_millis(300));
                }
                stream.write_all(&[b'c'; 250]).unwrap();
                stream.flush().unwrap();
            }
        });

        let client = download_client(Duration::from_secs(15), Duration::from_millis(700))
            .expect("the production download client must build");
        let url = format!("http://{address}/Cobblify-Launcher.bundle");
        let started = std::time::Instant::now();
        let body = tauri::async_runtime::block_on(async move {
            let response = client.get(url).send().await.expect("request must reach the trickle server");
            response.bytes().await.expect("the trickled body must arrive whole")
        });
        let elapsed = started.elapsed();
        server.join().unwrap();

        assert_eq!(body.len(), 2000, "every trickled chunk must be read");
        assert!(
            elapsed >= Duration::from_millis(2100),
            "the body is meant to take longer than the read timeout to arrive, took {elapsed:?}"
        );
    }

    /// L8: the bytes the installer hands to Tauri must be the ones that were hashed and
    /// signature-checked, so `verify_cached` returns them instead of a bare yes/no.
    #[test]
    fn verify_cached_returns_the_bytes_it_verified() {
        let dir = tempfile::tempdir().unwrap();
        let bundle = dir.path().join("0.16.0.bundle");
        fs::write(&bundle, TEST_BUNDLE_BYTES).unwrap();

        let verified = verify_cached(&bundle, TEST_BUNDLE_SHA256, TEST_BUNDLE_SIGNATURE, TEST_PUBKEY)
            .expect("a signed bundle must verify")
            .expect("a signed bundle must be accepted");
        assert_eq!(verified, TEST_BUNDLE_BYTES);
        assert_eq!(verified, fs::read(&bundle).unwrap());

        let tampered = dir.path().join("tampered.bundle");
        fs::write(&tampered, b"other-bytes!").unwrap();
        assert_eq!(
            verify_cached(&tampered, TEST_BUNDLE_SHA256, TEST_BUNDLE_SIGNATURE, TEST_PUBKEY),
            Ok(None),
            "content that does not hash to the trusted sha256 is not a cache hit"
        );

        let wrong_sha = "0".repeat(64);
        assert_eq!(
            verify_cached(&bundle, &wrong_sha, TEST_BUNDLE_SIGNATURE, TEST_PUBKEY),
            Ok(None),
            "the trusted sha256 has to match the bytes on disk"
        );
    }

    /// L12: the per-chunk game scan and status event are the expensive part of the download
    /// loop. They may run at most once per 500 ms or per MiB, and always on the first chunk.
    #[test]
    fn chunk_throttle_reports_on_time_or_bytes() {
        let start = std::time::Instant::now();
        let mut throttle = ChunkThrottle::new();

        let mut due = 0;
        let mut offset = 0u64;
        for millis in 0..100u64 {
            offset += 10 * 1024;
            if throttle.due(start + Duration::from_millis(millis), offset) {
                due += 1;
            }
        }
        assert_eq!(
            due, 1,
            "100 chunks over 99 ms and under a MiB are one report: the first"
        );

        assert!(
            throttle.due(start + Duration::from_millis(100), offset + 1024 * 1024),
            "a MiB of progress reports before the timer is up"
        );
        assert!(
            !throttle.due(start + Duration::from_millis(101), offset + 1024 * 1024 + 1),
            "the byte budget restarts from the report that was just made"
        );
        assert!(
            throttle.due(
                start + Duration::from_millis(601),
                offset + 1024 * 1024 + 2
            ),
            "500 ms of progress reports before the byte budget is up"
        );
    }

    /// L13: both callers that find a game running take the same decision - pause with
    /// `game_active` AND arm the waiter that re-checks once the game exits.
    #[test]
    fn pause_for_game_pauses_and_arms_the_exit_waiter() {
        let (state, reason, arm_waiter) = pause_for_game();
        assert_eq!(state, UpdateState::Paused);
        assert_eq!(reason, "game_active");
        assert!(
            arm_waiter,
            "a pause nobody wakes up from leaves the update stuck until the next launch"
        );
    }

    /// L14: bundles for versions we are already running (or have passed) are dead weight.
    #[test]
    fn prune_removes_bundles_at_or_below_the_running_version() {
        let dir = tempfile::tempdir().unwrap();
        for name in [
            "0.15.0.bundle",
            "0.15.1.partial",
            "0.16.0.partial",
            "junk.bundle",
        ] {
            fs::write(dir.path().join(name), b"x").unwrap();
        }

        prune_update_cache(dir.path(), &Version::parse("0.15.1").unwrap());

        let mut left: Vec<String> = fs::read_dir(dir.path())
            .unwrap()
            .map(|entry| entry.unwrap().file_name().to_string_lossy().into_owned())
            .collect();
        left.sort();
        assert_eq!(left, vec!["0.16.0.partial".to_string(), "junk.bundle".to_string()]);
    }

    /// L3: the download client must bound the connect and per-read waits, never the whole
    /// request. A total deadline kills a slow-but-alive download, so re-adding one has to
    /// fail a test even though the 60 s case is not something a test can sit through.
    #[test]
    fn download_client_has_no_total_deadline() {
        let source = include_str!("updater.rs");
        let whole_request_deadline = format!(".{}(", "timeout");
        assert!(
            !source.contains(&whole_request_deadline),
            "updater.rs must bound connect and read waits only, never the whole request"
        );
    }

    #[test]
    fn later_hides_only_that_noncritical_version_for_the_current_run() {
        assert!(suppress_deferred(Some("1.2.0"), "1.2.0", false, false));
        assert!(!suppress_deferred(Some("1.2.0"), "1.2.1", false, false));
        assert!(!suppress_deferred(Some("1.2.0"), "1.2.0", true, false));
        assert!(!suppress_deferred(Some("1.2.0"), "1.2.0", false, true));
    }
}
