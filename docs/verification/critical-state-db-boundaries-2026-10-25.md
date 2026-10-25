# Critical state and DB error boundaries — 2026-10-25

## Request and baseline

- The user narrowed review to important code. This change addresses only the
  `SparseItemUtil` database-error path and `StateSaver` incomplete snapshot path.
  Baseline is batch 16 commit `eef0708dd5b7f35afb56be27cd94ffcc7b0e36e4`.
- The relevant text portions of web review batch 19 (`libfile_824eeb746fc48191ad1982b0af42dc15`
  patch, `libfile_02d553892ecc819186dbbe73055105f6` review) and batch 18
  (`libfile_eb140ebc4c0081918c0e5bf533da3b8c` patch,
  `libfile_44df15ce7ee4819197a9cd8116b0a312` review) were read through
  Library. Their substitute-model checks were not treated as app evidence.
- No other batch 17–19 suggestion, R1 live database restore change, schema
  change, main merge, release, or APK delivery is included.

## Reproduction and changes

| Boundary | RED on baseline/current path | Correction |
| --- | --- | --- |
| Sparse-item DB upsert fails after metadata succeeds | With a dummy DAO throwing, the outer metadata callback and one notification ran, but the inner argument-free `Completable.subscribe()` also sent an error to the global Rx handler. Focused JVM suite: 1/3 failed. | Give the inner subscription one terminal error consumer that displays the existing notification. Leave the metadata callback and DB scheduling unchanged. |
| State snapshot serialization, stream close, or publication fails | In an isolated temporary directory, each injected failure deleted the previous complete file before publishing the replacement. Initial suite: 2/3 failed; separate close case: 1/1 failed. | Serialize into a temporary file in the cache directory, close it, publish with rename, and only then retire older generations. Best-effort cleanup targets only the unpublished temporary file. |
| Preserved file loses its caller handle | After staging the file, all three disk-failure cases still returned `null`; callers assign that result and their replacement Bundle lacked the old handle. Follow-up suite: 3/4 failed. | On disk replacement failure, return an existing nonempty disk `SavedState` and put it in the replacement Bundle. Do not use a memory-only handle as a disk fallback. |

The only modified product files are `SparseItemUtil.java` and `StateSaver.java`.
The tests use a dummy DAO, generated metadata, temporary files, generated
serializable values, and an isolated debug-app cache directory. No real
network request, user database, saved download, or user state file was read or
modified. Cancellation before serialization, successful replacement, and
memory-only rotation were also checked.

## Final verification

- Final-source app JVM suite: **653 tests, 0 failures, 0 errors, 0 skips**.
  Focused new tests: SparseItem DB 3/3 and StateSaver publication 4/4.
- `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, and
  `:app:runCheckstyle` passed; `git diff --check` was clean.
- API 23 focused instrumentation: **1/1 PASS**. It saves an old state,
  injects incomplete serialization, restores the old state through the
  replacement Android Bundle, then publishes/restores a valid new state.
  The initial Gradle connected run stalled at installation. Manual ADB
  installation found a provider-authority collision with an older generated
  batch 16 test APK; only that test APK was removed. The final focused ADB
  instrumentation returned `OK (1 test)` and `INSTRUMENTATION_CODE: -1`.
- Independent read-only code review found the caller-handle loss; the test and
  product fix were added, then the reviewer found no new blocker. The reviewer
  did not execute tests.

## Limits and remaining important risks

- A nonempty final cache file that was already corrupt before this fix is
  still reused by the existing shortcut; this patch prevents newly failed
  writes from creating such a reusable file. It does not validate old cache
  contents or guarantee fsync/power-loss durability, atomicity among concurrent
  writers of the same prefix, or a particular platform's rename behavior.
- `WriteRead.writeTo()` executes before the disk error handler. An exception
  there still propagates to its caller. The cancellation fixture verified
  preservation of the previous disk file, not Bundle fallback for that case.
- The previous handle fallback requires an existing nonempty file; a missing
  or memory-only state is not presented as a durable snapshot. Real user
  process-death timing and a physical device were not tested. API 35 remains
  blocked by prior cloud AVD system ANRs.
- R1 live-database restore quiescence/rollback, detached history callback,
  and subscription first-tab/error handling remain the important separate
  items recorded in the batch 16 journal. The broader 22-file high-impact
  triage list is still unimplemented.
