# Android playback CI failure investigation — 2026-10-05

## Scope and evidence before changes

The user requested correction of the existing Android CI failure blocking runtime
verification of Draft [PR #2](https://github.com/wnswlfhvkr-code/backtube/pull/2).
Minimal CI test selection and diagnostic collection are authorized. App features,
button placement, production permissions, SDK installation, merge and release are
outside scope.

The failure reproduced in the main baseline, PR #2 implementation, and exact-head
retry:

| Revision | Run / API 35 job | Result |
| --- | --- | --- |
| main `84b4e0e85f02f29a8523486345db5fc5df9e395d` | [36739430876](https://github.com/wnswlfhvkr-code/backtube/actions/runs/36739430876/job/109969607868) | FAIL |
| implementation `5edf0a41db0fb32f19e45258cd2a2b930ebb72be` | [37307864266](https://github.com/wnswlfhvkr-code/backtube/actions/runs/37307864266/job/111755808758) | FAIL |
| exact head `dc03e7723cd6f128375fcdd05ae0b5a95ec60e72` | [37308965568 attempt 2](https://github.com/wnswlfhvkr-code/backtube/actions/runs/37308965568/job/111764545377) | FAIL |

Official GitHub log/artifact tools retrieved all three reports. Each first fails
`PersonalPlaybackTest.relatedListStartsWithActualNextAndHidesUsedSongs:781`,
waiting for a RESUMED MainActivity in portrait, before opening the detail fragment.
`removedSongCanReturnUnlessExplicitlyExcluded` then has an empty failure stack;
instrumentation reports a process crash. The detail timer test has no recorded
result. The exact-head first attempt instead stopped before testing on Maven
Central HTTP 429; that error did not recur in attempt 2. API 23 was cancelled by
matrix fail-fast; JVM/build succeeded; sonar was disabled (SKIP).

Identical signatures establish an existing failure, not its cause. Existing
artifacts omit crash logcat. A permission dialog preventing RESUMED and incomplete
Activity/service teardown are hypotheses pending runtime evidence.

## Plan

1. Add optional dispatch test selection and preserve default full-suite execution
   on API 23 and 35. Capture logcat/crash and Activity/window state inside the
   emulator lifetime; retain original Gradle exit status and upload reports on
   both success and failure.
2. Reproduce the first failure and following test on both API levels, then apply
   only a confirmed product or fixture correction, with assertions preserved.
3. Run the original failing tests, detail timer test, full Android matrix, local
   builds/JVM/style checks, and independent review. Record remaining limitations.

The diagnostic assertion retains the original condition and five-second timeout;
it adds lifecycle, orientation, notification permission and PlayerHolder state.
No permission is granted or denied by this diagnostic change.

## Local diagnostic runner checks

`python3 .github/scripts/test_android_test_runner.py`: RED before implementation
(three expected failures because the runner was absent); GREEN after implementation
(5 tests). These check the default full suite, a literal selected-test argument,
nonzero Gradle status preservation after successful or failed diagnostics, and rejection of malformed
selectors. They are shell orchestration tests, not Android runtime tests.

Local Android builds use the already installed Build Tools 37.0.0 through the
temporary init script recorded in the detail timer verification. Default 36.0.0
remains unavailable. No local emulator/system image/KVM or real Android device is
available. Remote emulators use synthetic local WAV fixtures, not user media.

## Targeted RED and confirmed fixture defect

[Run 37313973834](https://github.com/wnswlfhvkr-code/backtube/actions/runs/37313973834)
executed both named failing tests at diagnostic commit
`f49c669f03623c264206c2ca5c62893af049c016`:

- API 23: **PASS**, 2 tests, no failures/errors/skips.
- API 35: **FAIL**, 2 tests, 1 failure, no errors/skips. The assertion reports
  `MainActivity:PAUSED orientation=1 finishing=false; notificationPermission=-1`.
  Logcat confirms `GrantPermissionsActivity` above the app. This establishes that
  the test omitted the expected notification prompt, not a portrait-layout bug.
- The following `removedSongCanReturnUnlessExplicitlyExcluded` test **PASSed** on
  both. No process crash occurred and the crash buffer is empty in this selected
  run. The original full-suite crash still requires verification; no PlayerHolder
  product defect is established by this evidence.
- Build/JVM/runner checks **PASS**; sonar **SKIP** (existing disabled job).

[API 23 XML](evidence/android-playback-ci-2026-10-05/red-api23.xml),
[API 35 XML](evidence/android-playback-ci-2026-10-05/red-api35.xml), and a minimal
[API 35 log excerpt](evidence/android-playback-ci-2026-10-05/red-api35-excerpt.log)
preserve this evidence. Full synthetic-emulator diagnostics remain in the run's
artifacts `android-test-report-api23` and `android-test-report-api35`.

The fixture now denies the actual notification dialog before testing playback
controls, asserts permission remains denied, and finishes Main/Queue Activity
instances in paused/stopped as well as resumed states. It does not change the
app's permission policy or grant any permission. All three MainActivity test
launches use the same helper. Assertions and their original timeouts remain.
Independent read-only review found no remaining blocker. A reused emulator where
Android no longer shows the permission prompt is outside this fresh-install CI
fixture assumption.
