# Title language and hold speed verification (2026-10-30)

Baseline: `b966504dbcaa1b4a51a04080312ada227d9c8e95` (1041 source).

## Observed cause and scope

The default content-language preference previously resolved to the OS locale, separately from the app's visible locale. The extractor is initialized in `App.onCreate`, while migration of a saved app language occurs later in `MainActivity.onCreate`. Channel, search and related results use the extractor localization. The user reported both app and content language settings at defaults with Korean UI. If the OS locale is Korean too, this adjustment does not change the reported request. In NewPipeExtractor v0.26.5 the [list extractor](https://github.com/TeamNewPipe/NewPipeExtractor/blob/v0.26.5/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/extractors/YoutubeStreamInfoItemExtractor.java) reads the response item `title`, whereas the [detail extractor](https://github.com/TeamNewPipe/NewPipeExtractor/blob/v0.26.5/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/extractors/YoutubeStreamExtractor.java) first reads the untranslated `playerResponse.videoDetails.title`. The raw service responses have not been observed. An HTTP comparison request for the example public video was denied by this environment's proxy (403). No automatic per-video translation or requests were added; the reported title mismatch is not verified fixed.

The default content language now follows the app locale. Explicit language codes remain unchanged. The legacy `system` choice is relabeled as "Follow app language" and now has that meaning, including for an existing saved `system` value; this is a behavior change for anyone who deliberately chose OS language separately from app language. A saved app language is considered before activity migration, and the extractor localization plus memory cache are refreshed on activity resume and when leaving content settings if the effective language or country changed. The resource-locale fallback uses AndroidX `ConfigurationCompat` for API 23 compatibility.

For MAIN video playback, long press starts a temporary 2.0x rate only while playing. Release, cancel, another pointer, drag, pause, completed playback, listening mode, fragment exit, or player teardown restores the original playback parameters on the same engine. The popup's long-press handler is unchanged. The temporary rate bypasses the preference-writing setter.

The inline video surface also recognizes a deliberate upward one-finger swipe for the existing fullscreen transition. In fullscreen only, a deliberate two-finger downward swipe returns to inline. The detector rejects single-finger downward, pinch, horizontal, small, canceled, and already scrolling gestures. No playback engine or queue is recreated by this transition.

## Checks

- PASS: locale resolution tests for default, explicit language, saved setting before migration, changed app locale, and system resource locale.
- PASS: temporary speed controller tests for speed/pitch restore, original 2.0x, speed above 2.0x, not playing, engine replacement, and repeated start.
- PASS: fullscreen swipe detector tests for one-finger up and two-finger down, with rejection of single-finger down, horizontal movement, pinch, canceled and already scrolling touch sequences.
- PASS: `:app:testDebugUnitTest` (705 tests, 0 failures/errors/skips), `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, and `:app:runCheckstyle` on the final source.
- PASS: `git diff --check`; independent review found and prompted fixes for startup locale synchronization, drag after long press, and API 23 locale access. Read-only re-review found no further concrete blocker in these paths.
- NOT RUN: Real-phone gesture and title verification. The user reported default app and content language settings, but the device OS locale and raw provider response were not available.
- BLOCKED: API 23 synthetic touch instrumentation compiled, but UTP could not install the debug APK on the unaccelerated cloud emulator (`ShellCommandUnresponsiveException` at split APK install commit). No Android test body executed. The emulator was stopped after the failed attempt.

Independent review found and prompted both seek and center double-tap conflict fixes. Read-only re-review found no further concrete blocker in those paths. It also identified an unresolved edge: an inline touch starting inside the video briefly blocks the detail page while the direction is decided. A very short downward flick consisting of only one MOVE before UP may not scroll the page. Touches starting in the title or recommendations are unaffected. This needs real page gesture verification before claiming complete scroll handoff.

No main merge, APK release, or public issue was made by this work.
