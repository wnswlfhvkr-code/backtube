# Offline video playback — issue 20

APK1026 release finalization remains on hold. No release, tag or release asset was
created or changed during this work. The fix is a review candidate, with physical
phone validation still NOTRUN.

## Build identity

| Item | Value |
| --- | --- |
| APK1026 build source | `baf499bac357399d60c36bbdd47de667902ef938` |
| APK1028 build/product source | `99e644648235ae837034b6a11e0be473afc34458` |
| Candidate file | `backtube-offline-video-99e6446-1028-20261021.apk` |
| Package | `org.schabi.newpipe.continuous.testofflinelibrary20261011` |
| Version | `0.29.1-backtube-offline-video-20261021` / `1028` |
| Bytes | `11528716` |
| SHA256 | `7d55158e854a0ad00d259a2b02207ee5144808dd0d75fbf7131c6164847eb4ca` |
| Signing certificate SHA256 | `4fc26294d54b6c9adb276b0b3aab38592077fe28c9f3c64d93da5d9ff6bb5bbc` |

The package and valid signing certificate match the original APK1026, versionCode
increases from 1026 to 1028, minSdk is not raised, permissions are unchanged and
16 KiB zip alignment passes. Existing signing input was used directly. No key contents were
inspected or copied, and no new key was generated. GPL text, component notices and all 12 original icon
assets are preserved. A credential-indicator scan found no suspicious credential
filename, private-key block or recognized token marker; no matching values were
printed or stored.

APK1028 was built in a separate clean cloud worktree from the exact product
commit above. Subsequent test-fixture and verification-documentation changes do not change
`app/src/main`, shared product code or the APK bytes. The older `cb89c858...`
commit is prior test/documentation work, not the build source of APK1026.

## Root cause and fix

Saved metadata already distinguishes `video/*` and `audio/*`, but shelf single,
selected and all playback always launched AUDIO plus PlayQueueActivity. Local
navigation and restored detail metadata also redirected away from the MAIN video
surface. A valid saved video therefore could play only its sound.

An all-local queue containing any VIDEO item now opens the existing MAIN player,
including an audio-first mixed queue. Audio-only saves retain their background
player/controller behavior. The fragment reads owned local metadata and the
existing FileDataSource; it does not invent remote video stream URLs. Invalid
local download, popup and external-player actions are hidden or bypassed, and
local related tabs remain hidden across fullscreen changes. Sparse video
metadata still shows the playback duration and listening-mode control.

Listening/video renderer changes leave the owned progressive source, pending
recovery and play/pause intent intact. Expanding from the actual controller uses
its existing recovery snapshot and preserves pause, position and playback gain.
The download/mux implementation and saved-file bytes were not rewritten. Video
selection still dispatches video plus a compatible secondary audio stream to the
MP4/WebM muxer; audio-only selection remains a separate path and media kind.

## Automated verification

- Genuine RED: the four new video navigation tests failed against the original
  code (controller/audio delivery instead of MAIN); 23 existing audio/lifecycle
  tests passed. All four became GREEN after the fix.
- App JVM: **540 tests / 86 suites**, zero failures, errors or skips.
- Shared UI canonical JVM: **14 tests / 9 suites**, zero failures, errors or skips.
- Android instrumentation compilation, debug/test APK assembly and continuous
  candidate assembly passed. Lint: **0 Error / 0 Fatal / 345 Warning**.
- Checkstyle, Ktlint, dependency order and whitespace checks passed.
- Independent read-only SPEC/QUALITY review approved the product change and the
  real-controller test path; the reviewer did not run the tests.

The final product callback correction was followed by app JVM, lint, compilation
and debug assembly checks. The later codec-fixture/controller-test updates were
followed by Android-test compilation, Checkstyle and test APK assembly.

## Cloud runtime evidence and limits

Runtime validation uses only locally generated CC0 media, a read-only cloud API23
virtual device and an isolated debug package whose product sources match
APK1028 commit `99e644648235ae837034b6a11e0be473afc34458` (the continuous candidate binary was not instrumented).
Wi-Fi/data were disabled; Android connectivity reported no connected network.
A test Downloader rejects all extractor requests, with zero-request assertions.
The headless emulator checks decoded audio buffers, not human audibility.
No user PC or physical phone was accessed. Actual Back/task-removal lifecycle and
an installed 1026-to-1028 upgrade were not exercised.

Generated WebM VP8/Vorbis passed **two consecutive full runs, 3/3 tests each**.
The tests require rendered video and decoded audio output, a visible MAIN surface,
automatic audio-to-video transition in a selected finite queue, audio-only
regression and zero extractor Downloader requests. The single-video test also
checks listening/video switching, a visible valid surface after real controller
EXPAND, retained pause/1000 ms position, playback gain 0.6 and an active timer.

Native-crash leftovers initially reused a READY fixture identity, so the test
helper now gives each generated input a unique identity. This isolates fixture
allocation without deleting/resetting the app library. The final two runs have
zero failures. Product sources and the candidate APK bytes were not changed by
that test-only isolation update. See [machine-readable summary](evidence/offline-video-issue20-2026-10-21/summary.json).

The H.264/AAC MP4 contains both tracks as verified by ffprobe, but video decoding
on this cloud API23 x86 instance terminated natively with SIGILL in
`libstagefright_soft_avcdec.so` / OMX. Audio-only AAC decoded successfully. This is
recorded as an unsuccessful H.264 runtime attempt, not a pass. The default
instrumentation fixture remains MP4; `-e offlineVideoFixture webm` selects the
additional generated VP8/Vorbis fixture without changing product behavior.

The actual controller path also exposed a second regression: the intent sought to
its recovery position and cleared the pending marker, then the player-type-change
callback rebuilt the local source at zero. All-local type changes now update
renderers without rebuilding that source. A new JVM test first reproduced the
unwanted reload, and the runtime controller test retains pause/position/gain and
visible, valid surface assertions. Private candidate 1027 (`5e87827b...`) is
superseded, preserved and renamed DO-NOT-INSTALL; its bytes were not deleted.


Real network download and complete DASH mux output generation were not run.
Download dispatch/mux selection is covered at the real mission boundary; owned
local file playback is checked separately. A video-only input with no compatible
secondary audio can still contain only a video track. Physical phone H.264,
full-device regression and the actual candidate upgrade remain **NOTRUN**.
Existing saves with no video track require a video save to obtain one.

## Candidate installation and release gate

Use an update installation over the matching existing package to retain its
shelf/playlists. Package/signature/version compatibility is verified, while actual
phone data retention and upgrade execution are untested. Keep issue #20 and this
PR open for physical video validation. APK1026 is not a finalized release; no new
release publication or merge into main is implied by the candidate.

The exact corresponding source is available at
[product commit 99e644648235ae837034b6a11e0be473afc34458](https://github.com/wnswlfhvkr-code/backtube/commit/99e644648235ae837034b6a11e0be473afc34458).
Prior limitations tracked in issues #13 and #19 remain recorded; this change does
not claim a restoration of the retired trending provider.
