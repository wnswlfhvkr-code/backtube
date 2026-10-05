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

## Full-suite crash isolated after permission correction

At `601222ad3ce5cbc59745d31cea47b4fdb848c2f3`,
[full run 37315825444](https://github.com/wnswlfhvkr-code/backtube/actions/runs/37315825444)
still **FAILed** on both APIs. API 35's related-list test passed, confirming the
notification fixture correction, but `removedSong` still ended the process.
Both full reports contain only 20 recorded tests (the runner says finished 21
of 60 before the crash): API 35 has 1 failure/1 skip; API 23 has 2 failures/1 skip,
including a MainActivity launch timeout. These are incomplete suites, not 60
executed tests.

The full logcat, rather than the empty crash buffer, contains ACRA's fatal stack:
`IndexOutOfBoundsException: Index 1 out of bounds for length 1`, through
`ManagedMediaSourcePlaylist.remove()` and `MediaSourceManager.onPlayQueueChanged()`.
[Preserved stack](evidence/android-playback-ci-2026-10-05/playlist-crash-api35-excerpt.log).
The guard incorrectly allowed `index == size`. Rapid append/remove queue events
can reach the source list after the item has already disappeared from the current
queue; the documented contract is to ignore an out-of-range deletion. The product
fix changes only `>` to `>=`.

Two new JVM regressions use the real ExoPlayer source list (only the item is
mocked), covering empty removal, negative/at-size removal and valid deletion.
Both failed with the matching bounds exception before the fix and passed after:
[RED](evidence/android-playback-ci-2026-10-05/playlist-boundary-red.xml),
[GREEN](evidence/android-playback-ci-2026-10-05/playlist-boundary-green.xml).

Earlier selected `removedSong` PASS was misleading in isolation: its logcat also
contains the same exception, which the normal app Rx error handler logged. DB
tests' `TrampolineSchedulerRule.reset()` erased that handler in the full suite,
allowing ACRA to terminate the process. The fixture now restores only the five
scheduler handlers it changed. Separately, three DB fixtures left a closed global
database; they now call the existing `NewPipeDatabase.close()` to clear its global
reference. No production error policy was relaxed. Final runtime verification
must check both the assertions and absence of the bounds/closed-DB exceptions.

[Timer-only run 37315880405](https://github.com/wnswlfhvkr-code/backtube/actions/runs/37315880405)
also **FAILed** on both APIs (1 test each). API 35 read the timer immediately after
an injected list click; API 23 could not find the 15-minute accessibility item.
The test now waits for the detail fragment to resume and for asynchronous timer
state changes under the original duration conditions. Missing UI text includes
the actual window contents. This is fixture synchronization/diagnostic work; the
timer UI's root cause is not yet declared resolved. No timer product change has
been made during this investigation.

All four XML reports at this stage are preserved as `after-permission-fix-*.xml`
in the evidence directory. Independent review confirmed the boundary fix and
fixture cleanup scope; fresh full/runtime validation remains required.

## Migration isolation and nullable legacy counts

[Full run 37318066153](https://github.com/wnswlfhvkr-code/backtube/actions/runs/37318066153)
at `05acf4430283c828817dbba6464a4b078fb19bc7` exposed another existing defect before
reaching the removal test: both APIs recorded 18 tests/1 failure and a process
exit in `RemotePlaylistItemHolder.updateFromItem()`. The stream count was null
and Java unboxed it to `long`. Both the entity and historical DB schemas permit
null, so this is a real legacy-data rendering bug, not an invalid SQL fixture.
[Crash stack](evidence/android-playback-ci-2026-10-05/legacy-count-crash-api35-excerpt.log),
`after-boundary-fix-*.xml` in the same evidence directory.

Migration tests had also used the actual application DB filename. Reopening that
DB after fixing the closed singleton surfaced their synthetic legacy bookmarks
in the next MainActivity test. The migration helper's create/validate/open paths
now all use `migration-test.db`, preserving every migration assertion while
separating the data from playback fixtures.

The product formatter separately accepts nullable `Long` and maps null to the
same empty label as the existing UNKNOWN count. Known and special-count branches
retain their behavior. New JVM coverage checks null, UNKNOWN and a known count;
the null case failed with NPE before the change while the other two passed.
[RED report](evidence/android-playback-ci-2026-10-05/stream-count-red.xml).
After the fix, all three cases passed
([GREEN](evidence/android-playback-ci-2026-10-05/stream-count-green.xml)); the local
full build/JVM/style run passed with 185 tests, no failures/errors/skips, using
the same temporary Build Tools 37 override.
Independent review verified the schema's nullability and that all migration DB
paths use the new name. This does not weaken or remove the failing UI tests.


## Selected runtime verification and accessibility fixture

[Selected run 37319230779](https://github.com/wnswlfhvkr-code/backtube/actions/runs/37319230779)
at `05acf4430283c828817dbba6464a4b078fb19bc7` ran the original two regressions and
the detail timer test on both APIs. The related-list and removed-song tests both
**PASSed**, with no ACRA, bounds, closed-database or undeliverable exception in
either captured logcat. Each API recorded 3 tests/1 failure, solely in the timer
fixture (`after-boundary-fix-selected-three*.xml`).

API 35 reached the custom dialog with visible `CANCEL`, but the selector required
case-sensitive `Cancel`. It now accepts the platform theme's capitalization.
API 23 completed preset selection, custom cancellation in paused/playing states,
extension and rotation. Its landscape list was still at the seventh of nine
options after the helper's six scroll attempts. The helper now permits twelve
bounded attempts to reach a fully visible target in this short viewport. It
still requires exact text apart from case, full visibility, real touch input and
all original timer/playback assertions. Fresh runtime results are required to
verify these fixture corrections; no timer product change was made.


## Late preference callback after detail screen teardown

[Full run 37319849305](https://github.com/wnswlfhvkr-code/backtube/actions/runs/37319849305)
at `88fca81576a5dfceff107da6cc8e37045d936077` passed JVM/build. API 23 completed its
runner's 62 cases; XML records 60 tests, 2 failures and 2 skipped tests. Failures
were the already-diagnosed timer scroll selector and a 45-second queue Activity
launch timeout in `recommendationMenuSupportsExcludeUndoAndClear`, still under
investigation. API 35 stopped early: runner finished 46, XML records 44 tests,
2 failures and 2 skips. The timer case failed on the already-diagnosed uppercase
button; the following test crashed in a late SharedPreferences callback.

The captured stack reaches `VideoDetailFragment.preferenceChangeListener` via
Android's queued `SharedPreferencesImpl.notifyListeners`. Its first `getString`
requires an attached context even after the old fragment has been detached.
Unregistering the listener does not retract a delivery already queued by Android.
A deterministic instrumented regression invokes that actual listener with a
contextless fragment; a FutureTask returns the exception to the test thread.
This is a separate existing lifecycle bug. The proposed minimal guard ignores
callbacks when `getContext()` is null; normal registration/unregistration and
attached-fragment handling remain unchanged. Each new fragment already reads
these preferences on creation. Independent review approved this scope.


The four-line context guard is implemented. The recommendation menu fixture now
prepares its existing local WAV and pauses it before injecting recommendation
metadata, matching the other queue UI fixture. Previously it left the real player
in PRE_FLIGHT, which renders an indeterminate progress animation; API 23's trace
shows the Activity RESUMED, service connected and window displayed, while
`startActivitySync` timed out waiting for idle. The real paused media setup keeps
all menu/exclusion/undo checks and the original launch/wait behavior. Runtime
validation will determine whether it resolves that fixture timeout.

Local build, test APK, 185 JVM tests and Checkstyle/ktlint all PASS after these
changes (temporary Build Tools 37 override). The new Android regression's source
was committed before the product guard at `0ebf5f1e95f29cbd535e073294d352c37545b28a`;
[its selected RED run](https://github.com/wnswlfhvkr-code/backtube/actions/runs/37321538652)
is recorded separately from the ensuing full-suite verification.
