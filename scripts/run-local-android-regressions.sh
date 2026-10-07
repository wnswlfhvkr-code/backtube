#!/usr/bin/env bash
# Reuse the already-running emulator and retain failure status while collecting diagnostics.
set -uo pipefail
output_dir=app/build/reports/offline-runtime
mkdir -p "$output_dir"
adb logcat -c
./gradlew connectedCheck --stacktrace \
    -Pandroid.testInstrumentationRunnerArguments.notClass=org.schabi.newpipe.local.subscription.SubscriptionManagerTest
result=$?
adb logcat -d -s ACRA AndroidRuntime App System.err > "$output_dir/regression-errors.log" || true
if grep -Eq 'IndexOutOfBoundsException|NullPointerException|not attached to a context|connection pool has been closed' \
        "$output_dir/regression-errors.log"; then
    echo '::error::Android runtime emitted a bounds, null, detached-context or closed-database error'
    result=1
fi
exit "$result"
