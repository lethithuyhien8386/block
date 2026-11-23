#!/usr/bin/env bash
set -euo pipefail
# Requires Java 17, Gradle 8.10.2 and Android SDK platform 35 (see README).
gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug
APK=app/build/outputs/apk/debug/app-debug.apk
test -s "$APK"
echo "Ready: $APK"
