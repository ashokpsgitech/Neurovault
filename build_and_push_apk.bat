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
git add -f frontend/build/app/outputs/flutter-apk/app-release.apk
git add .gitignore frontend/.gitignore build_and_push_apk.bat frontend/lib frontend/pubspec.yaml frontend/pubspec.lock
git commit -m "build: update release APK binary and sync changes"
git push origin main

echo ========================================================
echo SUCCESS: Release APK updated and pushed to GitHub!
echo ========================================================
