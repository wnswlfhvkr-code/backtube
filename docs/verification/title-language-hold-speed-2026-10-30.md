# Title language and hold speed verification (2026-10-30)

Baseline: `b966504dbcaa1b4a51a04080312ada227d9c8e95` (1041 source).

## Observed cause and scope

The default content-language preference resolves to the OS locale, separately from the app's visible locale. The extractor is initialized in `App.onCreate`. Channel, search and related results use the extractor localization. The user reported both app and content language settings at defaults with Korean UI. This alone does not establish the OS locale or the effective provider response. In NewPipeExtractor v0.26.5 the [list extractor](https://github.com/TeamNewPipe/NewPipeExtractor/blob/v0.26.5/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/extractors/YoutubeStreamInfoItemExtractor.java) reads the response item `title`, whereas the [detail extractor](https://github.com/TeamNewPipe/NewPipeExtractor/blob/v0.26.5/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/extractors/YoutubeStreamExtractor.java) first reads the untranslated `playerResponse.videoDetails.title`. The raw service responses have not been observed. An HTTP comparison request for the example public video was denied by this environment's proxy (403). No automatic per-video translation or requests were added; the reported title mismatch is not verified fixed.

Independent review found that making the default content language follow the app UI language was speculative for this report and changed the meaning of existing saved `system` settings. That change was removed. The existing system-language default and explicit language codes retain their meanings. The extractor localization and memory cache are still refreshed on activity resume and when leaving content settings if the effective content language or country changed; this addresses stale requests after a real setting change but is not evidence of a fix for the reported title.

For MAIN video playback, long press starts a temporary 2.0x rate only while playing. Release, cancel, another pointer, drag, pause, completed playback, listening mode, fragment exit, or player teardown restores the original playback parameters on the same engine. The popup's long-press handler is unchanged. The temporary rate bypasses the preference-writing setter.

The inline video surface also recognizes a deliberate upward one-finger swipe for the existing fullscreen transition. In fullscreen only, a deliberate two-finger downward swipe returns to inline. The detector rejects single-finger downward, pinch, horizontal, small, canceled, and already scrolling gestures. No playback engine or queue is recreated by this transition.

The first version blocked the parent bottom sheet on inline ACTION_DOWN. This could lose a short downward flick. The video surface now lets the sheet see the first and subsequent inline MOVE events, while `CustomBottomSheetBehavior` primes its drag helper on DOWN and keeps upward video swipes with the fullscreen detector. Playback-button priority and the existing seek bar/bottom-control exclusions are retained. Independent review found and prompted fixes for the subslop-first-MOVE case and control-area precedence. The added Android test checks that inline DOWN and a two-MOVE sequence never disallow parent interception; the existing `DownwardDrag` test covers the single downward MOVE threshold. A real page scroll/flick still needs device confirmation.

## Checks

- PASS: locale resolution tests for unchanged system default and explicit language; no unverified content-language policy change remains.
- PASS: temporary speed controller tests for speed/pitch restore, original 2.0x, speed above 2.0x, not playing, engine replacement, and repeated start.
- PASS: fullscreen swipe detector tests for one-finger up and two-finger down, with rejection of single-finger down, horizontal movement, pinch, canceled and already scrolling touch sequences.
- PASS: `:app:testDebugUnitTest` (702 tests, 0 failures/errors/skips), `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, and `:app:runCheckstyle` after the locale and short-flick corrections.
- PASS: `git diff --check`; independent review found and prompted fixes for startup locale synchronization, drag after long press, and API 23 locale access. Read-only re-review found no further concrete blocker in these paths.
- NOT RUN: Real-phone gesture and title verification. The user reported default app and content language settings, but the device OS locale and raw provider response were not available.
- PASS: The earlier API 23 installation timeout was not a permanent device failure. With the same software emulator booted, direct `adb install` finished after a long `dex2oat` stage. The earlier synthetic fullscreen touch test passed (1 test), and the corrected APK plus test APK were installed and `PlayerUiLifetimeDeviceTest` passed (6 tests), including the two-MOVE parent-interception regression. This dispatches synthetic MotionEvents on Android; it does not prove a physical finger flick through the complete detail screen.

Independent review found and prompted both seek and center double-tap conflict fixes. Read-only re-review found no further concrete blocker in those paths. The short-flick concern was addressed in the bottom sheet dispatch path, subject to a real page gesture check.

No main merge, APK release, or public issue was made by this work.
