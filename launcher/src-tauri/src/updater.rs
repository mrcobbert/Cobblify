use std::fs;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

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

const EVENT_NAME: &str = "updater://status";

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
        let marker = self.installed_marker_path();
        let Ok(version) = fs::read_to_string(&marker) else {
            return;
        };
        if version.trim() == self.status().current_version {
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
        let endpoint = format!(
            "{}/launcher/update/{{{{target}}}}/{{{{arch}}}}/{{{{current_version}}}}",
            endpoint.trim_end_matches('/')
        );
        let endpoint = match endpoint.parse() {
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
        let cached_ready =
            verify_cached(&cache, &trusted.sha256, &update.signature, pubkey).unwrap_or(false);
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

fn verify_cached(
    path: &Path,
    expected_sha: &str,
    encoded_signature: &str,
    encoded_pubkey: &str,
) -> Result<bool, &'static str> {
    let bytes = match fs::read(path) {
        Ok(bytes) => bytes,
        Err(_) => return Ok(false),
    };
    let digest = format!("{:x}", Sha256::digest(&bytes));
    if digest != expected_sha {
        return Ok(false);
    }
    let key = decode_public_key(encoded_pubkey)?;
    let signature = decode_signature(encoded_signature)?;
    key.verify(&bytes, &signature, true)
        .map_err(|_| "artifact_signature_failed")?;
    Ok(true)
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
        && verify_cached(&partial, &expected_sha, &update.signature, pubkey).unwrap_or(false)
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
    let client = match reqwest::Client::builder()
        .timeout(Duration::from_secs(60))
        .build()
    {
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
    while let Some(chunk) = stream.next().await {
        if service.pause_requested.load(Ordering::SeqCst) {
            service.set_status(&app, |status| {
                status.state = UpdateState::Paused;
                status.pause_reason = Some("manual".into());
            });
            finish_task(&service);
            return;
        }
        if crate::proc::game_jvm_running(&service.home) {
            service.set_status(&app, |status| {
                status.state = UpdateState::Paused;
                status.pause_reason = Some("game_active".into());
            });
            service.send_event("download_paused");
            finish_task(&service);
            let waiting_service = service.clone();
            let waiting_app = app.clone();
            tauri::async_runtime::spawn(async move {
                while crate::proc::game_jvm_running(&waiting_service.home) {
                    tokio::time::sleep(Duration::from_secs(2)).await;
                }
                if waiting_service.status().pause_reason.as_deref() == Some("game_active") {
                    let next = waiting_service.check(&waiting_app, false).await;
                    if matches!(
                        next.state,
                        UpdateState::Available | UpdateState::CriticalRequired
                    ) {
                        begin_download(waiting_app, &waiting_service);
                    }
                }
            });
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
        service.set_status(&app, |status| status.downloaded_bytes = offset);
    }
    if file.flush().await.is_err() || offset != expected_size {
        service.fail(&app, "download_size_mismatch");
        finish_task(&service);
        return;
    }
    drop(file);
    if !verify_cached(&partial, &expected_sha, &update.signature, pubkey).unwrap_or(false) {
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
        return Ok(service.set_status(&app, |status| {
            status.state = UpdateState::Paused;
            status.pause_reason = Some("game_active".into());
        }));
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
    if !verify_cached(&path, &trusted.sha256, &update.signature, pubkey).unwrap_or(false) {
        return Ok(service.fail(&app, "artifact_verification_failed"));
    }
    let bytes = tokio::fs::read(path)
        .await
        .map_err(|_| "Verified update cache could not be read.".to_string())?;
    fs::write(service.installed_marker_path(), &update.version)
        .map_err(|_| "The post-update verification marker could not be written.".to_string())?;
    let snapshot = service.set_status(&app, |status| status.state = UpdateState::Installing);
    service.send_event("install_started");
    update
        .install(bytes)
        .map_err(|_| "The updater could not start installation.".to_string())?;
    Ok(snapshot)
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

    #[test]
    fn later_hides_only_that_noncritical_version_for_the_current_run() {
        assert!(suppress_deferred(Some("1.2.0"), "1.2.0", false, false));
        assert!(!suppress_deferred(Some("1.2.0"), "1.2.1", false, false));
        assert!(!suppress_deferred(Some("1.2.0"), "1.2.0", true, false));
        assert!(!suppress_deferred(Some("1.2.0"), "1.2.0", false, true));
    }
}
