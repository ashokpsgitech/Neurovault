@echo off
echo ========================================================
echo Building NeuroVault Release APK and Pushing to GitHub
echo ========================================================

cd /d "%~dp0frontend"
call flutter build apk --release

if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Flutter build failed!
    exit /b %ERRORLEVEL%
)

cd /d "%~dp0"
echo ========================================================
echo Staging Release APK and Pushing to GitHub...
echo ========================================================
for /f "tokens=*" %%b in ('git rev-parse --abbrev-ref HEAD') do set CURRENT_BRANCH=%%b
if "%CURRENT_BRANCH%"=="" set CURRENT_BRANCH=main

git add -A
git add -f frontend/build/app/outputs/flutter-apk/app-release.apk

git diff --cached --quiet
if %ERRORLEVEL% NEQ 0 (
    git commit -m "build: update release APK binary and sync changes"
) else (
    echo [INFO] No changes to commit.
)

git push origin %CURRENT_BRANCH%

echo ========================================================
echo SUCCESS: Release APK updated and pushed to branch %CURRENT_BRANCH%!
echo ========================================================
