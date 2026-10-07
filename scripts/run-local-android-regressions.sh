#!/usr/bin/env bash
# Reuse the already-running emulator and retain failure status while collecting diagnostics.
set -uo pipefail
output_dir=app/build/reports/offline-runtime
mkdir -p "$output_dir"
adb logcat -c
./gradlew connectedCheck --stacktrace \
    -Pandroid.testInstrumentationRunnerArguments.notClass=org.schabi.newpipe.local.subscription.SubscriptionManagerTest
result=$?
adb logcat -d -s ACRA AndroidRuntime App System.err Player AudioFocusReactor \
    VideoDetailFragment MediaSourceManager BaseStateFragment > "$output_dir/regression-errors.log" || true
if grep -Eq 'IndexOutOfBoundsException|NullPointerException|not attached to a context|connection pool has been closed' \
        "$output_dir/regression-errors.log"; then
    echo '::error::Android runtime emitted a bounds, null, detached-context or closed-database error'
    result=1
fi
app_id=$(python3 -c 'import json; print(json.load(open("app/build/outputs/apk/debug/output-metadata.json"))["applicationId"])')
adb pull "/sdcard/Android/data/$app_id/files/personal-playback-test/backtube-current-track-generated.png" \
    "$output_dir/backtube-current-track-generated.png" || true
adb pull "/sdcard/Android/data/$app_id/files/personal-playback-test/backtube-current-track-generated-landscape.png" \
    "$output_dir/backtube-current-track-generated-landscape.png" || true
exit "$result"
