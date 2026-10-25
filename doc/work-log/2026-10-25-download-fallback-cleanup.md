# 2026-10-25 downloader fallback cleanup

## Request and source

The web review of current Backtube source `6095d418bb36e6bcc3a38d51a40fe99ac17a12f3` identified a failure path in `DownloadRunnableFallback`. This task is isolated from the unresolved R1 backup-restore work. The review archive was materialized with Library identity `libfile_4c457a4a836481919c5b58386fd80fdb`, SHA-256 `dc5e7a175844a8fdf6d2f75a329c6b99c502db9c0ffc80f0b446901b60bc3a9a`, 14 entries and valid CRC. The suggested patch was checked against the actual source rather than applied directly.

## Cause and correction

`run()` assigns `mConn` only when `openConnection()` returns. When connection setup throws, the catch calls `dispose()`, which previously called `mConn.disconnect()` despite `mConn` being null. The resulting NPE interrupted the existing resume-offset, cancellation, retry and error-notification logic. The product correction guards only that disconnect. No download queue, file, storage, URL or network configuration was changed.

## Test sequence

- Added three test-double cases for setup failure with zero retries, one retry, and a canceled mission. They call the real `run()` method; no network or user files are used.
- The first test run failed during setup because Android `Log.i` is unavailable in plain JVM tests. Static Android log calls were then mocked in the test fixture; no product code changed for that failure.
- With the corrected fixture and original product code, all three cases failed with `NullPointerException` at `DownloadRunnableFallback.dispose()` line 42, reached from `run()` catch line 110. This is the expected RED result.
- After the null guard, the same three cases passed. Full JVM app suite: 571 tests, 0 failures/errors/skips. Shared JVM suite: 14 tests, 0 failures/errors/skips. Checkstyle passed. Android lint: 0 errors, 392 warnings. Compared with the earlier local 346-warning report, the additional 46 are dependency/Android Gradle Plugin advisory categories; no new code warning category appeared.
- An independent read-only code review found no critical or important issue in this scoped change and distinguished the fixture failure from the valid RED run.

Android download instrumentation, an actual provider failure, real network transfer and physical-device tests were not run for this change. Earlier offline download and duplicate-save findings are historical evidence, not rerun results here. No APK, main merge or release was made in this task.
