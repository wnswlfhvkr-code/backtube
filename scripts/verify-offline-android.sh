#!/usr/bin/env bash
# Uses generated local WAV and PNG fixtures only. The caller builds/starts exactly one emulator.
set -euo pipefail
output_dir=app/build/reports/offline-runtime
mkdir -p "$output_dir"
capture_failure() {
    local result=$?
    if (( result != 0 )); then
        adb logcat -d > "$output_dir/offline-failure-logcat.log" || true
        adb exec-out screencap -p > "$output_dir/offline-failure-screen.png" || true
    fi
    exit "$result"
}
trap capture_failure EXIT
app_id=$(python3 -c 'import json; print(json.load(open("app/build/outputs/apk/debug/output-metadata.json"))["applicationId"])')
runner="$app_id.test/androidx.test.runner.AndroidJUnitRunner"
class_name=org.schabi.newpipe.player.PersonalPlaybackTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
api_level=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0

run_phase() {
    local phase="$1"
    shift
    adb logcat -c
    adb shell am instrument -w -r "$@" "$runner" | tee "$output_dir/$phase.txt"
    # am instrument may return exit 0 for test failures or a crashed process.
    grep -Eq 'OK \([0-9]+ tests?\)' "$output_dir/$phase.txt"
    if grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|shortMsg=|INSTRUMENTATION_ABORTED' \
            "$output_dir/$phase.txt"; then
        return 1
    fi
}

# Exercise the real denial dialog in a fresh emulator app process before the usual grant.
adb shell am force-stop "$app_id"
if (( api_level >= 33 )); then
    adb shell pm revoke "$app_id" android.permission.POST_NOTIFICATIONS
    adb shell pm clear-permission-flags "$app_id" android.permission.POST_NOTIFICATIONS user-set user-fixed
    run_phase detail-timer -e expect_notification_denied true \
        -e class "$class_name#detailTimerSharesStateAndDialogCancellationPreservesPlayback"
else
    run_phase detail-timer \
        -e class "$class_name#detailTimerSharesStateAndDialogCancellationPreservesPlayback"
fi
for orientation in "" "-landscape"; do
    adb pull "/sdcard/Android/data/$app_id/files/personal-playback-test/backtube-detail-timer-generated${orientation}.png" \
        "$output_dir/backtube-detail-timer-generated${orientation}.png"
done
if (( api_level >= 33 )); then
    adb shell pm grant "$app_id" android.permission.POST_NOTIFICATIONS
fi

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
adb pull "/sdcard/Android/data/$app_id/files/personal-playback-test/offline-shelf-retained-after-restart.png" \
    "$output_dir/offline-shelf-retained-after-restart.png"
run_phase playback -e class "$class_name#savedOfflineCopyPlaysWithoutInfoCacheAndKeepsSleepTimerOnNetworkReturn,$class_name#savedOfflineQueueRestoresWithoutRemoteMetadata,$class_name#savedOfflineShelfImportsGeneratedFile"
adb pull "/sdcard/Android/data/$app_id/files/personal-playback-test/offline-shelf-generated.png" \
    "$output_dir/offline-shelf-generated.png"
# Capture generated UI evidence before connectedCheck uninstalls the test app/data.
run_phase current-ui -e class org.schabi.newpipe.player.MiniPlayerUiTest
for orientation in "" "-landscape"; do
    adb pull "/sdcard/Android/data/$app_id/files/personal-playback-test/backtube-current-track-generated${orientation}.png" \
        "$output_dir/backtube-current-track-generated${orientation}.png"
done
# Exercise real popup windows and retain orientation/control evidence before connectedCheck.
navigation_class=org.schabi.newpipe.player.PlaybackNavigationTest
run_phase popup-policy -e class "$navigation_class#popupExpansionKeepsPortraitPolicyAndControlsReachable,$navigation_class#popupExpansionKeepsLandscapePolicyAndControlsReachable,$navigation_class#popupExpansionKeepsLockedPortraitPolicyAndControlsReachable"
for orientation in portrait landscape portrait-locked; do
    adb pull "/sdcard/Android/data/$app_id/files/personal-playback-test/popup-expanded-${orientation}.png" \
        "$output_dir/popup-expanded-${orientation}.png"
done
# Leave networking disabled for the subsequent local connected suite.
