# Backtube prior-update integration and real audio focus verification

Status: pre-integration real-focus tests compile; Android runtime pending.

## Report provenance

The user described quiet audio and no automatic restart after another video app
exited on the existing installation. They explicitly had not tried the 19b4522
test APK. Existing synthetic-callback results at that head do not prove real
cross-app focus exchange or a physical-device resolution.

## Platform contract checked 2026-10-07

[Android audio focus](https://developer.android.com/media/optimize/audio-focus)
distinguishes transient interruption (retain intent; resume/unduck after GAIN)
from permanent LOSS (explicit user Play is required). API23 uses the legacy
request/abandon API. API26 adds system ducking; Backtube requests
`setWillPauseWhenDucked(true)` and handles its callback itself. API31+ can also
fade/mute at the system layer. For target35+, focus requests require a top app
or foreground service. App gain and system output volume are separate; testing
ExoPlayer gain is not acoustic loudness measurement.

[Android17 background audio hardening](https://developer.android.com/about/versions/17/changes/bg-audio)
requires visible activity or non-short foreground service for background audio
interactions even below target37. Target37 additionally requires while-in-use
service capability (apart from the documented alarm exception). Backtube targets
35 and compiles against37: compile SDK does not opt it into target37 rules.
The new restrictions cover focus, playback and volume APIs. API23/35 results
cannot establish API37 behavior. No API37 runtime result is claimed here.

The app uses a media-playback foreground service. Default background mode keeps
its notification during pause; alternative minimize-none/video behavior can stop
foreground status. This is source inspection, not proof of every lifecycle path.

## Intended evidence

Use the existing generated-WAV PlayerService fixture and a separate test APK
Activity owning a MediaPlayer/focus request. Assert distinct UIDs and log real
grant/loss/gain events. Test transient return, app duck restoration, permanent
loss without an unsolicited restart, manual Play at normal gain, explicit pause
and timer expiry. Do not download remote media or change user device settings.

PR4's original source, passing run and delivered APK remain unchanged while this
work proceeds on `integration/approved-updates`.

## Pre-integration local validation

Added seven `RealAudioFocusHandoffTest` cases, a framework-only
`AudioFocusOwnerActivity` in the instrumentation APK manifest, and generated WAV
support. The test asserts that the owner's UID differs from the instrumented
Backtube UID. Both real focus requests and OS callbacks are logged. The seventh
case explicitly retains an app duck of 0.2 through user pause/abandon, then checks
that manual Play restores 1.0 without an intervening GAIN callback.

`assembleDebug assembleDebugAndroidTest runCheckstyle` passed; after the seventh
case was added, `assembleDebugAndroidTest runCheckstyle` passed again.
`testDebugUnitTest`: 233 tests, zero failures/errors/skips (37 XML reports).
Diff whitespace and shell syntax checks passed. These are local compilation and
JVM results; the seven new tests still require actual Android execution.

## First real-focus CI: a1bccbfb1

[Run 37601044631](https://github.com/wnswlfhvkr-code/backtube/actions/runs/37601044631):
API35 passed all seven real-handoff tests and the 104-case connected suite
(zero failures/errors; three pre-existing opt-in skips), plus dedicated local
lifecycle/UI phases. API23 failed all seven new cases at the same fixture UID
assertion: `testContext.getApplicationInfo().uid` was 0 while the real test owner
reported UID10056. The target process was UID10055. This is a fixture lookup
failure, not evidence that all seven playback behaviors failed.

Resolve the installed test package's ApplicationInfo through PackageManager,
retaining both the exact owner-UID check and the distinct-target-UID assertion.
The API23 trace also observed GAIN after a permanent LOSS when the owner left;
API35 did not. The permanent-loss test must accept either callback sequence and
require that playback remains paused without a new request until explicit Play.
It must not turn the documentation's usual callback sequence into a universal
platform assertion. The explicit-abandon test still verifies the no-GAIN path.
Both changes affect tests only. Revalidation is required before integration.

## Pre-integration verified checkpoint: d6d84955c

[Run 37602506632](https://github.com/wnswlfhvkr-code/backtube/actions/runs/37602506632)
passed JVM/build and both API23/35 jobs. Each connected suite had 104 cases,
zero failures/errors, and the same three opt-in skips; all seven cross-UID focus
cases passed. Dedicated generated local lifecycle phases (1+1+3) and current UI
tests (4) also passed. Production source still matched PR4's 19b4522 exactly.
The tests observe app gain and unchanged system-volume setting, not acoustic
loudness or a user's physical device. API37 remains untested.

The diagnostic `dumpsys audio` file used `.txt`, which the report step treated
as instrumentation output and incorrectly annotated as an error. Change it to
`.log` for subsequent runs; the actual Android test jobs passed.

## Integration implementation and local checks

Restored PR2's shared SleepTimerDialog/detail button and final PR3 detail-timer
scenario. Removed the redundant detail Background button and its now-unused
private handlers; automatic background continuation was not altered. The current
artwork/swipe binding, offline source/store, AudioReactor and recovery code remain
unchanged. The PR2 merge conflict was limited to obsolete widget imports.

Restored 12-attempt short-landscape scrolling and the real notification denial
path. A dedicated phase launches a fresh emulator app process, clicks Deny and
asserts the denied state through timer operation before the usual permission
grant. The activity request flag is asserted false beforehand, preventing a
silent skip. Portrait/landscape timer screenshots are retained. Shared test UI
helpers moved into PlaybackTestUi to preserve the 2000-line Checkstyle limit.

Timer layout contract first failed both cases against the unintegrated resources
(missing timer and missing horizontal scroll container). After integration,
full JVM tests, debug app/test APK builds and Checkstyle passed locally. Exact
integrated Android results will be recorded against the committed head.

## First integrated runtime and fixture repairs: e9a32efcf

[Run 37604483522](https://github.com/wnswlfhvkr-code/backtube/actions/runs/37604483522)
passed JVM/build and API23. API35 failed in the setup of
`PlaybackRecoveryTest.metadataRefreshTimeoutIsTerminal`, before its test body:
the generated player reached READY at 42000 ms, then an actual System UI
MediaSession STOP reset it to IDLE/0. Logcat identifies
`com.android.systemui` and `MediaSessionRecord:stop`; the old service/session had
already been destroyed and a new session created. A delayed notification cleanup
using the same notification key is the inferred trigger, consistent with
[Android15 LegacyMediaDataManagerImpl.dismissMediaData](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/packages/SystemUI/src/com/android/systemui/media/controls/domain/pipeline/LegacyMediaDataManagerImpl.kt).
This is not evidence that the metadata-timeout behavior failed.

Isolate the direct Player recovery fixture from System UI transport commands by
destroying its MediaSessionPlayerUi before player initialization. Keep its actual
foreground notification and audio focus. Do not ignore production STOP, extend
the timeout or remove recovery assertions. Separate AudioReactorPlaybackTest and
RealAudioFocusHandoffTest retain real session/focus coverage; this isolated
recovery fixture itself does not establish System UI transport integration.

Visual inspection also rejected the initially passing timer screenshot evidence:
portrait showed an extraction error and landscape placed the timer below the
viewport. The synthetic item's comments request cleared InfoCache while detail
metadata was loading. Disable comments only in this fixture, refresh generated
metadata after MainActivity launch, and restore the prior preference after the
activities finish. Require visible, successful expanded detail content, scroll
the timer fully into the viewport, use screen-coordinate taps, and recheck
visibility after rotation and around screenshots. Hidden `performClick()` calls
are no longer accepted as timer UI evidence. These repairs change tests only.

After these fixture repairs, local `testDebugUnitTest`, `assembleDebug`,
`assembleDebugAndroidTest` and `runCheckstyle` completed successfully. The JVM
reports contain 235 cases with zero failures/errors/skips. Shell syntax and
`git diff --check` also passed. The subsequent committed head still needs its
own Android runtime result; no passing screenshot claim is made from this build.

The duplicate manual run 37604511644 was intentionally cancelled after the PR run
appeared. It is not an additional regression result.

## Automatic resume setting

`PlayerHelper.isResumeAfterAudioFocusGain` and `video_audio_settings.xml` default
`resume_on_audio_focus_gain` to false. The Korean setting is “이어서 재생” under
video/audio settings. Tests explicitly cover enabled and disabled states. The
user's installed-device setting is unknown, and it was not changed. This can
explain an absent transient auto-resume when disabled, but does not establish the
cause of the user's reported quiet audio or behavior on their physical device.
