@echo off
rem Worldgen Accelerator GPU probe: finds a Java 21 or newer (the one on PATH, or the ones Minecraft launchers install)
rem and runs wgaccel-openclprobe.jar, which writes wgaccel-openclprobe-result.txt in this folder.
setlocal EnableDelayedExpansion
cd /d "%~dp0"
set RESULT=wgaccel-openclprobe-result.txt
set DONE=

echo Worldgen Accelerator GPU probe
echo This takes one to three minutes. Please wait until it says it is finished.
echo.

call :try java
for /d %%D in ("%APPDATA%\PrismLauncher\java\*") do call :try "%%D\bin\java.exe"
call :try "%LOCALAPPDATA%\Packages\Microsoft.4297127D64EC6_8wekyb3d8bbwe\LocalCache\Local\runtime\java-runtime-delta\windows-x64\java-runtime-delta\bin\java.exe"
call :try "%ProgramFiles(x86)%\Minecraft Launcher\runtime\java-runtime-delta\windows-x64\java-runtime-delta\bin\java.exe"
call :try "%USERPROFILE%\curseforge\minecraft\Install\runtime\java-runtime-delta\windows-x64\java-runtime-delta\bin\java.exe"
for /d %%D in ("%ProgramFiles%\Eclipse Adoptium\*") do call :try "%%D\bin\java.exe"
for /d %%D in ("%ProgramFiles%\Microsoft\jdk-*") do call :try "%%D\bin\java.exe"
for /d %%D in ("%ProgramFiles%\Java\*") do call :try "%%D\bin\java.exe"

echo.
if defined DONE (
  echo Finished. Please send the file %RESULT% from this folder:
  echo %~dp0%RESULT%
  start "" explorer /select,"%~dp0%RESULT%"
) else (
  echo No Java 21 or newer was found, so the probe could not run.
  echo Install Java 21 from https://adoptium.net ^(Temurin 21, Windows x64 .msi^), then run this file again.
)
echo.
pause
exit /b

:try
if defined DONE exit /b
if not "%~1"=="java" if not exist "%~1" exit /b
if "%~1"=="java" (where java >nul 2>&1 || exit /b)
if exist "%RESULT%" del "%RESULT%"
"%~1" -jar wgaccel-openclprobe.jar
rem An older Java stops before the probe starts, leaving no result file: then the next Java is tried.
if exist "%RESULT%" (set DONE=1) else (echo That Java is too old for the probe; trying another one...)
exit /b
