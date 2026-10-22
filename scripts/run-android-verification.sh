#!/usr/bin/env bash
# Reuse one emulator and report both dedicated lifecycle and local regression failures.
set -euo pipefail
./gradlew assembleDebug assembleDebugAndroidTest --stacktrace

runtime_result=0
if ! bash scripts/verify-offline-android.sh; then
    runtime_result=1
fi

# Keep later tests local even if the lifecycle phase failed before its network switch.
adb shell svc wifi disable
adb shell svc data disable
if ! bash scripts/run-local-android-regressions.sh; then
    runtime_result=1
fi
exit "$runtime_result"
