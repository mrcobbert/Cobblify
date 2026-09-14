; Tauri NSIS installer hooks (bundle.windows.nsis.installerHooks).
;
; An upgrade install copies the new bundle over the old one but never removes files the
; previous version shipped, so `resources\` ends up holding two versions of every jar.
; resources.rs deliberately refuses to run with a jar the manifest does not declare
; (reject_unexpected_jars), which turned every 0.9.9 -> 0.10.0 -> 0.10.1 upgrade into
; "This copy is incomplete". The installer owns that directory: clear it before the new
; files land. On a fresh install the directory does not exist and this is a no-op.
!macro NSIS_HOOK_PREINSTALL
  RMDir /r "$INSTDIR\resources"
!macroend
