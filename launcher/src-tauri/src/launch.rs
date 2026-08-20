//! Launch routes, preflight, and native dispatch.

use std::path::Path;
use std::process::Command;
use std::time::SystemTime;

use serde::Serialize;

use crate::forge;
use crate::lobby::preexisting_writer;
use crate::preferences::{LaunchPreferencesView, LockedPrefs};

/// Official Lunar play deep link when auto-join is on.
pub const PLAY_HYPIXEL: &str = "lunarclient://play?serverAddress=play.hypixel.net";

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LaunchOutcome {
    pub auto_join_hypixel: bool,
    pub action: LaunchAction,
}

#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum LaunchAction {
    GameLaunchRequested,
    LauncherOpened,
}

#[derive(Debug, Clone, Serialize)]
#[serde(tag = "status", rename_all = "snake_case")]
pub enum LaunchReply {
    Launched {
        outcome: LaunchOutcome,
        generation: u64,
    },
    PreexistingGame {
        preferences: LaunchPreferencesView,
    },
    Rejected {
        code: &'static str,
        #[serde(skip_serializing_if = "Option::is_none")]
        preferences: Option<LaunchPreferencesView>,
        #[serde(skip_serializing_if = "Option::is_none")]
        message: Option<String>,
    },
}

/// Internal launch result carrying the pre-dispatch freshness baseline.
pub struct LaunchAttempt {
    pub reply: LaunchReply,
    pub baseline: Option<SystemTime>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LaunchKind {
    Lunar,
    Forge,
}

pub struct DispatchResult {
    pub baseline: SystemTime,
    pub outcome: LaunchOutcome,
}

pub fn dispatch(
    home: &Path,
    kind: LaunchKind,
    auto_join: bool,
) -> Result<DispatchResult, String> {
    match kind {
        LaunchKind::Lunar => {
            let baseline = SystemTime::now();
            dispatch_lunar(auto_join, baseline)
        }
        LaunchKind::Forge => dispatch_forge(home, auto_join),
    }
}

fn dispatch_lunar(auto_join: bool, baseline: SystemTime) -> Result<DispatchResult, String> {
    if auto_join {
        open_play_hypixel()?;
        crate::hide::spawn_worker();
        Ok(DispatchResult {
            baseline,
            outcome: LaunchOutcome {
                auto_join_hypixel: true,
                action: LaunchAction::GameLaunchRequested,
            },
        })
    } else {
        open_lunar_app_only()?;
        Ok(DispatchResult {
            baseline,
            outcome: LaunchOutcome {
                auto_join_hypixel: false,
                action: LaunchAction::LauncherOpened,
            },
        })
    }
}

fn dispatch_forge(home: &Path, auto_join: bool) -> Result<DispatchResult, String> {
    let saved = forge::load_target(home).ok_or("Choose a Prism Forge instance before launching.")?;
    if saved.marker.as_deref() != Some("mmc-pack.json") {
        return Err(
            "The saved Forge target is not a Prism instance - set up Prism first.".to_string(),
        );
    }
    let fresh = forge::revalidate(&saved)
        .ok_or("That Prism instance changed - reopen Cobblify and set it up again.")?;
    let instance_id = fresh
        .game_dir
        .parent()
        .and_then(std::path::Path::file_name)
        .and_then(|n| n.to_str())
        .filter(|n| !n.is_empty())
        .ok_or("Cannot determine the Prism instance id.")?;

    let exe = forge::prism_exe();
    if !exe.is_file() {
        return Err("Prism Launcher is not installed in its standard location.".to_string());
    }

    let baseline = SystemTime::now();
    let mut cmd = Command::new(&exe);
    for arg in forge_launch_args(instance_id, auto_join) {
        cmd.arg(arg);
    }
    cmd.spawn()
        .map_err(|e| format!("Cannot start Prism Launcher: {e}"))?;
    crate::hide::spawn_prism_worker();
    Ok(DispatchResult {
        baseline,
        outcome: LaunchOutcome {
            auto_join_hypixel: auto_join,
            action: LaunchAction::GameLaunchRequested,
        },
    })
}

/// Prism argv shape — one helper for production dispatch and tests.
pub fn forge_launch_args(instance_id: &str, auto_join: bool) -> Vec<String> {
    let mut args = vec!["--launch".to_string(), instance_id.to_string()];
    if auto_join {
        args.push("--server".to_string());
        args.push("play.hypixel.net".to_string());
    }
    args
}

pub fn launch_with_preflight(
    home: &Path,
    kind: LaunchKind,
    expected_auto_join: bool,
    generation: u64,
) -> LaunchAttempt {
    let locked = match LockedPrefs::acquire(home) {
        Ok(l) => l,
        Err(e) => {
            return LaunchAttempt {
                reply: LaunchReply::Rejected {
                    code: "preference_error",
                    preferences: None,
                    message: Some(format!("{e:?}")),
                },
                baseline: None,
            };
        }
    };
    let prefs = locked.read_view();
    let strict = match locked.read_strict() {
        Ok(v) => v,
        Err(msg) => {
            return LaunchAttempt {
                reply: LaunchReply::Rejected {
                    code: "preference_error",
                    preferences: Some(prefs.clone()),
                    message: Some(msg),
                },
                baseline: None,
            };
        }
    };
    if strict != expected_auto_join {
        return LaunchAttempt {
            reply: LaunchReply::Rejected {
                code: "stale_preference",
                preferences: Some(prefs),
                message: None,
            },
            baseline: None,
        };
    }
    if preexisting_writer(home) {
        return LaunchAttempt {
            reply: LaunchReply::PreexistingGame { preferences: prefs },
            baseline: None,
        };
    }
    match dispatch(home, kind, strict) {
        Ok(result) => LaunchAttempt {
            reply: LaunchReply::Launched {
                outcome: result.outcome,
                generation,
            },
            baseline: Some(result.baseline),
        },
        Err(message) => LaunchAttempt {
            reply: LaunchReply::Rejected {
                code: "native_launch_error",
                preferences: Some(prefs),
                message: Some(message),
            },
            baseline: None,
        },
    }
}

#[cfg(target_os = "macos")]
fn open_play_hypixel() -> Result<(), String> {
    let status = Command::new("/usr/bin/open")
        .args(["-g", PLAY_HYPIXEL])
        .status()
        .map_err(|e| format!("Cannot start Lunar Client: {e}"))?;
    if !status.success() {
        return Err("Cannot start Lunar Client - is Lunar installed?".to_string());
    }
    Ok(())
}

#[cfg(windows)]
fn open_play_hypixel() -> Result<(), String> {
    tauri_plugin_opener::open_url(PLAY_HYPIXEL, None::<&str>)
        .map_err(|_| "Cannot start Lunar Client - is Lunar installed?".to_string())
}

#[cfg(not(any(target_os = "macos", windows)))]
fn open_play_hypixel() -> Result<(), String> {
    Err("Lunar launch is not supported on this platform.".to_string())
}

pub fn open_lunar_app_only() -> Result<(), String> {
    #[cfg(target_os = "macos")]
    {
        let status = Command::new("/usr/bin/open")
            .args(["-a", "Lunar Client"])
            .status()
            .map_err(|e| format!("Cannot open Lunar Client: {e}"))?;
        if !status.success() {
            return Err("Cannot open Lunar Client - is Lunar installed?".to_string());
        }
        return Ok(());
    }
    #[cfg(windows)]
    {
        let local = std::env::var_os("LOCALAPPDATA").ok_or("LOCALAPPDATA is not set.")?;
        let programs = std::path::PathBuf::from(local).join("Programs");
        let candidates = [
            programs.join("Lunar Client/Lunar Client.exe"),
            programs.join("lunarclient/Lunar Client.exe"),
        ];
        let exe = candidates
            .iter()
            .find(|p| p.is_file())
            .ok_or("Lunar Client is not installed.")?;
        Command::new(exe)
            .spawn()
            .map_err(|e| format!("Cannot open Lunar Client: {e}"))?;
        return Ok(());
    }
    #[cfg(not(any(target_os = "macos", windows)))]
    {
        Err("Opening Lunar Client is not supported on this platform.".to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn launch_reply_serializes_snake_case_tags() {
        use crate::preferences::{LaunchPreferencesView, PreferenceHealth};
        let pre = serde_json::to_value(LaunchReply::PreexistingGame {
            preferences: LaunchPreferencesView {
                auto_join_hypixel: true,
                health: PreferenceHealth::Valid,
                diagnostic: None,
            },
        })
        .unwrap();
        assert_eq!(pre["status"], "preexisting_game");
        let rejected = serde_json::to_value(LaunchReply::Rejected {
            code: "busy",
            preferences: None,
            message: None,
        })
        .unwrap();
        assert_eq!(rejected["status"], "rejected");
    }

    #[test]
    fn forge_on_includes_hypixel_server_once() {
        let args = forge_launch_args("instance-1", true);
        assert_eq!(args, vec!["--launch", "instance-1", "--server", "play.hypixel.net"]);
    }

    #[test]
    fn forge_off_omits_server_argument() {
        let args = forge_launch_args("instance-1", false);
        assert_eq!(args, vec!["--launch", "instance-1"]);
    }

    #[test]
    fn lunar_on_deep_link_is_fixed() {
        assert!(PLAY_HYPIXEL.contains("play.hypixel.net"));
        assert!(!PLAY_HYPIXEL.contains("forceRecommendedVersion"));
    }
}
