# 2026-10-25 Android Host capacity and redirect review

## Requested Android Host repair and storage boundary

The existing `shared:testAndroidHostTest` result remains 14 tests / 14
failures, all before assertions at Compose's Android
`RobolectricIdlingStrategy` because `android.os.Build.FINGERPRINT` is null.
`shared/build.gradle.kts` enables `withHostTest`, but its Android Host test
source set has neither a Robolectric dependency nor a runner; the common UI
tests call `runComposeUiTest` without a runner. The supported
`shared:verifySharedUi` task uses `shared:jvmTest`, which passed 14/14 in the
previous run. A Host-only runner arrangement and dependency are needed while
retaining JVM and device execution. No test was excluded or made to pass by
setting a fake fingerprint.

Read-only storage inspection before this follow-up found 270 MB free on the
workspace volume. The scoped scratch folder was 5.9 MB. Generated outputs in
this worktree were 6.6 MB at its root, 27 MB in `app/build`, and 30 MB in
`shared/build`; these are distinct from the 2.2 GB preserved Gradle dependency
cache and the 5.9 GB preserved API23 AVD. This request deleted none of those
files. The configured API23 AVD data partition is 2 GB. Its previous startup
was rejected by the emulator's `hasSufficientDiskSpace` check even with about
271 MB free; it was not retried.

The project uses AGP 9.2.1, Kotlin 2.4.10, JUnit 4.13.2 and AndroidX test
runner 1.7.0. Robolectric's official Gradle instructions require a test
dependency and compatible JUnit runner, plus JVM module-opening flags for Java
17 or later. The official Maven Central repository lists Robolectric 4.17,
whose base JAR is 172,497 bytes but whose POM declares multiple transitive
dependencies. Its instrumented Android runtime JAR for API23 is 79,019,399
bytes; the repository's latest listed runtime JAR is 238,744,924 bytes. After
the existing-code test rebuild, free space was only about 129 MB. The larger
runtime JAR alone exceeds that free space before transitive libraries and
generated build output. A pinned API23 attempt might use less, but its total
resolved size and compatibility with this project have not been proven. No
new dependency was downloaded or installed, no build configuration was
changed, and Host repair is blocked pending materially more free workspace
storage. At final measurement, exactly 135,225,344 bytes were free, so that
238,744,924-byte JAR alone would require at least 103,519,580 additional
bytes; transitive libraries and build products need further headroom. The
current worktree's generated `app/build` is about 151 MB, `shared/build`
about 21 MB, root `build` about 6.7 MB, and scoped scratch about 5.8 MB.
They remain untouched as cleanup candidates, separate from source and the
preserved cache/AVD. The emulator's exact minimum free-space threshold was not exposed;
do not infer it from the 2 GB virtual partition setting.

Sources checked:
[Robolectric setup](https://robolectric.org/getting-started/),
[Android KMP Host/Device configuration](https://developer.android.com/kotlin/multiplatform/plugin),
and Maven Central POM/artifact metadata for `org.robolectric:robolectric:4.17`
and `org.robolectric:android-all-instrumented`.

## Manual redirect response cleanup

In `YoutubeHttpDataSource.makeConnection(DataSpec)`, the manual redirect branch
received a local connection from the existing setup method but read its
response code and `Location` header before transferring ownership. Generated
`IOException` from response inspection or `RuntimeException` from header
inspection left that connection open. Three tests use the actual method and
connection/URL/URI doubles, with cross-protocol redirects enabled to reach the
manual branch. Before the correction, all three failed; after a guarded
disconnect on those inspection failures, all 11 setup/redirect tests passed.
The original exception is rethrown, and a disconnect runtime failure is
suppressed on it. An independent read-only review found no critical or
important issue in the changed path. No real network or user media was used.

Fresh app JVM suite: 589 tests, 0 failures/errors/skips. Checkstyle passed.
Android lint: 0 errors, 346 warnings. Shared source did not change and its
prior JVM 14/14 result was retained. Android Host 14/14 remains FAIL; API23
instrumentation is NOT RUN because the emulator disk preflight refused startup.
This repair covers only connection setup and manual redirect response
inspection; it does not assert that every HTTP connection path is leak-free.
R1 backup restore safety remains separate and unresolved. No APK, main merge
or release was made.
