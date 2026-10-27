# Important code review integration — 2026-10-25

## Scope and baseline

- The user narrowed the work to important code. This final pass addresses
  only the detached history-deletion callback that remained after the earlier
  [batch 11 review](local-list-batch11-2026-10-25.md).
- Baseline is `2d4093a0626a2556613c45b61717b69f624ffe65`, which retained
  the 1036 playback/holder work, approved batches 3–16, and the critical
  state-cache and sparse-item DB-error corrections. The earlier verification
  record is [critical state and DB boundaries](critical-state-db-boundaries-2026-10-25.md).
- This pass does not alter `HistoryRecordManager` transactions, schema,
  stream selection, download/offline files, settings, permissions, signing,
  or the public release. No actual user history or database was used.

## Reproduction and minimal correction

| Case | Original result with delayed dummy `Completable` | Current behavior |
| --- | --- | --- |
| Delete completes after Fragment detach | Success called `Toast.makeText(null, ...)`; error called a detached Fragment's snackbar path. Both were caught by RED tests. | Old view gets no success UI. Detached error goes through the captured application context's existing error-notification path. The DB operation is allowed to finish. |
| View is destroyed and recreated before completion | Old completion tried to show a Snackbar from the replacement view. | Feedback is accepted only when the original request View is still current. |
| Dialog click after detach | A late click reached `requireContext()` and threw before starting a deletion. | An action with no current View or Context returns without starting DB work. |
| Old dialog click after a new View exists | The old action started deletion as though it belonged to the replacement View. | Dialog action retains its opening View and rejects the stale click. A new dialog on the new View still starts deletion for the selected stream ID. |

The same focused suite checks attached error feedback and a manually canceled
subscription. The cancellation test proves that a disposed observer does not
deliver late UI callbacks; production does not cancel an already started
deletion merely because its view is destroyed. Existing selected-ID behavior
was tested with a valid attached View/Context fixture.

## Verification and limits

- The failing paths were observed before their respective fixes: 3/5 delayed
  callback tests failed, the detached late-click case failed, and the
  replacement-view dialog click reached the dummy DB request.
- After the corrections, the focused **seven** new JVM cases plus the
  existing selected-ID case passed. Final-source app JVM suite:
  **660 tests, 0 failures, 0 errors, 0 skips**. Debug app and instrumentation
  APK builds and Checkstyle passed. `git diff --check` was clean.
- An independent read-only review found the late-dialog cases; after fixes,
  it found no remaining blocker in the scoped diff. The reviewer did not run
  tests.
- The callback lifecycle was exercised with dummy Rx completions and mocked
  Android View/Context boundaries on the JVM. Actual Snackbar rendering,
  notification delivery, physical-device navigation, and Android Fragment
  recreation were not exercised by those JVM cases.
- Current-source API 23 focused instrumentation: **10/10 PASS**, comprising
  nine `HistoryRecordManagerTest` in-memory Room cases (including selected-ID
  deletion and rollback after injected DAO failures) and one real Bundle/file
  `StateSaverPublicationDeviceTest`. This does not substitute for an Android
  Fragment UI test of the new callback guard.
- The API 23 app installation stalled during `dex2oat` as emulator `/data`
  space fell. An earlier generated debug app from this branch was removed,
  the current APK was installed, and only this task's duplicate
  `/data/local/tmp/app-debug.apk` was removed after the package manager had
  copied it to staging. Final installation was confirmed by `pm path` and the
  focused instrumentation returned `OK (10 tests)` with code `-1`. No other
  app data or AVD was cleared. API 35 remained unavailable because of earlier
  cloud AVD system ANRs; API 23 H.264 decoding was previously blocked by a
  software-decoder `SIGILL`.

## Remaining important conditions

1. **R1 database restore:** live DB replacement, writer quiescence, WAL/SHM
   handling and failure rollback remain unproved. No risky replacement change
   was mixed into this pass.
2. **Subscription first tab/error handling:** the first channel tab is used for
   old-stream marking, and an extraction or database error can be swallowed;
   an actual false notification has not been reproduced.
3. **Previously corrupt state-cache file:** the new staged writer prevents a
   fresh failed serialization from publishing a reusable partial file, but
   the existing nonempty-file shortcut does not validate or repair already
   corrupt final files. Power-loss durability and concurrent same-prefix
   writers also remain unproved.
4. **Broader candidate list:** the previously triaged 22 high-impact
   file/request/playback paths are not marked reviewed or fixed by this pass.
   The 533 runtime-file inventory is not a completed full-project audit.

No new APK was delivered, and no main merge or public release was made.
