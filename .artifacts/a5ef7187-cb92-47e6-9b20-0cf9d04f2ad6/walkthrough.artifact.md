# Walkthrough - Flutter APK Builds (Updated with Network Fixes)

I have successfully re-built both the **Debug** and **Release** APKs for the Neurovault Flutter frontend, incorporating the network security fixes.

## Changes Made

### [Build Infrastructure]

#### [FIX] Gradle & Java Compatibility
The project was initially using Gradle 7.6.3, which is incompatible with Java 21. I performed the following upgrades:
- Upgraded **Gradle** to `8.5` in [gradle-wrapper.properties](file:///D:/Project/micro%20server/frontend/android/gradle/wrapper/gradle-wrapper.properties).
- Upgraded **Android Gradle Plugin (AGP)** to `8.2.1` and **Kotlin** to `1.9.22` in [settings.gradle](file:///D:/Project/micro%20server/frontend/android/settings.gradle).
- Updated [app/build.gradle](file:///D:/Project/micro%20server/frontend/android/app/build.gradle) to target **Java 21**.

### [Network Security]

#### [FIX] Android Permissions & Cleartext Traffic
Updated [AndroidManifest.xml](file:///D:/Project/micro%20server/frontend/android/app/src/main/AndroidManifest.xml) to enable network communication:
- Added `INTERNET` and `ACCESS_NETWORK_STATE` permissions.
- Enabled `android:usesCleartextTraffic="true"` to allow HTTP connections to the backend server.

## Verification Results

### Generated APKs
Both APKs are located in the following directory:
`D:/Project/micro server/frontend/build/app/outputs/flutter-apk/`

- **Debug APK:** [app-debug.apk](file:///D:/Project/micro%20server/frontend/build/app/outputs/flutter-apk/app-debug.apk)
- **Release APK:** [app-release.apk](file:///D:/Project/micro%20server/frontend/build/app/outputs/flutter-apk/app-release.apk) (21.0 MB)

> [!NOTE]
> The Release APK is currently using **debug signing**. For production, a release keystore should be configured.
