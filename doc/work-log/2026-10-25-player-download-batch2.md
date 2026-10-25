# 2026-10-25 player and download review, batch 2

## Source and scope

The web review archive `backtube-delta-player-download-6095d418-batch2(1).zip`
was materialized from Library ID `libfile_71627414b02881919880c85f1d12eb9e`.
Its SHA-256 was `3ff88f20d462439fed552971cad1c64b7e32cb7cd5c7c0bca9ad988706a14566`;
all 68 ZIP entries passed CRC validation. The four proposed patches were compared
with actual source rather than applied blindly. This branch starts at
`9dd73d16884e3ce77da6007339d5ce34b97c67ef`, which includes APK 1036
source, the R2 import validation source change and the prior fallback null
connection fix. R1 restore safety remains a separate unresolved issue.

## Findings, corrections and RED to GREEN evidence

- Forced pause cleared `DownloadMission.init` and `threads` immediately before
  `pause()` tried to signal them. Two app JVM tests with live generated workers
  failed on the original code because neither worker observed interruption;
  both passed when the references were retained. `joinForThreads(-1)` does not
  join, but an overridden worker `interrupt()` can itself block during
  connection cleanup. Immediate shutdown on a slow provider is not proven.
- `DownloadMission.notifyError(int, Exception)` dereferenced a null
  `IOException.getMessage()`. Two tests failed with an NPE before error state
  was recorded; both passed after guarding the message while preserving the
  existing `storage.canWrite()` fallback. Independent review found the same
  null-message risk in the initializer catch, which otherwise skipped retry
  and notification. Its actual `run()` path failed before the fix and passed
  afterward, with the original generated exception delivered to `notifyError`.
- `DownloadManagerService.handleMessage` passed the value's index from
  `SparseArrayCompat.indexOfValue()` to `remove(key)`. Two tests using the
  actual AndroidX collection failed: a matching failure remained, and an
  unrelated entry at key `-1` could be removed. Both passed with guarded
  `removeAt(index)`.
- `YoutubeHttpDataSource.makeConnection()` held a local connection during
  `connect`, POST output acquisition, write and close. On failure it had not
  yet assigned the instance field that outer cleanup uses. Tests invoke the
  actual method with connection/stream doubles, without sockets. With the
  original code, five failure cases missed local disconnect or stream close;
  the success case passed. After scoped try-with-resources and cleanup, all
  six passed, including original-exception preservation when disconnect itself
  throws. Setup setters before `connect()` remain outside this guarded region;
  their rare exception path is not covered by this correction.

The tests do not use personal files, real provider downloads or network
requests. They do not claim Android device behavior for thread interrupt
latency, notification UI, or ExoPlayer transfer listeners.

## Validation and limits

Fresh app JVM test suite: 584 tests, 0 failures, 0 errors, 0 skips. Shared JVM
suite: 14 tests, 0 failures, 0 errors, 0 skips. Checkstyle passed. Android
lint reported 0 errors and 392 warnings. The unrelated
`shared:testAndroidHostTest` task failed all 14 tests because the local Android
stub exposed a null `Build.FINGERPRINT` to Compose's Robolectric detection;
this task is separate from `shared:jvmTest` and did not pass. No product or
shared source was changed to mask it.

Actual Android instrumentation, physical-device provider behavior,
network retry/cancellation, and the R1 destructive restore boundary were not
run or resolved here. No new APK, main merge or release is part of this batch.
