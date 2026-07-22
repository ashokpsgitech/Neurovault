# Walkthrough - Flutter APK Builds

I have successfully built both the **Debug** and **Release** APKs for the Neurovault Flutter frontend.

## Changes Made

### [Build Infrastructure]

#### [FIX] Gradle & Java Compatibility
The project was initially using Gradle 7.6.3, which is incompatible with Java 21 (major version 65). I performed the following upgrades:
- Upgraded **Gradle** to `8.5` in [gradle-wrapper.properties](file:///D:/Project/micro%20server/frontend/android/gradle/wrapper/gradle-wrapper.properties).
- Upgraded **Android Gradle Plugin (AGP)** to `8.2.1` and **Kotlin** to `1.9.22` in [settings.gradle](file:///D:/Project/micro%20server/frontend/android/settings.gradle).
- Updated [app/build.gradle](file:///D:/Project/micro%20server/frontend/android/app/build.gradle) to target **Java 21** for both Java and Kotlin compilation to resolve JVM target mismatches.

#### [FIX] Environment Conflicts
Resolved a conflict where both `ANDROID_PREFS_ROOT` and `ANDROID_USER_HOME` were set, causing AGP to fail during the keystore location lookup.

## Verification Results

### Generated APKs
Both APKs are located in the following directory:
`D:/Project/micro server/frontend/build/app/outputs/flutter-apk/`

- **Debug APK:** [app-debug.apk](file:///D:/Project/micro%20server/frontend/build/app/outputs/flutter-apk/app-debug.apk)
- **Release APK:** [app-release.apk](file:///D:/Project/micro%20server/frontend/build/app/outputs/flutter-apk/app-release.apk) (21.0 MB)

> [!NOTE]
> The Release APK is currently using **debug signing** as configured in the project's `build.gradle`. For production deployment, you should configure a proper release keystore.
