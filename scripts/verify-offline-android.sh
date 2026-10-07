#!/usr/bin/env bash
# Uses generated WAV only. The caller builds/starts exactly one emulator.
set -euo pipefail
output_dir=app/build/reports/offline-runtime
mkdir -p "$output_dir"
app_id=$(python3 -c 'import json; print(json.load(open("app/build/outputs/apk/debug/output-metadata.json"))["applicationId"])')
runner="$app_id.test/androidx.test.runner.AndroidJUnitRunner"
class_name=org.schabi.newpipe.player.PersonalPlaybackTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
api_level=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
if (( api_level >= 33 )); then
    adb shell pm grant "$app_id" android.permission.POST_NOTIFICATIONS
fi
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0

run_phase() {
    local phase="$1"
    shift
    adb shell am instrument -w -r "$@" "$runner" | tee "$output_dir/$phase.txt"
    # am instrument may return exit 0 for test failures or a crashed process.
    grep -Eq 'OK \([0-9]+ tests?\)' "$output_dir/$phase.txt"
    if grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|shortMsg=|INSTRUMENTATION_ABORTED' \
            "$output_dir/$phase.txt"; then
        return 1
    fi
}

run_phase seed -e session_restart_phase seed \
    -e class "$class_name#savedOfflineLifecycleAcrossProcessRestart"
adb shell am force-stop "$app_id"
adb shell settings put global airplane_mode_on 1
if (( api_level >= 30 )); then
    adb shell cmd connectivity airplane-mode enable
else
    adb shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true
fi
adb shell svc wifi disable
adb shell svc data disable
run_phase restore -e session_restart_phase restore \
    -e class "$class_name#savedOfflineLifecycleAcrossProcessRestart"
run_phase playback -e class "$class_name#savedOfflineCopyPlaysWithoutInfoCacheAndKeepsSleepTimerOnNetworkReturn,$class_name#savedOfflineQueueRestoresWithoutRemoteMetadata,$class_name#savedOfflineShelfImportsGeneratedFile"
adb pull "/sdcard/Android/data/$app_id/files/personal-playback-test/offline-shelf-generated.png" \
    "$output_dir/offline-shelf-generated.png"
adb shell settings put global airplane_mode_on 0
if (( api_level >= 30 )); then
    adb shell cmd connectivity airplane-mode disable
else
    adb shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false
fi
adb shell svc wifi enable
