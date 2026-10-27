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
# This app declines SystemUI's media-resumption browser root. On API 35 the emulator may
# nevertheless probe it and send STOP during an unrelated saved-session assertion. Disable only
# that unsupported SystemUI probe in the test emulator; connected tests still exercise
# media-session stop/play explicitly.
if (( api_level >= 33 )); then
    adb shell settings put secure qs_media_resumption 0
    test "$(adb shell settings get secure qs_media_resumption | tr -d '\r')" = 0
fi
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
# Explicit selection must win on the first playback in a genuinely new process. The online
# entry path uses cached synthetic metadata and owned WAV media, never a provider download.
first_selection_class=org.schabi.newpipe.player.FirstPlaybackSelectionTest
for path in online video audio; do
    for prior in none saved restored; do
        for phase in seed verify; do
            adb shell am force-stop "$app_id"
            run_phase "first-selection-$path-$prior-$phase" \
                -e first_selection_path "$path" -e first_selection_prior "$prior" \
                -e first_selection_phase "$phase" \
                -e class "$first_selection_class#firstSelectionAcrossProcessRestart"
        done
    done
done
# Related B must not displace the explicit first A, but must start after A really completes.
for prior in none saved restored; do
    for phase in seed verify; do
        adb shell am force-stop "$app_id"
        run_phase "first-selection-auto-next-$prior-$phase" \
            -e first_selection_path online -e first_selection_prior "$prior" \
            -e first_selection_phase "$phase" -e first_selection_auto_next true \
            -e class "$first_selection_class#firstSelectionAcrossProcessRestart"
    done
done
# A failed initial source must retain A for retry instead of starting its related B.
for phase in seed verify; do
    adb shell am force-stop "$app_id"
    run_phase "first-selection-source-failure-$phase" \
        -e first_selection_path online -e first_selection_prior none \
        -e first_selection_phase "$phase" -e first_selection_auto_next true \
        -e first_selection_source_failure true \
        -e class "$first_selection_class#firstSelectionAcrossProcessRestart"
done
# Leave networking disabled for the subsequent local connected suite.
