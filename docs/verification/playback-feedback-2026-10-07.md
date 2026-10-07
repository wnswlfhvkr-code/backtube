# Playback feedback verification — in progress

Baseline: integration/approved-updates 896fa7aa5, verified implementation b5b6e3111.
New branch: feat/playback-feedback. Main 84b4e0e85 and PR3 bff8f4a44 are distinct
unmodified refs. No AGENTS.md was found under /workspace. Cloud only.

## Initial findings and product decisions

- The existing shelf was reachable only inside Downloads; managed-download
  completion skipped ordinary completion notification. Add independent access
  and a completion action. Preserve existing ordinary downloads.
- VideoDetailFragment Back calls queue.previous(); remove that behavior.
  Activity destruction also destroys MAIN playback, so UI-only exit requires
  explicit lifecycle handling, not merely changing the root Back callback.
- Existing expiry runs while reading/refreshing the store. Remove this behavior
  and migrate expiry fields. Previously deleted bytes cannot be restored.
- Existing ACRA sender opens the manual error screen; it is not an authenticated
  GitHub issue sender. Local sanitized outbox is separate from public delivery.

## Source/provider and offline feasibility audit

The actual app initializes NewPipeExtractor in `App.kt` and defaults to YouTube
in `util/ServiceHelper.kt`; dependency is v0.26.5. Playback uses ExoPlayer 2.19.1,
including YouTube-specific HTTP/manifest factories in
`player/helper/PlayerDataSource.java`. This is not the official embedded player
or a YouTube Premium offline API integration.

Ordinary playback cache uses externalCacheDir/exoplayer with an LRU evictor;
it is not a complete-media retention guarantee. App-owned saved files use
filesDir/offline in `offline/OfflineLibrary.java`, and READY entries resolve to
local media before remote metadata. `OfflineDownloads.java` defaults to validated,
unmetered Wi-Fi; Giga owns transfer/resume and dataSync foreground service work.
The playback service and its existing SleepTimer remain separate from transfers.

Official sources checked on 2026-10-07:

- [YouTube developer policies, III.E](https://developers.google.com/youtube/terms/developer-policies):
  API clients have explicit storage/offline restrictions. Calling a feature a
  temporary cache does not establish permission. This app's extractor architecture
  is separately identified rather than assumed to be an official API client.
- [YouTube service terms](https://www.youtube.com/static?template=terms):
  access/download permissions depend on service authorization or the specified
  written permissions. No legal conclusion about an individual user's rights is
  inferred from merely finding a playable URL.
- [YouTube official offline help](https://support.google.com/youtube/answer/11977233?hl=en):
  documents downloads within the official Premium experience and availability
  rechecks; does not document an export API for this app.
- [Android offline media guidance](https://developer.android.com/media/media3/exoplayer/downloading-media):
  retained download storage should not evict media. Current guidance uses Media3;
  this task preserves the repository's existing ExoPlayer/Giga architecture.
- [Android app-specific storage](https://developer.android.com/training/data-storage/app-specific):
  persistent files differ from cache files, but uninstall removes app-owned data.
- [Android foreground-service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout):
  Android 15+ dataSync time limits require safe stop/resume handling; they are
  not the same service type as mediaPlayback.

Recommendation: an explicit saved-media shelf for user-supplied or otherwise
authorized media, with local-first playback and clear incomplete/full states.
Under the latest request, retain until user deletion, not seven days. Keeping
the current local file when connectivity returns avoids disruptive replacement
mid-track. No new server/account/DRM bypass or actual protected-media download
is part of the changes or validation.

## Slow-speed audio investigation

No audible stutter has been reproduced in this cloud session. The baseline
uses stock ExoPlayer 2.19.1 audio rendering: platform playback parameters and
offload are not enabled. Normal PCM uses the default Sonic processing path.
Sonic's speed/pitch ratio drives time stretching; pitch equal to speed avoids
that stage but changes the sound's pitch, so it is a diagnostic comparison,
not an equivalent fix. The parameter dialog applies each slider movement,
making drag-only interruptions distinct from steady-speed artifacts.

Primary sources: [2.19.1 audio sink](https://raw.githubusercontent.com/google/ExoPlayer/r2.19.1/library/core/src/main/java/com/google/android/exoplayer2/audio/DefaultAudioSink.java),
[Sonic](https://raw.githubusercontent.com/google/ExoPlayer/r2.19.1/library/common/src/main/java/com/google/android/exoplayer2/audio/Sonic.java),
[Sonic processor](https://raw.githubusercontent.com/google/ExoPlayer/r2.19.1/library/common/src/main/java/com/google/android/exoplayer2/audio/SonicAudioProcessor.java),
[underrun events](https://raw.githubusercontent.com/google/ExoPlayer/r2.19.1/library/core/src/main/java/com/google/android/exoplayer2/audio/AudioRendererEventListener.java).

Recommended follow-up: generated non-silent tone/transients at speed 1/0.75/0.5,
pitch 1 versus pitch=speed, skip-silence on/off, fixed setting versus slider drag;
record underruns, sink errors, buffer position, focus and gain. Physical affected
phone/output testing is necessary to assess perceived quality. Coalescing slider
updates is cheap if drag alone causes interruption; buffer changes need proven
underruns and trade responsiveness; replacement DSP has higher regression cost.
No DSP or playback-speed behavior change is made in this task.

## Local verification and pending runtime

Initial integrated local run: 266 JVM tests, zero failures/errors/skips, debug
app and Android test APK compilation passed. First Checkstyle run found 19
formatting/documentation/import violations in six files; these were fixed and
the second Checkstyle run passed. Logs: /tmp/backtube-feedback-compile-2.log and
/tmp/backtube-feedback-style-2.log. Final rotation/layout and fixture changes
also passed the combined command (JVM tests, both APK builds, Checkstyle) in
/tmp/backtube-feedback-final-local-2.log. The JVM total remains 266 with no
failures/errors/skips. Shell syntax and git diff whitespace checks pass.

Storage owner observed six expected regression failures before the retention
change and then 25 standalone tests passing. Reporting owner ran 13 standalone
tests and targeted style checks. Independent read-only review found no remaining
actionable issue in retention, saved-list access, task removal, listening state,
gesture intent or sanitized reporting. Review is not device runtime evidence.

Android API23/API35 runtime and exact APK provenance remain pending. There is
no /dev/kvm in this executor; GitHub KVM runners will own device validation.

Listening mode is session state, retained through UI recreation but not a new
persisted preference across process death. Muxed media can still contain video
bytes in the fetched container even when video/text tracks are disabled. Tests
use generated black video and silent AAC, so they establish pipeline/navigation
behavior, not perceived audio quality. Popup tests temporarily grant overlay
permission only inside disposable emulators and restore the prior app-op mode.

## First remote run and fixture corrections

[Run 37656280463](https://github.com/wnswlfhvkr-code/backtube/actions/runs/37656280463)
at 297da5612 passed the continuous APK/JVM/lint job. Both API23/API35 suites ran
122 tests with 11 failures in the new fixtures. Existing timer, seed/restart,
saved playback and current-track UI dedicated phases passed on both versions.
The retained-after-restart screenshot shows both legacy entries available.

Three setup defects were identified from logs, without weakening assertions:

- Listening tests inflated with application context instead of the production
  PlayerService's themed context, failing before the track assertions.
- Drawer test searched `navigation`, but the layout include overrides its root
  ID with `drawer_layout`; the corrected test waits for the actual drawer.
- Navigation setup sent both a direct Player intent and a detail navigation
  request before initial loading settled. Logs show fragment stop/reuse and a
  completed-state Play resetting index 1 to 0. Setup now uses one actual user
  navigation request with explicit immediate playback, preserving index-1 and
  all Back/gesture/task-removal assertions.

After these test-only corrections, the full local JVM/build/style command
passed again (/tmp/backtube-feedback-fixture-fixes.log). A new runtime run is
required; the first APK is not delivered as a validated build. Environment/model
disconnects are separate from these observed test failures. The cloud worktree
and remote checkpoint remained available and matched when rechecked.

## Second remote run and remaining fixture corrections

[Run 37660911317](https://github.com/wnswlfhvkr-code/backtube/actions/runs/37660911317)
at f4d0569a3 passed APK/JVM/lint. Both full Android suites ran 122 tests, with
three popup tests failing and the three existing opt-in skips. The previous
listening, shelf access and Back/gesture fixture failures were resolved.
API23 also had one failure in the separate current-track UI phase.

The popup fixture called Player.handleIntent directly, omitting the service's
handleIntentPost callback that attaches the popup window. It now uses the same
NavigationHelper.playOnPopupPlayer service path as the app. The API23 landscape
fixture could accept the old activity's updated configuration before rotation
recreation finished; it now requires the replacement activity before checking
layout/artwork. No assertions were weakened and no production behavior changed
in these corrections. Another runtime run is needed before delivery.

## Third remote run: popup playback intent regression

[Run 37663048248](https://github.com/wnswlfhvkr-code/backtube/actions/runs/37663048248)
at c813dbfeb passed APK/JVM/lint and all tests except the three popup cases on
both Android versions (122 total, three existing skips). The separate current
UI rotation checks now passed. Actual popup windows attached and orientation
assertions passed; the next playing assertion exposed a production defect.

API35 log at 18:02:44.444 records playWhenReady=true while preparing. Expansion
then sent MAIN play_when_ready=false at 18:02:44.601 because NavigationHelper
used PlayerHolder.isPlaying() rather than the user's playback intent. The new
getPlayWhenReady query preserves buffering playback and deliberate pauses.
Popup fixtures now settle initial playback, stop the engine while retaining
intent, expand through the real button, and await READY before asserting play
state/orientation/control geometry. A fourth case preserves a deliberate pause.
Independent source review confirmed the transition cause and paused behavior.
No timeout or existing assertions were relaxed. The failed-run APK is withheld.
