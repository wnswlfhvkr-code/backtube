# Backup restore review after APK1036

Base: `5f2d1c154d52d9706d7c28049024e710763fec51` (APK1036 product source `e3f506218aca6ec4c9c15c9e449b8ac1bf3c19fb`).
The backup restore files were unchanged between APK1035 and this base. The web candidate was read as review input, not applied blindly.

Inputs were materialized and read in this consumer using the official Library flow:

- Review `libfile_30ad936b3a788191abc73f16d739dec2`, SHA256 `a030fc26391a50936b205be8793adb3032873f81fa566e5d5c30f684ba821048`; 26,697 bytes, 8 ZIP members/CRC PASS.
- Candidate `libfile_5c4cf5d041048191b01a6146a1871ba5`, SHA256 `0413f2815581cc91ee92cf85dc9a0da4b71767546b95c974d26bb3c5b7bf2f9f`; 12,158 bytes, 11 ZIP members/CRC PASS.

## R2 minimal correction

- `extractDb == false` now returns immediately: no preferences offer/import, import URI save or restart after missing DB.
- Replaced construction-only ZIP acceptance with nonempty local-entry reading, payload CRC validation, at most 1,024 entries and 512 MiB decoded bytes, interruption checks and a 30-second cooperative deadline.
- Validation runs on an IO worker, with a 30-second Rx result timeout. The existing confirmation leads to a cancellable progress dialog. Cancel, replacement request or view destruction disposes the job and invalidates its generation. A late provider result cannot start DB extraction.
- Existing DB extraction/settings behavior after successful preflight is otherwise retained. `finishImport` package visibility enables an isolated Android fragment fixture to observe completion without restarting the test app; production completion behavior is unchanged.

This is a bounded readable-entry preflight, not a complete ZIP central-directory validator. It does not validate SQLite contents or schema. A provider read/close that ignores interruption may remain blocked; the timeout bounds UI/result acceptance and prevents continuation, not termination of arbitrary provider code. Input is reopened for extraction, so mutation or an IO error during that second read remains an R1 risk. The DB copy and settings parsing were not moved into an unproven concurrent replacement protocol.

## R1 remains unresolved

Concrete path: overwrite confirmation → `importDatabase` → `ImportExportManager.extractDb` → `Files.copy(REPLACE_EXISTING)` into the target → delete journal/WAL/SHM on success. `NavigationHelper.restartApp` closes Room only later.

`NewPipeDatabase.getInstance` synchronizes singleton creation; `close` synchronizes the singleton reference. Neither provides a maintenance gate around every DAO operation. `HistoryRecordManager` retains an `AppDatabase` and DAO references and schedules history transactions on IO. No global stop/drain boundary for retained users was established. This does not prove that a connection is always open, nor that every retained DAO necessarily reopens after close.

`RestoreCopyRiskTest` injects a mid-stream read failure into the real manager/ZipHelper copy, using only temporary generated files. The strengthened final diagnostic first passes the real ZIP preflight, then fails during the second open/copy. The existing target bytes are changed before IOException; there is no rollback. This is a reproduction of partial target-file replacement, not a demonstration of corruption of a live Room database with concurrent writers.

A close-only or atomic-rename-only patch would not establish consistent ownership of the DB and sidecars. No live DB swap/rollback implementation was added. A complete R1 solution requires a verified exclusive ownership/drain boundary plus staged SQLite validation and consistent recovery; that is beyond this safe minimal change. Do not describe this branch as safe transactional restore.

## Verification ledger

- Original ZIP checker: 7 tests, 6 expected failures (text, CRC, read error, cancellation, count cap, decoded-size cap); normal archive PASS.
- R1 diagnostic: PASS means the unsafe partial-write behavior was reproduced, not fixed.
- Initial Android missing-DB attempt: FAIL at test window retrieval (`restore window missing`), not a product RED. Replaced that brittle root lookup with the existing project's actual accessibility-window event pattern. Corrected baseline/green results are recorded in the evidence JSON.
- Final JVM, lint, compile and real Android results are recorded separately; no JDK model result substitutes for Android execution.

Only generated temporary DBs/files and isolated named test preferences are used by restore tests. No user data, credentials, main merge, release, Windows work, Preview removal or unrelated code cleanup.

## Final observed results

- Corrected original Android fixture: expected RED `missing DB offered preference import`; app did not crash.
- First candidate Android run: five product cases PASS; four delayed-provider test prerequisites FAIL because Android used `read(byte[])` rather than the intercepted three-argument overload. All read overloads are now intercepted; assertions were retained.
- Final real API23 restore fragment: nine/nine PASS, including normal closed SQLite backup, no DB, text, CRC, open/read failure, user cancellation, removal, timeout and superseded selection. Target bytes and isolated preferences are asserted unchanged on failed/canceled validation.
- Existing API23 holder/initial-source-failure/offline-video/duplicate-save regressions: twenty/twenty PASS on the corrected product app.
- Product-code full JVM: app568/shared14 PASS, lint0 errors/346 warnings, Checkstyle PASS. The R1 diagnostic was then strengthened with successful preflight before second-read failure and separately passed. Final Android test-fixture changes compiled and passed Checkstyle; no later product change.
- These results do not solve R1 or certify transactional backup restoration. No new deliverable APK, main merge or release.
