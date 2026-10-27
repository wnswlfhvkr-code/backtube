# Subscription boundary review batch 13, 2026-10-25

## Baseline and scope

- Based on batch 12 commit `63c6ff6e93949f3eb4e088c6ab20d051b7c2e842`,
  retaining earlier 1036 and batch 3–12 and 14 changes.
- Read the batch 13 Library patch `libfile_a509377ae7a8819194b5ff4ed453efd8`
  and review `libfile_a7d1dcea319c8191831b2ffba97c73e4`. The ZIP
  `libfile_98009d148a1c819190f75b0edb247809` was materialized locally;
  SHA-256 matched
  `09d7fdff82304f4a009ff81829e566e457e117695a8cdb91e3647e1076134a70`
  and ZIP CRC passed. Web substitute checks were not counted as app tests.
- Changes are limited to subscription notification mode, import/export stream
  failures, import foreground initialization, and stale group-view posts.
  A mode-column DAO query adds no schema/version or migration change.

## Reproduction and corrections

| Boundary | RED on current baseline | Correction |
| --- | --- | --- |
| Enable notifications | JVM test failed because `andThen(rememberAllStreams(...))` was discarded. An independent review then found that re-enabling an already enabled row, cancellation during baseline fetch, cross-screen enable/disable ordering, and a stale whole-row write could corrupt the intended mode or metadata. Focused tests reproduced each case before the final correction. | Baseline only on a genuine disabled-to-enabled transition, then publish the mode. Process-wide per-channel request generations keep a late enable from overriding a newer disable across the channel and settings screens. A column-only Room update preserves metadata refreshed during extraction. |
| Import foreground | Fresh WorkManager test worker threw `UninitializedPropertyAccessException` before `doWork()` initialized subscriptions. | Use progress maximum zero until the list is initialized; preserve the actual size later. |
| Provider stream unavailable | A test-only content provider returning null caused both import and export workers to return `Result.success()`. | Throw `IOException` for null input or output streams, keeping existing worker failure handling. A genuinely empty valid archive still succeeds. |
| Recreated subscription view | An old `View.post` task added two group rows to the adapter after its binding was replaced. | Capture the posting binding and ignore the callback when the fragment's current binding differs. |

All worker and Room checks used an in-memory database, generated example URLs,
test-only provider, and temporary cache files. No actual account, user
subscription, provider network request, or user export document was used.

## Verification and limits

- Final-source full JVM: **641 tests, 0 failures, 0 errors, 0 skips**.
  `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, and
  `:app:runCheckstyle` passed. Focused notification-mode JVM tests cover
  lazy subscription, one-time follow-up, missing and already enabled rows,
  cancellation, delayed subscription, cross-manager ordering, and metadata.
- Final-source API 23 focused tests: **8/8 PASS**. Five worker checks cover
  fresh and initialized foreground info, null input/output failures, genuine
  empty import, and genuine empty export. The group-post check verifies stale
  and current binding behavior. Two in-memory Room checks verify a disabled
  row, a missing row, exact URL identity with `%`/`_`, and refreshed metadata
  preservation. The first final run had one test timing failure because it
  asserted Room's asynchronous result before completion; adding a bounded
  await to that test yielded 8/8 on the unchanged product APK.
- An independent read-only review found no remaining commit blocker after
  the global request sequence and exact mode-column update. The reviewer did
  not run tests.
- Existing baseline policy still inspects only the first channel tab and
  swallows extractor/stream-write errors before enabling. Thus a channel with
  multiple feed tabs, an empty tab list, or a provider error may still have
  incomplete old-stream marking. This batch does not claim full initial
  notification correctness. The process-wide generation map retains keys
  touched during this app process; the per-key lock is held for the short
  synchronous Room mode update.
- Worker tests invoke `doWork()` directly rather than exercising WorkManager
  scheduling cancellation. The group test replaces a real Fragment binding
  while attached; it does not execute a full destroy/recreate transaction.
  Partial export writes, actual SAF providers, OS notifications, real feed
  providers and physical-device behavior remain untested.
- Existing batch 11 residual risk remains separate: a history deletion
  callback in `StatisticsPlaylistFragment.java` around lines 345–357 can
  reach a detached Fragment after navigation.
- API 35 remains untested after cloud AVD system ANRs; H.264 remains untested
  after an API 23 software decoder `SIGILL`. No main merge, new APK delivery,
  or public release is part of this batch.
