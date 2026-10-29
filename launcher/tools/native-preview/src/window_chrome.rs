// The standalone build helper materializes this production module in the staging copy.
#[path = "../../../src-tauri/src/window_chrome.rs"]
mod implementation;
pub use implementation::*;
