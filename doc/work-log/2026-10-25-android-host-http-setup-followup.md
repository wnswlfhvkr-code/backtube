# 2026-10-25 Android Host and HTTP setup follow-up

## HTTP connection ownership

Review of `YoutubeHttpDataSource.makeConnection(URL, ...)` after commit
`1b444fa62294cea56fa4992b4b19d1281bffcdde` found that a local
`HttpURLConnection` is acquired before several configuration calls. The prior
cleanup `try` began only at `connect()`, so a checked `ProtocolException` from
`setRequestMethod()` or a runtime failure from a setter left that local
connection outside the caller's `connection` field and without disconnect.

Two new tests invoke the actual data-source method with a connection double.
Before the follow-up change, both failed because `disconnect()` was not called;
after moving the existing `try` boundary immediately after local connection
acquisition, both passed. Existing tests for connect, output-stream acquisition,
write, close, cleanup failure, and successful POST also passed: 8/8. The
original error and suppressed cleanup error handling remain in the same catch.
An independent read-only review found no regression in this narrowed change.
It also noted that the separate manual-redirect `getResponseCode()` path can
still leave its returned connection open if response inspection throws; that
path was outside this setter follow-up and remains unverified here. No socket,
provider download, user file, header or URL policy was changed.

## Android Host test diagnosis

`shared:testAndroidHostTest` ran 14 common Compose UI tests and failed all 14
before their assertions. The first cause in each result is
`android.os.Build.FINGERPRINT` being null inside Compose's Android
`RobolectricIdlingStrategy` detection. This is a test execution setup problem:
`shared/build.gradle.kts` enables `withHostTest` and Android resources, but the
Android Host source set has no Robolectric dependency or runner, and the common
test classes have no Android JUnit runner annotation. Robolectric's
[getting-started documentation](https://robolectric.org/getting-started/)
requires both its test dependency and a compatible runner for JVM Android
tests. The supported repository task `shared:verifySharedUi` instead delegates
to `shared:jvmTest`, whose same 14 cases passed. Replacing or excluding the
failing Host tests was not done. A proper Host repair needs a host-specific
runner arrangement that retains the common tests on the JVM and device;
adding a dependency alone would not select that runner.

An API 23 cloud emulator is configured but was not running. A start attempt
after freeing this worktree's generated build intermediates left about 271 MB
free on the workspace volume; the emulator stopped at its own
`hasSufficientDiskSpace` preflight with `Your device does not have enough disk
space to run avd`. Thus Android device instrumentation for these 14 cases is
NOT RUN. No AVD image, user data, dependency or product configuration was
altered to bypass the check. Android Host 14/14 remains FAIL until a supported
Robolectric runner/dependency setup is added. A device with enough disk space
could separately run `shared:connectedAndroidDeviceTest` for Android runtime
evidence; it would not itself repair the Host task.

## Validation after the source change

Fresh app JVM suite: 586 tests, 0 failures/errors/skips. Checkstyle passed.
Android lint: 0 errors, 392 warnings. Shared JVM 14/14 passed in the prior
run and was unchanged by this follow-up. Real network and physical-device
behavior remain unverified. R1 restore safety remains a separate unresolved
issue; no APK, main merge or release was made here.
