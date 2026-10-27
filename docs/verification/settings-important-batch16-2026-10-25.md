# Important settings boundaries, batch 16 — 2026-10-25

## Scope and baseline

- Baseline: batch 15 commit `7cef92f431ad9da646af9191c5e3a48b283d92e6`.
  The earlier 1036 and batch 3–15 changes remain in its ancestry.
- Library input `libfile_ccfbfae874f08191932367d2befd6245` was materialized
  with the supported consumer-local flow. SHA-256 was
  `07d44924ef77d0285729c6df5cd4925f3ff5dc3adf582b4d69e259af0fc9db69`;
  ZIP CRC passed. Its patch and review were also read through Library as
  `libfile_530f8ec64f0c8191a3cedda03c888da1` and
  `libfile_1109c643a7d0819195f26dbb901eb568`.
- The user narrowed the task to important code. The simple PeerTube radio-row
  display issue and notification search alias were inspected but left
  unchanged. Web substitute tests are not counted as Android verification.

## Reproduction and correction

| Boundary | API 23 RED on baseline | Minimal correction |
| --- | --- | --- |
| PeerTube request outlives its view | A pending test disposable remained active after `onDestroyView()`. A late dialog request with no view threw before it could start safely. | Clear view-owned requests when the view ends; return if there is no binding; accept success or error only for the binding that started the request. |
| Notification row removed or unbound | An unbound holder click delivered `NO_POSITION(-1)` to the listener, whose screen reads `currentList[position]`. | Ignore `NO_POSITION` at the holder; retain the existing valid-position callback and mode calculation. |

The Android fixtures use disposable test jobs and synthetic rows. They do not
make a PeerTube request, alter a user setting, or schedule a real notification.

## Verification and limits

- Final-source `:app:testDebugUnitTest`: **646 tests, 0 failures, 0 errors,
  0 skips**. `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, and
  `:app:runCheckstyle` passed in the same Gradle invocation.
- Final-source API 23 focused checks: **3/3 PASS**. The same three tests were
  **3/3 RED** on baseline. They verify pending-request disposal on view
  teardown, rejection of a late dialog request without a view, and rejection
  of `NO_POSITION` while a valid bound row still invokes its callback.
- An independent read-only review found no blocker in the two-file product
  diff. The reviewer did not execute tests.
- No actual PeerTube provider response, operating-system notification, or
  physical device behavior was verified. Directly forced late network callbacks
  were not executed; the same-binding check and disposal are source-verified.
  Rx disposal need not stop an already blocked network call. Multiple parallel
  additions in the same view can still hide a shared progress bar when the
  first completes; that pre-existing behavior was not changed here.
- API 35 remains unavailable because the cloud AVD repeatedly reached system
  ANRs; API 23 H.264 software decoding was previously blocked by `SIGILL`.

## Important unresolved boundaries

These are source-grounded risks, not reports of actual user data loss:

1. **R1 database restore, high data-integrity risk, unproven failure in the
   app.** `settings/export/ImportExportManager.kt` around line 75 asks
   `ZipHelper` to copy an archive entry over the live database with
   `REPLACE_EXISTING`, then deletes journal/WAL/SHM files. The database close
   and writer-quiescence boundary has not been proved, and a failed copy has
   no rollback. A staged, integrity-checked replacement and failure-injection
   proof are needed before changing this path.
2. **Sparse stream database error, high crash potential, failure injection
   not run.** `util/SparseItemUtil.java` around lines 108–118 subscribes to a
   database upsert without an error consumer. Its `doOnError` notification does
   not consume the error; `App.kt` treats `OnErrorNotImplementedException` as
   critical. This is conditional on the upsert failing.
3. **State cache publication, medium conditional state-loss risk.**
   `util/StateSaver.java` around lines 241–260 deletes matching older cache
   files before completing serialization of a new file. Disk failure or process
   death can leave no complete saved state. Uses seen here are temporary list
   and playlist-dialog state; canonical database loss is not established.
4. **Detached history callback, medium conditional UI crash risk.**
   `local/history/StatisticsPlaylistFragment.java` around lines 343–357 can
   use `getContext()` or UI error handling after navigation detaches the
   Fragment. The deletion transaction itself is not shown to be wrong.
5. **First channel tab and swallowed errors, medium notification correctness
   risk.** `local/subscription/SubscriptionManager.kt` around line 155 loads
   only `info.tabs.first()` for old-stream marking and ends in
   `onErrorComplete()`. Other tabs or extraction/database errors can leave
   streams unmarked, leading to possible false new-stream notifications.
   No actual provider reproduction was run.

The remaining source review is limited to high-impact paths: 15 file writers
and readers in `giga/io`, `postprocessing`, and `newpipe/streams`; five
request/playback availability files (`DownloaderImpl` and four `util/potoken`
files); and two partial boundaries (`App.kt` startup/global Rx and
`YoutubeDashLiveManifestParser.java`). These 22 files are a triage list, not
an authorization to implement them together or a claim that the complete
project was audited. No main merge, new APK delivery, or public release is
part of this batch.
