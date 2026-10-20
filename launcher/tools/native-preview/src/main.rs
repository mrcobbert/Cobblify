//! Isolated GUI fixture host. No production backend modules, plugins or credentials.
use objc::{msg_send, sel, sel_impl};
use serde_json::{json, Value};
use std::io::Write;
use std::sync::Mutex;
use tauri::{webview::PageLoadEvent, Manager};

mod window_chrome;
struct Options {
    check: bool,
    chrome_check: bool,
    whats_new: bool,
    font: &'static str,
    scene: &'static str,
}
struct Fixtures {
    scene: String,
    launched: bool,
    token: u64,
    snapshots: Value,
}
const SCENES: &[&str] = &["home", "join", "lobby", "queueFours", "gameSolo", "gameDoubles", "unsupported"];
struct Preferences {
    overlay: bool,
    auto: bool,
    channel: String,
}

#[tauri::command]
fn window_top_inset(chrome: tauri::State<window_chrome::WindowChrome>) -> window_chrome::Snapshot { chrome.snapshot() }

fn ready_status() -> Value {
    json!({"state":"ready", "mod_version":"0.18.1", "targets":[
        {"kind":"lunar", "state":"ready", "message":"Lunar Client", "action":"none", "candidates":[], "has_setup_folder":false},
        {"kind":"forge", "state":"ready", "message":"Prism Forge 1.8.9", "action":"none", "candidates":[], "has_setup_folder":false}
    ]})
}
#[tauri::command]
fn status() -> Value { ready_status() }
#[tauri::command]
fn refresh_setup() -> Value {
    println!("GUI_TEST fixture_refresh_setup");
    ready_status()
}
#[tauri::command]
fn setup_state() -> Value { Value::Null }
#[tauri::command]
fn game_theme(options: tauri::State<Options>) -> Value { json!({"font":options.font,"accent":"deep-teal"}) }
#[tauri::command]
fn open_info(options: tauri::State<Options>, fixtures: tauri::State<Mutex<Fixtures>>) -> Value {
    json!({"version":"0.18.1-dev.0","updated":options.whats_new && fixtures.lock().unwrap().scene == "home","fresh":false})
}
#[tauri::command]
fn launch_preferences(prefs: tauri::State<Mutex<Preferences>>) -> Value {
    json!({"useExternalOverlay":prefs.lock().unwrap().overlay,"health":"valid"})
}
#[tauri::command]
fn set_use_external_overlay(enabled: bool, prefs: tauri::State<Mutex<Preferences>>) -> Value {
    prefs.lock().unwrap().overlay = enabled;
    json!({"status":"saved","useExternalOverlay":enabled})
}
fn update_prefs(prefs: &Preferences) -> Value {
    json!({"autoUpdateEnabled":prefs.auto,"autoUpdatePrompted":true,"updateChannel":prefs.channel,"health":"valid"})
}
#[tauri::command]
fn update_preferences(prefs: tauri::State<Mutex<Preferences>>) -> Value { update_prefs(&prefs.lock().unwrap()) }
#[tauri::command]
fn set_auto_update(enabled: bool, prefs: tauri::State<Mutex<Preferences>>) -> Value {
    let mut prefs = prefs.lock().unwrap();
    prefs.auto = enabled;
    let mut result = update_prefs(&prefs);
    result["status"] = json!("saved");
    result
}
#[tauri::command]
fn set_update_channel(channel: String, prefs: tauri::State<Mutex<Preferences>>) -> Result<Value, String> {
    if channel != "stable" && channel != "dev" { return Err("invalid channel".into()); }
    let mut prefs = prefs.lock().unwrap();
    prefs.channel = channel;
    let mut result = update_prefs(&prefs);
    result["status"] = json!("saved");
    Ok(result)
}
fn current_update(prefs: &Preferences) -> Value {
    let mut result = update_prefs(prefs);
    result["state"] = json!("current");
    result["currentVersion"] = json!("0.18.1-dev.0");
    result["channelLoaded"] = json!(true);
    result["channelSaving"] = json!(false);
    result
}
#[tauri::command]
fn update_status(prefs: tauri::State<Mutex<Preferences>>) -> Value { current_update(&prefs.lock().unwrap()) }
#[tauri::command]
fn check_for_update(prefs: tauri::State<Mutex<Preferences>>) -> Value { current_update(&prefs.lock().unwrap()) }
#[tauri::command]
fn lunar_version_ready() -> &'static str { "ready" }
fn disabled_launch() -> Value {
    json!({"status":"rejected", "code":"gui_test_only", "message":"GUI test only: starting games is disabled."})
}
fn fixture_launch(fixtures: &Mutex<Fixtures>) -> Value {
    let mut state = fixtures.lock().unwrap();
    if state.scene == "home" { return disabled_launch(); }
    state.launched = true;
    json!({"status":"launched", "outcome":{"useExternalOverlay":true,"action":"game_launch_requested"}})
}
#[tauri::command]
fn launch_lunar(fixtures: tauri::State<Mutex<Fixtures>>) -> Value { fixture_launch(&fixtures) }
#[tauri::command]
fn launch_forge(fixtures: tauri::State<Mutex<Fixtures>>) -> Value { fixture_launch(&fixtures) }
#[tauri::command]
fn launch_progress(fixtures: tauri::State<Mutex<Fixtures>>) -> Value {
    json!({"stage":"settled", "percent":100, "loaded":fixtures.lock().unwrap().launched})
}
#[tauri::command]
fn lobby_state(fixtures: tauri::State<Mutex<Fixtures>>) -> Value {
    let state = fixtures.lock().unwrap();
    if !state.launched || state.scene == "join" { return json!({"kind":"unavailable"}); }
    json!({"kind":"snapshot", "token":state.token, "snapshot":state.snapshots[&state.scene]})
}
#[tauri::command]
fn acknowledge_lobby_snapshot(token: u64, fixtures: tauri::State<Mutex<Fixtures>>) -> Result<(), String> {
    let state = fixtures.lock().unwrap();
    if !state.launched || token != state.token { return Err("stale fixture snapshot".into()); }
    Ok(())
}
#[tauri::command]
fn preview_scene(scene: Option<String>, fixtures: tauri::State<Mutex<Fixtures>>, prefs: tauri::State<Mutex<Preferences>>) -> Result<String, String> {
    let mut state = fixtures.lock().unwrap();
    if let Some(scene) = scene {
        if !SCENES.contains(&scene.as_str()) { return Err("unknown fixture scene".into()); }
        state.scene = scene;
        state.launched = false;
        state.token += 1;
        // The explicit test-view selection simulates an overlay-enabled session.
        prefs.lock().unwrap().overlay = true;
    }
    Ok(state.scene.clone())
}
#[tauri::command]
fn reset_session_end(prefs: tauri::State<Mutex<Preferences>>) -> Value {
    json!({"status":"ok","preferences":{"useExternalOverlay":prefs.lock().unwrap().overlay,"health":"valid"}})
}
#[tauri::command]
fn abort_launch_session(prefs: tauri::State<Mutex<Preferences>>) -> Value { reset_session_end(prefs) }

#[tauri::command]
fn preview_report(message: String) { println!("GUI_TEST {message}"); }
#[tauri::command]
fn preview_fullscreen(window: tauri::WebviewWindow) -> Result<bool, String> {
    window.is_fullscreen().map_err(|e| e.to_string())
}
#[tauri::command]
fn preview_show(window: tauri::WebviewWindow, options: tauri::State<Options>) -> Result<(), String> {
    if !options.check { return Err("automated controls require --check".into()); }
    window.show().map_err(|e| e.to_string())?;
    window.set_focus().map_err(|e| e.to_string())
}
#[tauri::command]
fn preview_toggle_fullscreen(window: tauri::WebviewWindow, options: tauri::State<Options>) -> Result<(), String> {
    if !options.check { return Err("automated controls require --check".into()); }
    let native = window.clone();
    window.run_on_main_thread(move || unsafe {
        let ns = native.ns_window().unwrap() as *mut objc::runtime::Object;
        let _: () = msg_send![ns, toggleFullScreen: std::ptr::null_mut::<objc::runtime::Object>()];
    }).map_err(|e| e.to_string())
}
#[tauri::command]
fn preview_focus_cycle(window: tauri::WebviewWindow, options: tauri::State<Options>) -> Result<(), String> {
    if !options.check { return Err("automated controls require --check".into()); }
    let target = tauri::WebviewWindowBuilder::new(window.app_handle(), "gui-test-focus", tauri::WebviewUrl::External("about:blank".parse().unwrap()))
        .title("GUI test focus target").inner_size(100.0, 100.0).build().map_err(|e| e.to_string())?;
    target.set_focus().map_err(|e| e.to_string())?;
    std::thread::spawn(move || {
        std::thread::sleep(std::time::Duration::from_millis(350));
        let _ = target.close();
        let _ = window.show();
        let _ = window.set_focus();
    });
    Ok(())
}
#[tauri::command]
fn preview_finish(failed: bool, options: tauri::State<Options>) -> Result<(), String> {
    if !options.check { return Err("automated controls require --check".into()); }
    println!("GUI_TEST result={}", if failed {"FAIL"} else {"PASS"});
    let _ = std::io::stdout().flush();
    std::process::exit(if failed {1} else {0});
}
#[tauri::command]
fn preview_unavailable(options: tauri::State<Options>) -> Result<(), String> {
    if !options.check { return Err("automated controls require --check".into()); }
    println!("GUI_TEST result=UNAVAILABLE: WKWebView is hidden/occluded; run from the active GUI account");
    let _ = std::io::stdout().flush();
    std::process::exit(2);
}

fn main() {
    let mut options = Options { check: false, chrome_check: false, whats_new: false, font: "minecraft", scene: "home" };
    for arg in std::env::args().skip(1) {
        match arg.as_str() {
            "--check" => options.check = true,
            "--check-chrome" => { options.check = true; options.chrome_check = true; },
            "--whats-new" => options.whats_new = true,
            "--font=modern" => options.font = "modern",
            "--font=minecraft" => options.font = "minecraft",
            x if x.starts_with("--dashboard=") => {
                let scene = x.trim_start_matches("--dashboard=");
                options.scene = *SCENES.iter().find(|candidate| **candidate == scene)
                    .unwrap_or_else(|| { eprintln!("Unknown fixture scene: {scene}"); std::process::exit(2); });
            },
            // macOS Launch Services may supply a process serial number.
            x if x.starts_with("-psn_") => (),
            _ => { eprintln!("Usage: cobblify-gui-test [--check|--check-chrome] [--whats-new] [--font=minecraft|modern] [--dashboard=home|join|lobby|queueFours|gameSolo|gameDoubles|unsupported]"); std::process::exit(2); }
        }
    }
    let fixtures = Fixtures { scene: options.scene.into(), launched: false, token: 1,
        snapshots: serde_json::from_str(include_str!("../fixtures.json")).expect("embedded sample rosters") };
    tauri::Builder::default()
        .manage(options)
        .manage(Mutex::new(fixtures))
        .manage(Mutex::new(Preferences { overlay: true, auto: false, channel: "dev".into() }))
        .setup(|app| {
            let window = app.get_webview_window("main").ok_or("missing main window")?;
            // Match the shipping Mac native-window setup; frontend assets stay byte-identical.
            let content = window.inner_size()?;
            let frame = window.outer_size()?;
            let inset = frame.height.saturating_sub(content.height) as f64 / window.scale_factor()?;
            window.set_title_bar_style(tauri::TitleBarStyle::Overlay)?;
            window.set_size(frame)?;
            app.manage(window_chrome::install(&window, inset)?);
            println!("GUI_TEST fixtures only; no real game, settings, installs or updater backend");
            if app.state::<Options>().check {
                std::thread::spawn(move || {
                    std::thread::sleep(std::time::Duration::from_secs(90));
                    eprintln!("GUI_TEST timed out");
                    std::process::exit(2);
                });
            }
            Ok(())
        })
        .on_page_load(|window, payload| {
            if window.label() == "main" && payload.event() == PageLoadEvent::Finished {
                // A noninteractive badge identifies the safe fixture without moving production layout.
                let _ = window.eval("const label=document.createElement('div');label.textContent='GUI TEST • fixture data only';label.style.cssText='position:fixed;bottom:4px;left:6px;z-index:99999;pointer-events:none;font:10px system-ui;color:#9cb5b0;background:#1a1a1acc;padding:2px 4px';document.body.append(label);");
                let _ = window.eval(include_str!("../fixture-controls.js"));
                if window.state::<Options>().check {
                    let script = if window.state::<Options>().chrome_check { include_str!("../native-chrome-check.js") } else { include_str!("../native-check.js") };
                    if let Err(error) = window.eval(script) {
                        eprintln!("GUI_TEST cannot start check: {error}");
                        std::process::exit(2);
                    }
                }
            }
        })
        .invoke_handler(tauri::generate_handler![
            window_top_inset, status, refresh_setup, setup_state, game_theme, open_info,
            launch_preferences, set_use_external_overlay, update_preferences, set_auto_update,
            set_update_channel, update_status, check_for_update, lunar_version_ready,
            launch_lunar, launch_forge, launch_progress, lobby_state, acknowledge_lobby_snapshot,
            preview_scene, reset_session_end, abort_launch_session,
            preview_report, preview_fullscreen, preview_show, preview_toggle_fullscreen, preview_focus_cycle, preview_finish, preview_unavailable
        ])
        .run(tauri::generate_context!())
        .expect("GUI fixture runtime");
}
