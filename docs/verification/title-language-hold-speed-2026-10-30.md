# Title language and hold speed verification (2026-10-30)

Baseline: `b966504dbcaa1b4a51a04080312ada227d9c8e95` (1041 source).

## Observed cause and scope

The default content-language preference previously resolved to the OS locale, separately from the app's visible locale. The extractor is initialized in `App.onCreate`, while migration of a saved app language occurs later in `MainActivity.onCreate`. Channel, search and related results use the extractor localization; the detail extractor can choose a different title from its player response. This is a confirmed path for different request languages, but the user's actual content-language selection and raw service responses have not been observed. An HTTP comparison request for the example public video was denied by this environment's proxy (403). No automatic per-video translation or requests were added.

The default content language now follows the app locale. Explicit content-language selections remain unchanged. A saved app language is considered before activity migration, and the extractor localization plus memory cache are refreshed on activity resume and when leaving content settings if the effective language or country changed. The resource-locale fallback uses AndroidX `ConfigurationCompat` for API 23 compatibility.

For MAIN video playback, long press starts a temporary 2.0x rate only while playing. Release, cancel, another pointer, drag, pause, completed playback, listening mode, fragment exit, or player teardown restores the original playback parameters on the same engine. The popup's long-press handler is unchanged. The temporary rate bypasses the preference-writing setter.

## Checks

- PASS: locale resolution tests for default, explicit language, saved setting before migration, changed app locale, and system resource locale.
- PASS: temporary speed controller tests for speed/pitch restore, original 2.0x, not playing, engine replacement, and repeated start.
- PASS: `:app:testDebugUnitTest` (697 tests, 0 failures/errors/skips), `:app:assembleDebug`, and `:app:runCheckstyle` on the changed source.
- PASS: `git diff --check`; independent review found and prompted fixes for startup locale synchronization, drag after long press, and API 23 locale access. Read-only re-review found no further concrete blocker in these paths.
- NOT RUN: Android gesture and title verification on a device; `adb devices` showed no connected device. The user's content-language preference and provider response for the reported video were not available.

No main merge, APK release, or public issue was made by this work.
