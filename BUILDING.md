# Building CallVault locally

1. `brew install --cask android-commandlinetools` (one-time)
2. Source the env: `source ~/.callvault-env.sh`
   - Sets JAVA_HOME (brew openjdk@17), ANDROID_HOME (~/Library/Android/sdk), PATH.
3. One-time SDK packages: `sdkmanager "platforms;android-36" "build-tools;36.0.0"`
4. Build: `cd app-src && ./gradlew assembleDebug`
5. APK: `app-src/app/build/outputs/apk/debug/app-debug.apk`
