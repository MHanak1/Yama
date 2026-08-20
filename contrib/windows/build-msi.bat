@echo off
setlocal EnableDelayedExpansion

REM ============================================================================
REM  build-msi.bat  --  one-shot local Windows MSI build for Yama (dev / VM).
REM
REM  Installs every compile dependency via Chocolatey, stages the bundled libvlc,
REM  compiles the SMTC WinRT shim, then builds the MSI with Gradle / jpackage --
REM  i.e. it reproduces the whole .github/workflows/release.yml "windows" job on a
REM  local machine.
REM
REM  Just double-click it, or run it from any prompt: it self-elevates to admin
REM  (Chocolatey + the VS C++ Build Tools require it). The FIRST run downloads the
REM  VS C++ Build Tools (several GB); later runs are incremental and much faster.
REM
REM  Keep VLC_VERSION below in sync with .github/workflows/release.yml (VLC_VERSION).
REM ============================================================================

set "VLC_VERSION=3.0.21"
if not defined ChocolateyInstall set "ChocolateyInstall=%ProgramData%\chocolatey"

REM --- self-elevate to administrator ------------------------------------------
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo Requesting administrator privileges...
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)

REM --- cd to repo root (two levels up from contrib\windows\) -------------------
pushd "%~dp0..\.." || (echo [x] Could not locate repo root & exit /b 1)
if not exist gradlew.bat (echo [x] gradlew.bat not found in %CD% -- wrong location? & popd & exit /b 1)
echo Repo root: %CD%

REM Building on a VM shared folder (VirtualBox/VMware, usually a network drive letter) fails: those
REM filesystems don't support all the ops MSVC / xcopy / jpackage need ("The request is not supported").
fsutil fsinfo drivetype %CD:~0,2% 2>nul | findstr /i "Network Remote" >nul
if not errorlevel 1 (
    echo.
    echo [!] WARNING: %CD:~0,2% looks like a network / VM shared drive.
    echo     Native compilation and jpackage are unreliable here. If this build fails with
    echo     "The request is not supported", copy the repo to a LOCAL drive ^(e.g. C:\dev\Yama^)
    echo     and run this script from there.
    echo.
)
echo.

REM --- 1. Chocolatey ----------------------------------------------------------
where choco >nul 2>&1
if %errorlevel% neq 0 (
    echo [1/5] Installing Chocolatey...
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Set-ExecutionPolicy Bypass -Scope Process -Force; [System.Net.ServicePointManager]::SecurityProtocol = 3072; iex ((New-Object System.Net.WebClient).DownloadString('https://community.chocolatey.org/install.ps1'))"
    if !errorlevel! neq 0 goto :error
    call :refresh
) else (
    echo [1/5] Chocolatey already present.
)

REM --- 2. compile dependencies ------------------------------------------------
echo [2/5] Installing build dependencies (JDK 17, WiX 3, 7-Zip, VS C++ Build Tools)...
echo       (VS C++ Build Tools is a large first-time download -- please be patient.)
call :choco microsoft-openjdk17 wixtoolset 7zip
if errorlevel 1 goto :error
REM --includeRecommended pulls the Windows SDK + C++/WinRT headers (windows.h etc.). Without it the
REM workload installs only the compiler and cl fails with "Cannot open include file: 'windows.h'".
REM NOTE: if vctools was previously installed WITHOUT this, choco skips it here -- add --force once,
REM or use the Visual Studio Installer, to retrofit the SDK onto an existing install.
call :choco visualstudio2022-workload-vctools --package-parameters "--includeRecommended"
if errorlevel 1 goto :error
call :refresh
echo       JAVA_HOME=!JAVA_HOME!
echo.

REM --- 3. stage bundled libvlc ------------------------------------------------
set "VLC_DST=desktopApp\resources\windows-x64\vlc"
if exist "%VLC_DST%\libvlc.dll" (
    echo [3/5] libvlc already staged -- skipping download.
) else (
    echo [3/5] Staging libvlc %VLC_VERSION%...
    call :stage_vlc
    if errorlevel 1 goto :error
)
echo.

REM --- 4. compile the SMTC shim ----------------------------------------------
echo [4/5] Compiling the SMTC shim (yama_smtc.dll)...
call :build_shim
if errorlevel 1 goto :error
echo.

REM --- 5. build the MSI -------------------------------------------------------
echo [5/5] Building the MSI (Gradle / jpackage)...
call gradlew.bat :desktopApp:packageMsi --no-daemon
if errorlevel 1 goto :error

call :cleanup
echo.
echo ============================================================================
echo  BUILD OK. Artifact(s):
for %%f in (desktopApp\build\compose\binaries\main\msi\*.msi) do echo    %%~ff
echo ============================================================================
popd
exit /b 0


REM ==================== subroutines ==========================================

:choco
REM choco install, treating 3010/1641 (reboot-required) as success.
choco install -y --no-progress %*
set "EC=%errorlevel%"
if "%EC%"=="3010" set "EC=0"
if "%EC%"=="1641" set "EC=0"
exit /b %EC%

:refresh
REM Reload machine/user env (PATH, JAVA_HOME, WiX) into this session after installs.
if exist "%ChocolateyInstall%\bin\RefreshEnv.cmd" call "%ChocolateyInstall%\bin\RefreshEnv.cmd" >nul
exit /b 0

:stage_vlc
set "VLC_BASE=https://get.videolan.org/vlc/%VLC_VERSION%/win64"
set "VLC_FILE=vlc-%VLC_VERSION%-win64.7z"
set "VLC_SRC=vlc_extracted\vlc-%VLC_VERSION%"

REM --- download (skip if the archive is already cached from a prior run) ---
if exist vlc.7z (echo     using cached vlc.7z. & goto :vlc_do_extract)
echo     downloading %VLC_FILE% ...
curl.exe -fL --retry 5 --retry-all-errors --retry-delay 3 -o vlc.7z "%VLC_BASE%/%VLC_FILE%"
if errorlevel 1 (echo     [x] libvlc download failed & exit /b 1)
curl.exe -fL --retry 5 --retry-all-errors --retry-delay 3 -o vlc.7z.sha256 "%VLC_BASE%/%VLC_FILE%.sha256"
if errorlevel 1 (echo     [x] libvlc checksum download failed & exit /b 1)
echo     verifying checksum ...
for /f %%h in ('powershell -NoProfile -Command "(Get-FileHash vlc.7z -Algorithm SHA256).Hash.ToLower()"') do set "ACTUAL=%%h"
for /f "tokens=1" %%e in (vlc.7z.sha256) do set "EXPECTED=%%e"
REM A bad cached archive is deleted so the next run re-downloads instead of reusing garbage.
if /i not "!ACTUAL!"=="!EXPECTED!" (echo     [x] libvlc checksum mismatch: expected !EXPECTED! got !ACTUAL! & del /q vlc.7z & exit /b 1)
echo     checksum OK.

:vlc_do_extract
REM --- extract (skip if a prior run already extracted it) ---
if exist "%VLC_SRC%\libvlc.dll" (echo     using cached vlc_extracted\. & goto :vlc_do_copy)
REM Prefer the REAL 7-Zip binary over Chocolatey's shim: the shim proxies stdout through a pipe, and
REM redirecting that to >nul can deadlock (a silent hang mid-extract). The real exe has no such layer.
set "SEVENZIP="
if exist "%ProgramFiles%\7-Zip\7z.exe"      set "SEVENZIP=%ProgramFiles%\7-Zip\7z.exe"
if not defined SEVENZIP if exist "%ProgramFiles(x86)%\7-Zip\7z.exe" set "SEVENZIP=%ProgramFiles(x86)%\7-Zip\7z.exe"
if not defined SEVENZIP if exist "%ChocolateyInstall%\lib\7zip\tools\7z.exe" set "SEVENZIP=%ChocolateyInstall%\lib\7zip\tools\7z.exe"
if not defined SEVENZIP (echo     [x] 7z.exe not found & exit /b 1)
REM Clear any partial extract left by an interrupted run so we start clean.
if exist vlc_extracted rmdir /s /q vlc_extracted
echo     extracting with "!SEVENZIP!" -- this can take a minute ...
REM -bso0 silences the per-file list; -bsp1 keeps a live progress line so slow never looks like hung.
"!SEVENZIP!" x vlc.7z -ovlc_extracted -y -bso0 -bsp1
if errorlevel 1 (echo     [x] 7z extraction failed & exit /b 1)

:vlc_do_copy
if not exist "%VLC_DST%" mkdir "%VLC_DST%"
echo     copying runtime DLLs + plugins ...
copy /y "%VLC_SRC%\libvlc.dll"     "%VLC_DST%\" >nul || goto :copy_fail
copy /y "%VLC_SRC%\libvlccore.dll" "%VLC_DST%\" >nul || goto :copy_fail
xcopy /e /i /y "%VLC_SRC%\plugins" "%VLC_DST%\plugins" >nul || goto :copy_fail
echo     staged libvlc into %VLC_DST%.
exit /b 0
:copy_fail
echo     [x] Failed to copy libvlc into %VLC_DST%.
echo         "The request is not supported" / "Unable to create directory" almost always means the
echo         repo is on a VM SHARED FOLDER. Copy it to a LOCAL drive ^(e.g. C:\dev\Yama^) and rebuild.
exit /b 1

:build_shim
REM Locate vcvars64.bat robustly: ask vswhere to FIND the file (no brittle -requires component
REM filter), then fall back to probing the standard install locations for every VS 2022 edition.
set "VCVARS="
set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
if exist "%VSWHERE%" for /f "usebackq delims=" %%i in (`"%VSWHERE%" -latest -prerelease -products * -find VC\Auxiliary\Build\vcvars64.bat 2^>nul`) do set "VCVARS=%%i"
if not defined VCVARS call :probe_vcvars "%ProgramFiles%"
if not defined VCVARS call :probe_vcvars "%ProgramFiles(x86)%"
if not defined VCVARS (
    echo     [x] Could not find the VC++ toolset ^(vcvars64.bat^).
    echo         Visual Studio products vswhere can see:
    if exist "%VSWHERE%" "%VSWHERE%" -latest -prerelease -products * -property displayName
    echo         The C++ workload likely didn't finish installing. Try, in order:
    echo           1^) reboot ^(a pending reboot can block VS install completion^), then re-run; or
    echo           2^) choco install -y visualstudio2022-workload-vctools --force ; or
    echo           3^) open "Visual Studio Installer" and add "Desktop development with C++".
    exit /b 1
)
echo     using "!VCVARS!"
call "!VCVARS!" >nul
if errorlevel 1 (echo     [x] vcvars64 failed & exit /b 1)
REM Verify the Windows SDK actually landed -- the compiler can install without it, and then cl dies
REM later with "Cannot open include file: 'windows.h'". Fail here with the fix instead.
set "SDK_OK="
if defined WindowsSdkDir if exist "%WindowsSdkDir%Include\%WindowsSDKVersion%um\windows.h" set "SDK_OK=1"
if not defined SDK_OK (
    echo     [x] Windows SDK headers not found ^(no windows.h^) -- the SDK component is missing.
    echo         The VC++ compiler is installed but not the SDK. Fix it ONCE with either:
    echo           - choco install -y visualstudio2022-workload-vctools --package-parameters "--includeRecommended" --force
    echo           - or Visual Studio Installer ^> Modify ^> "Desktop development with C++"
    exit /b 1
)
set "SMTC_DST=desktopApp\resources\windows-x64\smtc"
if not exist "%SMTC_DST%" mkdir "%SMTC_DST%"
cl /nologo /LD /std:c++17 /EHsc /permissive- /DUNICODE /D_UNICODE ^
   /I "%WindowsSdkDir%Include\%WindowsSDKVersion%cppwinrt" ^
   desktopApp\native\smtc\yama_smtc.cpp ^
   /Fe:%SMTC_DST%\yama_smtc.dll ^
   /link RuntimeObject.lib ole32.lib oleaut32.lib
if errorlevel 1 (echo     [x] SMTC shim compile failed & exit /b 1)
if not exist "%SMTC_DST%\yama_smtc.dll" (echo     [x] yama_smtc.dll was not produced & exit /b 1)
echo     staged yama_smtc.dll into %SMTC_DST%.
exit /b 0

:probe_vcvars
REM %~1 = a Program Files root. Sets VCVARS to the first VS 2022 edition that has vcvars64.bat.
REM `if not defined` reads the live variable, so this correctly stops at the first hit.
for %%e in (BuildTools Community Professional Enterprise Preview) do (
    if not defined VCVARS if exist "%~1\Microsoft Visual Studio\2022\%%e\VC\Auxiliary\Build\vcvars64.bat" set "VCVARS=%~1\Microsoft Visual Studio\2022\%%e\VC\Auxiliary\Build\vcvars64.bat"
)
exit /b 0

:cleanup
del /q vlc.7z vlc.7z.sha256 yama_smtc.obj yama_smtc.lib yama_smtc.exp >nul 2>&1
if exist vlc_extracted rmdir /s /q vlc_extracted >nul 2>&1
exit /b 0

:error
echo.
echo [x] BUILD FAILED (errorlevel %errorlevel%). See the messages above.
popd
exit /b 1
