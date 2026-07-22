# Implementation Plan - Build Flutter APKs (Debug & Release)

This plan outlines the steps to build both Debug and Release Android APKs for the `neurovault_frontend` Flutter application.

## User Review Required

> [!IMPORTANT]
> The `flutter doctor` report indicates that some Android SDK licenses have not been accepted and the `cmdline-tools` are missing. I will attempt to build the APKs, but if it fails, you may need to manually accept the licenses in your terminal (`flutter doctor --android-licenses`) or via Android Studio.

## Proposed Changes

### [Frontend Build]

#### [BUILD] Build Debug APK
- Navigate to the `frontend/` directory.
- Run `flutter build apk --debug`.
- Output: `frontend/build/app/outputs/flutter-apk/app-debug.apk`

#### [BUILD] Build Release APK
- Navigate to the `frontend/` directory.
- Run `flutter build apk --release`.
- *Note: As per `app/build.gradle`, this will use debug signing for now.*
- Output: `frontend/build/app/outputs/flutter-apk/app-release.apk`

## Verification Plan

### Manual Verification
- Verify the existence of both generated APK files in `frontend/build/app/outputs/flutter-apk/`.
- Provide the user with the absolute paths to the APKs.
