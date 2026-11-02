# Video surface swipe and mini player (2026-11-02)

Baseline: `ed44ec9c408be2b1e3bfb2b7a4ab13121b688bc4`, the source used for the previously delivered 1042 candidate. Work branch: `fix/gesture-landscape-mini-20261102`.

## Request and reproduction

The user reported that an upward swipe on the portrait video did not open landscape fullscreen. The fullscreen two-finger downward swipe must instead force portrait and collapse into the app's bottom mini player, preserving playback. Swipes starting below the video must remain detail-page gestures.

The old direct-view gesture test passed but bypassed the Activity and its parent layouts. A new `PlaybackNavigationTest` sends pointer events through the real Activity window. Before the fix, the video listener received DOWN once and MOVE zero times for the upward swipe; the parent took the gesture. After retaining the first move, the listener reached fullscreen but orientation stayed portrait. In fullscreen the video listener received DOWN and POINTER_DOWN, then zero MOVEs and CANCEL once for the two-finger gesture: the bottom sheet captured the first downward movement.

## Change

- The video keeps initial movement until the fullscreen detector distinguishes upward intent from downward or horizontal intent. The latter still yields to the parent bottom sheet.
- A two-finger gesture begun on the video stays with the video listener; the bottom sheet does not take its first downward move. Other control areas keep their previous interception rules.
- Deliberate portrait video upward swipe enters fullscreen and explicitly requests landscape. Deliberate fullscreen two-finger downward swipe leaves fullscreen, records and applies the existing bottom-sheet collapsed state, then explicitly requests portrait. These orientation requests do not change the device's auto-rotate setting. The player, queue, and playback engine are not replaced.
- The service listener forwards the gesture to the active detail fragment. The existing rotation button and back navigation paths remain separate.

## Verification

- PASS, API 23 software emulator, Activity-window synthetic touch at phone layout (sw365dp): video upward to landscape fullscreen; two-finger downward to portrait mini player; swipe starting in the detail body does not enter fullscreen; video downward still collapses. All 4 passed. The two transition tests assert the same queue object, stream ID, playing state, and nondecreasing playback position.
- PASS, same API 23 emulator with sw853dp tablet layout: both requested transitions, 2 passed.
- PASS, API 23 focused legacy regressions: initial video movement and parent handoff, direct gesture dispatch, playback-button/downward collapse, fullscreen back navigation; 4 passed.
- PASS, four related JVM suites (fullscreen detector, downward drag, temporary 2x speed, fullscreen controls), 25 tests. The full `:app:testDebugUnitTest` run passed 705 tests with 0 failures, errors, or skips.
- PASS: `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, `:app:runCheckstyle`, and `git diff --check`.
- NOT RUN: physical Galaxy Tab S6 Lite or phone finger input, actual H.264 media playback, API 35 emulator (previous software-emulator system failure). The tests use generated local WAV and synthetic cached metadata, with no provider request or user data. The app code requests fixed orientations, but the physical device's auto-rotate-off switch was not separately exercised.

The initial API 23 AVD lacked space for a new install. Only the app and test package created for this gesture branch were removed. A separate API 23 AVD with 3 GB data space was used for final checks; the earlier AVD and other test apps were preserved. Its files are outside this checkout.

This commit is source and test evidence for the existing signing owner. It does not include a signed APK or claim that the title-language mismatch or public Wi-Fi YouTube challenge is resolved. No main merge or public release was made.
