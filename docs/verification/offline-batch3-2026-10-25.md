# Offline and finished-download storage review, 2026-10-25

## Scope and baseline

- Started from `9a1688c93a2dbff3179dd748ea39df65dec0ceb6` on a separate branch.
- Compared the two product files with Web review baseline
  `6095d418bb36e6bcc3a38d51a40fe99ac17a12f3`; they were unchanged.
- Reviewed Library bundle `libfile_e47011885bf081918f9eb0fa4d6b96b9`, SHA-256
  `f15783e3838e0bfcca4e674d7532590c5c7db3804dfd19953ac60a3a8a384ad6`.
  Its substitute Android boundaries and SQLite bridge are review aids, not device proof.
- Used only generated app-cache SQLite databases and JUnit temporary directories. No user
  download database, saved media, or backup was opened or changed.

## Reproduction and changes

| Case | Before change | Change |
| --- | --- | --- |
| Offline metadata without `id` or `state` | JVM test failed: `NullPointerException` escaped the `IOException` boundary. | Convert missing required fields to the existing invalid-metadata `IOException`; preserve stored bytes. |
| Initial shelf-order write fails | JVM test found published metadata after failed registration; a retry could create another record. | Persist order before publishing metadata for a new ID. Existing-record saves retain their path. |
| v2 finished mission with nullable `kind` | API 23 test found the row missing after migration. | Use the existing unknown-kind `?` convention during migration. |
| v3 duplicate legacy rows | API 23 test found insertion failure ignored and migration completed. | Abort migration on `insert == -1`; close the transaction in `finally` so SQLiteOpenHelper can roll back. |
| Empty finished-mission query | API 23 tracked Cursor: opened 1, closed 0. | Use try-with-resources for query and migration cursors. |

## Verification

- RED: two new `OfflineStoreTest` cases failed for their intended reasons. On API 23, two
  of three migration cases failed as expected; the valid v3 regression case passed. The
  separate empty-query Cursor case failed with opened 1, closed 0.
- GREEN: all 29 `OfflineStoreTest` cases pass. Full `:app:testDebugUnitTest` reports
  591 tests, 0 failures, 0 errors, 0 skips. `:app:assembleDebug` and
  `:app:assembleDebugAndroidTest` pass. Forced `:app:runCheckstyle --rerun-tasks` passes.
- Corrected API 23 instrumentation: 4 tests pass, 0 failures. This includes v2 NULL-kind
  preservation, v3 uniqueness-collision rollback (both original rows and version 3 remain),
  normal v3 migration, and actual SQLite Cursor closure on the empty query path.
- API 23 focused regressions: `OfflineFileStreamDeviceTest` and both
  `OfflineDuplicateDeviceTest` cases pass (3 tests, 0 failures). The tests use
  generated media and clean up their own records.

## Limits

- Order-before-metadata prevents one same-process duplicate-registration failure. It is
  not a claim of power-loss atomicity. A later metadata rename failure may leave an
  unknown ID in the order file, which startup filters and repairs best-effort.
- Invalid offline metadata is reported; this change does not reconstruct it. Migration
  fails closed on an unrecoverable uniqueness collision rather than deleting legacy rows.
- API 35 full connected verification is not available in this cloud emulator: both the
  prior and a clean temporary API 35 AVD had system startup ANRs before tests began.
- Backup restore risk R1 and Web review batches 4–11 are separate work. No APK release or
  main merge is included here.
