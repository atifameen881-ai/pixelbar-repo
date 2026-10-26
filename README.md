# Pixel Bar (uncompiled, untested)

Build (Linux, JDK 17 + Android SDK 34 installed):
  1. Open this folder in Android Studio (easiest, it makes the Gradle wrapper for you), or
  2. gradle wrapper --gradle-version 8.7 && ./gradlew assembleDebug
  APK: app/build/outputs/apk/debug/app-debug.apk

Install:
  adb install -r app/build/outputs/apk/debug/app-debug.apk

Setup: open app > Grant phone state > Open accessibility settings > enable "Pixel Bar" > flip the switch in the app.
