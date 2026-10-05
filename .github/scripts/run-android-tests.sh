#!/usr/bin/env bash
# Run inside emulator-runner so diagnostics are collected before the emulator exits.
set -uo pipefail

selector=${ANDROID_TEST_CLASS:-}
selector_pattern='^[A-Za-z0-9_.$#]+(,[A-Za-z0-9_.$#]+)*$'
if [[ -n "$selector" && ! "$selector" =~ $selector_pattern ]]; then
    echo "ANDROID_TEST_CLASS must contain class or class#method selectors separated by commas." >&2
    exit 2
fi

diagnostics=android-test-diagnostics
mkdir -p "$diagnostics"
adb logcat -c
adb logcat -v threadtime > "$diagnostics/logcat.txt" 2>&1 &
logcat_pid=$!

collect_diagnostics() {
    local test_status=$?
    # Diagnostic failures must not replace the original Gradle result.
    adb logcat -b crash -d > "$diagnostics/crash.txt" 2>&1 || true
    adb shell dumpsys activity activities > "$diagnostics/activities.txt" 2>&1 || true
    adb shell dumpsys window windows > "$diagnostics/windows.txt" 2>&1 || true
    kill "$logcat_pid" 2>/dev/null || true
    wait "$logcat_pid" 2>/dev/null || true
    exit "$test_status"
}
trap collect_diagnostics EXIT

if [[ -n "$selector" ]]; then
    ./gradlew :app:connectedDebugAndroidTest --stacktrace \
        "-Pandroid.testInstrumentationRunnerArguments.class=$selector"
else
    ./gradlew connectedCheck --stacktrace
fi
