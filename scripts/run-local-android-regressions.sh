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
    VideoDetailFragment MediaSourceManager BaseStateFragment MediaSessUi PlayerService \
    ExoPlayerImpl MediaSessionService > "$output_dir/regression-errors.log" || true
# Keep the final focus stack for real cross-UID handoff diagnostics.
adb shell dumpsys audio > "$output_dir/regression-audio-state.log" || true
if grep -Eq 'IndexOutOfBoundsException|NullPointerException|not attached to a context|connection pool has been closed' \
        "$output_dir/regression-errors.log"; then
    echo '::error::Android runtime emitted a bounds, null, detached-context or closed-database error'
    result=1
fi
if [ "$result" -ne 0 ]; then
    # Retain dynamic MediaSourceManager tags and system media-session callers on failure.
    adb logcat -d > "$output_dir/regression-full-logcat.log" || true
fi
exit "$result"
