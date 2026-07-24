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
if not exist "apk" mkdir "apk"
copy /Y "frontend\build\app\outputs\flutter-apk\app-release.apk" "apk\neurovault-app.apk"

echo ========================================================
echo Staging and Pushing to GitHub...
echo ========================================================
git add -A
git commit -m "build: update release APK binary and sync changes"
git push origin main

echo ========================================================
echo SUCCESS: APK updated and pushed to GitHub!
echo ========================================================
