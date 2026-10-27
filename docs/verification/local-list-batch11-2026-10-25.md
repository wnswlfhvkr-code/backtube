# Local list review batch 11, 2026-10-25

## Scope and source

- Baseline: batch 10 commit `1dd024a6d06268588281b46298beff412e477637`,
  retaining 1036 and batches 3–10 and 14.
- Library review `libfile_601811741c288191b8d500f6d19c1e44`, patch text
  `libfile_eaa4f4b07cf08191a434b02b85eda706`, and ZIP
  `libfile_b0b55f0e6e088191bed9e1ed556f730e` were read. The ZIP's
  SHA-256 matched `b2dc9f598015cbcb907f860918165a210c84c5453721c998108c8d1e2b57ce77`;
  its CRC test passed. The web substitute-boundary tests are not Android tests.
- Product edits are confined to HistoryRecordManager, StatisticsPlaylistFragment,
  LocalPlaylistFragment and LocalPlaylistStreamItemHolder. No schema, version,
  signing, permissions, offline files, playback queue or release state changed.

## Reproduction and correction

| Boundary | RED on baseline | Correction |
| --- | --- | --- |
| Selected history deletion | The old callback recalculated `indexOf(capturedRow)` from a new snapshot, then used index 0 if absent. The focused JVM test initially had no captured-entry delete method. | Keep the selected `StreamStatisticsEntry` in the callback and delete its immutable stream ID. A changed or missing row cannot substitute the current first row. |
| Atomic history and state deletion | The in-memory Android Room test injected a failure into the second DAO delete: 8 tests ran, 1 failed because state had already been removed. | Wrap both synchronous DAO deletes on the same `AppDatabase` in `runInTransaction`; keep lazy IO subscription and ID behavior. |
| Playlist sharing | Synthetic DB source emitted two snapshots after one click; `ShareUtils.shareText` ran twice in the RED JVM test. | `take(1)` before export; another explicit click can start another subscription. |
| Playlist rewrite guard | Empty completion and disposal left `isRewritingPlaylist` true in RED JVM tests; repeat operation was blocked. | `doFinally` on both watched and duplicate rewrite subscriptions releases the guard on success, error, empty completion and disposal. It does not cancel an already running DB write or change save policy. |
| Recycled playlist progress | API 23 view test bound a known-duration row then an unknown-duration row; progress stayed visible (expected `GONE` 8, actual `VISIBLE` 0). | Hide progress with duration when duration is unknown; known-duration logic is unchanged. |

All test rows, URLs and databases are synthetic. Trigger tests use an in-memory
Room database, including another ID to check unrelated data preservation. They
never open or mutate a user's history or saved media.

## Verification and limits

- Focused final-source JVM: 6 tests passed for captured ID, one share per
  click, and rewrite empty/error/cancel/retry paths.
- Full final-source JVM: 629 tests, 0 failures, 0 errors, 0 skipped.
- `:app:assembleDebug`, `:app:assembleDebugAndroidTest` and
  `:app:runCheckstyle`: passed.
- API 23 final-source focused results: **14/14 PASS**. These comprise nine
  HistoryRecordManager tests (including second-delete rollback, first-delete
  failure, selected-only success and absent-ID retry), one reused-row View
  test, one owned temporary on-disk playlist reopen test, and three in-memory
  LocalPlaylistManager tests.
- Independent read-only review found no regression introduced by this diff;
  the reviewer did not run tests.
- The history deletion completion/error callback can still access a detached
  Fragment after navigation. That behavior predates this batch; view/dialog
  recreation was a deferred web candidate and is not claimed fixed here.
- The focused Room trigger checks actual Android Room rollback, not long-lived
  concurrent writers, process death or persistent WAL recovery. Actual user
  data, provider content and physical-device UX were not exercised. API 35
  remains untested after cloud AVD system ANRs; H.264 playback remains untested
  after the API 23 software decoder `SIGILL` observed in earlier work.
- No new APK, main merge or public release is part of this batch.
