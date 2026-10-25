# Feed review batch 12, 2026-10-25

## Baseline and scope

- Based on batch 11 commit `1a173d5a1675d8008a1805a4a64d06cdbabc1bb1`,
  preserving earlier 1036 and batch 3–11 and 14 changes.
- Read Library patch `libfile_e5ae299fd2f08191b1ef0e706b186b66` and review
  `libfile_f5d0999970ec819192f29931705b6225`. Materialized ZIP
  `libfile_c4dbc144a6f48191b33ae951d9921669`; SHA-256 matched
  `3d55d54b55168e69aecb275e6f73fa0e0f1bd659026230b3fcb74ee6c7a5d615`
  and ZIP CRC passed. Web substitute checks are not app or Android validation.
- Product edits are limited to FeedFragment, FeedDatabaseManager and
  NotificationWorker. They do not change schema, feed timestamps, subscription
  identities, notification scheduling, permissions or app version.

## Reproduction and corrections

| Boundary | RED on baseline | Correction |
| --- | --- | --- |
| Feed shrinks after prior highlighted rows | Synthetic Groupie list with `lastNewItemsCount=3` and new count=0 threw `IllegalArgumentException` from `coerceIn(3, 0)` in the API 23 test. | Repaint up to the larger of old/new highlight counts, capped by the current item count; empty and one-row results can no longer reverse the clamp range. |
| Undated audio live stream | In-memory Room feed stored undated video live and recent dated audio, but omitted undated audio live. Older dated audio live and undated ordinary audio were correctly excluded. API 23 test failed. | Admit `AUDIO_LIVE_STREAM` alongside `LIVE_STREAM` only when upload date is absent. Keep the date cutoff for dated streams and notification candidate policy unchanged. |
| Notification avatar preparation | Existing worker used `observeOn(AndroidSchedulers.mainThread())` before a helper that calls Coil `executeBlocking`. The focused test initially failed to compile because the new IO render boundary did not exist. | Move the render stage to `Schedulers.io()` and expose the small actual Rx stage for controlled scheduler tests. Empty, error and cancellation semantics remain observable; later `Result.success()` and error reporting stay in the worker. |

The feed DAO's `unlinkOldLivestreams` query already names both live stream
types; batch 14 changed nearby feed data semantics, so this fix does not add a
second unlink policy or alter subscription identity. Tests use only synthetic
rows, example URLs and a new in-memory Room database. They do not fetch actual
provider content or read a user's subscriptions.

## Verification and limits

- Focused notification Rx tests: **4/4 PASS**. They check named IO execution,
  ordered side effects, empty/error handling, cancellation before IO delivery,
  and render failure propagation with synthetic callbacks. No OS notification
  or image request is made.
- Final-source full JVM: **633 tests, 0 failures, 0 errors, 0 skips**.
  `:app:assembleDebug`, `:app:assembleDebugAndroidTest` and
  `:app:runCheckstyle` passed.
- Final-source API 23 feed tests: **4/4 PASS**. Two new boundary tests cover
  shrink to zero/one item, undated audio live, dated normal audio and old live
  exclusion in in-memory Room. Two existing FeedDAO tests cover related data
  retrieval. Baseline was 2/2 failures for the two expected product behaviors,
  rather than setup errors.
- Independent read-only source review found no new blocking issue; the
  reviewer did not run tests. Once a blocking avatar call or render loop has
  started, work cancellation does not guarantee immediate interruption or
  prevent later notifications within that loop. This limit predates the IO
  move, and the controlled test covers only cancellation before IO delivery.
- Moving render work to IO is supported by the code path: NotificationHelper
  builds app-context notifications and uses blocking Coil; ErrorUtil posts its
  Toast through the main executor. Actual WorkManager cancellation during a
  blocking image request, vendor NotificationManager behavior, real avatar
  decoding, and background notification permissions are not proven by the
  controlled Rx tests.
- Existing batch 11 residual risk remains separate: after a history deletion
  finishes or errors following navigation, StatisticsPlaylistFragment's
  callback (`app/src/main/java/org/schabi/newpipe/local/history/StatisticsPlaylistFragment.java`,
  around lines 345–357) can reach a detached Fragment's context/error UI.
  This predates batch 12 and is not fixed by a feed change.
- API 35 remains untested after cloud AVD system ANRs; H.264 remains untested
  after an API 23 software decoder `SIGILL` in earlier work. Physical device,
  real feed providers, user data, main merge, a new APK delivery and public
  release are outside this batch.
