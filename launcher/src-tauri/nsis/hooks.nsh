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

; Uninstalling the launcher must not leave Lunar Client pointed at a
; `-javaagent:` whose jar is about to be deleted - that is a Lunar that will
; not start. The exe undoes its own Lunar registration when it is run with
; this one argument, and reports the outcome as an exit code: 0 done, 3 Lunar
; is running (or cannot be determined), 4 another launcher process is alive,
; 1 an I/O error. Anything non-zero means NOTHING was changed, so the
; uninstall aborts and the user can quit Lunar and run it again. The
; template's own running-app check happens after this hook, which is why the
; exe checks for a live launcher itself.
!macro NSIS_HOOK_PREUNINSTALL
  ExecWait '"$INSTDIR\${MAINBINARYNAME}.exe" --uninstall-lunar-integration' $0
  ${If} $0 != 0
    MessageBox MB_OK|MB_ICONSTOP "Quit Lunar Client and Cobblify, then run the uninstaller again."
    Abort
  ${EndIf}
!macroend
