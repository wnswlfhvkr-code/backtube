# Online video long-press offline save, 2026-10-26

## Request and scope

The attached online-video long-press menu was inspected. This change removes the background, popup, and browser entries from that menu and adds one `임시저장` entry. The existing download, playlist, share, watched, channel, and queue entries remain. Player features outside this menu are unchanged.

`InfoItemDialog.Builder` applies the menu change to online `VIDEO_STREAM` and `POST_LIVE_STREAM` items. Local saved entries and audio menus retain their prior defaults. `OfflineSaveMenuAction` passes the selected service and URL into the existing metadata loader and opens the existing offline save selector. It uses the existing completed-save check and `이미 저장된 항목입니다` toast. The callback also checks the original Fragment view and detail selection identity, so navigation or a next-video update cannot open a stale selector.

## Reproduction and verification

- Before the change, menu tests reproduced the three unwanted entries and missing offline-save entry. The new tests passed after the focused change.
- A delayed metadata callback test reproduced a stale selector after the detail selection changed in the same view. It passed after the selection-generation guard.
- A next-video update test reproduced the same issue when the player changed the URL without recreating the view. It passed after including service and URL in the selection key.
- Tests cover selected service/URL, a completed video showing the existing toast without another save, audio-only saved state not blocking a video save, duplicate taps opening one selector, and a detached Fragment dropping a late callback.
- Final JVM/build/Checkstyle run: `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:runCheckstyle` PASS, 671 JVM tests, 0 failures, 0 errors, 0 skips. This builds the device test; its execution status is recorded separately below.
- API 23 Android dialog test PASS (1/1). It inspected the real `AlertDialog` list and confirmed exactly one offline-save entry, no background/popup/browser entries, and retained download/playlist/share entries. [Screen capture](evidence/online-video-menu-2026-10-26/api23-generated-video.png) uses a generated item and the emulator's English locale; the Korean resource is `임시저장`.
- The first test-APK install was rejected by `INSTALL_FAILED_CONFLICTING_PROVIDER`: older test variants on this emulator had the same fixed test-only provider authority. The test manifest now uses the test application's ID, and the matching test URI is generated from that ID. The rebuilt test APK installed without removing existing emulator data.
- The affected `SubscriptionWorkerBoundaryTest` passed on API 23 after this test-only provider change (5/5).
- Independent code review found and led to the same-view and next-video lifetime guards, then found no remaining blocking issue in the reviewed implementation.

## Separate pending-download report

The offline shelf image showed the last LiveClip item as `오디오 저장본 · 허용된 네트워크·다운로드 순서 대기`. `OfflineLibraryActivity.rowDetails` selects that string when a download mission exists, has no error, is not running, and is enqueued. The screenshot does not identify which condition prevents its start.

The queue code additionally checks network availability, validated connectivity, and, with the default Wi-Fi-only setting, an unmetered Wi-Fi transport. Existing running missions and the queue limit can delay another mission. A mock-network characterization test confirmed that Wi-Fi can be validated yet metered and still fail the default policy; an unmetered validated Wi-Fi network satisfies it. Existing dummy queue tests cover activation after a policy change. These tests do not establish the user's device state, current setting, active missions, or an actual scheduler defect. The 1036 source used the same offline network policy. No user downloads, settings, or saved files were changed for this diagnosis.

## Limits

The API 23 test used a generated item; it did not exercise a real provider download. API 35 and a physical phone were not run. No new user APK, main merge, or public release is part of this change.
