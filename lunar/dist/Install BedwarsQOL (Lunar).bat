@echo off
setlocal
REM Cobblify - one-click installer for Lunar Client (Windows).
REM Double-click this file. It copies the Weave loader + the mod into place and
REM creates a double-click launcher - no Lunar settings to touch.

set "DIR=%~dp0"

echo.
echo   Installing Cobblify for Lunar Client...

set "AGENT="
set "MOD="
set /a AGENTCOUNT=0
set /a MODCOUNT=0
for %%F in ("%DIR%Weave-Loader-Agent-*.jar") do set "AGENT=%%~nxF" & set /a AGENTCOUNT+=1
for %%F in ("%DIR%Cobblify-Lunar-*.jar") do set "MOD=%%~nxF" & set /a MODCOUNT+=1

if not "%AGENTCOUNT%"=="1" goto :badbundle
if not "%MODCOUNT%"=="1" goto :badbundle

if not exist "%USERPROFILE%\.weave\mods" mkdir "%USERPROFILE%\.weave\mods"
copy /Y "%DIR%%AGENT%" "%USERPROFILE%\.weave\%AGENT%" >nul
copy /Y "%DIR%%MOD%"   "%USERPROFILE%\.weave\mods\%MOD%" >nul

set "LAUNCHER=Launch Lunar (Cobblify).bat"
call :writelauncher "%DIR%%LAUNCHER%"
call :writelauncher "%USERPROFILE%\Desktop\%LAUNCHER%"

echo   Done.
echo.
echo   ------------------------------------------------------------
echo   A launcher named "%LAUNCHER%" was placed
echo   next to this installer AND on your Desktop.
echo   Use it every time you play - it starts Lunar with Cobblify loaded.
echo.
echo   Remaining steps (in Lunar, after launching):
echo.
echo    1. Log into Lunar Client.
echo    2. Pick version 1.8.9 and click Play.
echo    3. Turn Waypoints OFF inside your active Lunar settings profile.
echo    4. Press Right Shift in-game to open the Cobblify settings menu.
echo   ------------------------------------------------------------
echo.
pause
exit /b 0

:badbundle
echo   ERROR: Expected exactly one Weave-Loader-Agent-*.jar and exactly one
echo          Cobblify-Lunar-*.jar next to this installer
echo          (found %AGENTCOUNT% agent jar(s) and %MODCOUNT% mod jar(s)).
echo          Extract the bundle into a fresh, empty folder and run this again.
echo.
pause
exit /b 1

:writelauncher
set "L=%~1"
>  "%L%" echo @echo off
>> "%L%" echo REM Launch the official Lunar Client with Cobblify (Weave) injected.
>> "%L%" echo REM Fully quit Lunar first, then double-click this file.
>> "%L%" echo if not exist "%%USERPROFILE%%\.weave\%AGENT%" goto :noagent
>> "%L%" echo if not exist "%%LOCALAPPDATA%%\Programs\lunarclient\Lunar Client.exe" goto :nolunar
>> "%L%" echo echo If Lunar is already open, fully quit it first, then run this again.
>> "%L%" echo set JAVA_TOOL_OPTIONS=-javaagent:"%%USERPROFILE%%\.weave\%AGENT%"
>> "%L%" echo start "" "%%LOCALAPPDATA%%\Programs\lunarclient\Lunar Client.exe"
>> "%L%" echo exit /b 0
>> "%L%" echo :noagent
>> "%L%" echo echo ERROR: Weave agent not found at: %%USERPROFILE%%\.weave\%AGENT%
>> "%L%" echo echo Run the Cobblify installer again.
>> "%L%" echo pause
>> "%L%" echo exit /b 1
>> "%L%" echo :nolunar
>> "%L%" echo echo ERROR: Lunar Client not found at:
>> "%L%" echo echo   %%LOCALAPPDATA%%\Programs\lunarclient\Lunar Client.exe
>> "%L%" echo echo Install Lunar Client first, then run this again.
>> "%L%" echo pause
>> "%L%" echo exit /b 1
goto :eof
