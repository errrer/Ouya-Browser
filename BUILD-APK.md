# Build the OUYA Browser APK

## Easiest route: GitHub Actions (no Android SDK installation on your PC)

1. Create a new GitHub repository.
2. Upload the contents of this folder to the repository (the `AndroidApp`, `remote-engine`, and `.github` folders should be at the repository root).
3. Open the repository's **Actions** tab and select **Build OUYA Browser APK**.
4. Press **Run workflow**. You can also let it run automatically after pushing to `main` or `master`.
5. When the run finishes, open it and download the **OUYA-Browser-debug-APK** artifact.
6. Extract the artifact. It contains `app-debug.apk`.

## Build locally with Android Studio

Open `AndroidApp/` in Android Studio with JDK 17+, Android SDK Platform 35, and Android Build Tools 35.0.0 installed. Choose **Build → Build Bundle(s) / APK(s) → Build APK(s)**. The APK is written to:

`AndroidApp/app/build/outputs/apk/debug/app-debug.apk`

The app's minimum Android version remains Android 4.1.2 (API 16). The build requires a modern SDK to compile, but the resulting app is intended for the older OUYA system.
