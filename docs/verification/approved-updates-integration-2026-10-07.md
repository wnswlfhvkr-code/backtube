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

## Automatic resume setting at checkpoint b855aa544

`PlayerHelper.isResumeAfterAudioFocusGain` and `video_audio_settings.xml` default
`resume_on_audio_focus_gain` to false. The Korean setting is “이어서 재생” under
video/audio settings. Tests explicitly cover enabled and disabled states. The
user's installed-device setting is unknown, and it was not changed. This can
explain an absent transient auto-resume when disabled, but does not establish the
cause of the user's reported quiet audio or behavior on their physical device.

## Integrated fixture rerun: 19f7bec4e

[Run 37607670381](https://github.com/wnswlfhvkr-code/backtube/actions/runs/37607670381)
passed JVM/build and API35. Both APIs completed the 105-case connected suite with
zero failures/errors and three opt-in skips. The repaired detail timer and direct
recovery cases passed; portrait/landscape timer screenshots now show visible
controls and successful generated content on both APIs.

API23's separate four-case current-UI phase failed one assertion immediately
after landscape rotation: `landscapeVisibleMetadataAcceptsScreenCoordinateSwipe`
found the current row hidden or empty. The test awaited orientation and cached
artwork but did not await the recreated activity's layout. Its log records the
new activity resuming and cached artwork arriving during window/layout setup;
the same case passed later in the connected suite. Require window focus and an
attached, shown, measured row without pending layout within the existing bounded
wait before strict geometry assertions. Preserve clipping/overlap and actual
screen-coordinate swipe checks; do not retry the test or change production UI.
Add measured dimensions to a remaining geometry failure for diagnosis. The APK
from this run was inspected but not delivered as the final verified build.

## Requested follow-up: enable automatic resume by default

After verified integration b855aa544, the user requested an ON default and a new
test APK. Change only the UI preference default and PlayerHelper's absent-key
fallback to true. Do not migrate or overwrite a stored false value. Older
automatically stored false values cannot be distinguished from a deliberate
opt-out, so both remain false. Fresh installs and absent-key settings use true.
The ordinary switch remains at queue overflow → Settings → “비디오 및 오디오” →
“동작” → “이어서 재생”. No player/focus/timer behavior is otherwise changed.

A focused JVM regression first failed against the old fallback (one expected
failure: missingPreferenceEnablesAutomaticResume); the explicit opt-out case
passed. Android coverage exercises real preference-resource initialization,
absent-key fallback and preserving saved OFF during reinitialization. The seven
real cross-UID focus cases now begin with an absent resume key instead of forcing
ON; the opt-out case explicitly stores OFF. Existing pause, timer, permanent-loss
and manual-gain assertions remain, and fixtures restore original preferences.

Use the existing CI continuous/debug signing route. Compare the produced APK's
certificate, package and version against b855aa544 before delivery; do not assume
that separate ephemeral CI runs reuse the same certificate. No signing key is
copied and no installed app data is deleted. Exact follow-up CI/APK evidence is
recorded in PR5 after verification.

## Synced verified checkpoint: b5b6e3111

Source `b5b6e311182e7011d833f6be0aba26f9ed19960f` passed
[CI 37646975276](https://github.com/wnswlfhvkr-code/backtube/actions/runs/37646975276):
JVM/build and both API23/API35 jobs succeeded. Local JVM verification reported
237 tests with zero failures/errors/skips; app/test builds and Checkstyle passed.
Each connected Android suite reported 108 cases, zero failures/errors and three
existing opt-in skips. The three real preference-initialization cases and seven
cross-UID focus cases ran successfully. Dedicated timer (1), offline (1+1+3) and
current-track UI (4) phases passed on both APIs. Live SubscriptionManagerTest
remained excluded; offline restart was exercised separately by seed/restore.

The unchanged CI APK is available in
[artifact 11495011105](https://github.com/wnswlfhvkr-code/backtube/actions/runs/37646975276/artifacts/11495011105).
It contains `app-continuous.apk`, 11,594,175 bytes, package
`org.schabi.newpipe.continuous.integrationapprovedupdates`, version `0.29.1` /
code `1015`, min23 / target35. Its SHA-256 is
`d963a0861ac83bf9445bb21128bc4b88611b4e9b6f36f62cbf80e250e1b7fee5`.
APK v1/v2 signatures and zipalign verified successfully; certificate SHA-256 is
`ae89a250cd498b77fc5012126dacd533b1c24fd8e2f1945ba50cf331f741ecad`.
Embedded build revision `6a014e7d124cdf6c57b95d36683fe78ee02ea8b2` is GitHub's
synthetic PR merge; its tree `c7e4b3a6a22dc12995bdb7f7a48944961376bde9` exactly
matches the source checkpoint. This was not an actual main merge.

The earlier b855aa544 APK has the same package/version but certificate SHA-256
`91e0c1e4fd4f3ae16b9f0ce23398797a2ab3cb78fdc9bbc6b079bfb4e3923a34`.
Because the certificates differ, the new APK cannot update that installed test
app in place. Fresh installation is possible where that package is absent.
No key copying or installed-data deletion was performed. A separate Library
copy was saved and its bytes retained the verified APK hash.

This synchronization only records already-completed evidence; no product code
or APK changed. Runtime checks above belong to b5b6e3111, not a new runtime run
for this documentation-only checkpoint. Physical-device acoustic behavior and
API37 runtime remain unverified. Prior external Claude review findings remain in
`offline-library-2026-10-07.md`; no original Claude patch was available locally
to archive or attribute as newly executed work.
