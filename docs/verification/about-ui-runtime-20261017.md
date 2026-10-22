# Shared About UI runtime verification

Baseline: `baf499bac357399d60c36bbdd47de667902ef938` (APK1026).
The installed app, its licenses/copyright notices, and APK1026 are unchanged.

## Diagnosis

The original command was:

```sh
./gradlew :shared:testAndroidHostTest --tests net.newpipe.app.screen.about.AboutPageTest
```

Both tests failed before `AboutPageContent` ran. Compose's Android
`getHasRobolectricFingerprint` reads `Build.FINGERPRINT` from the mock Android
SDK; the plain JVM runner has neither an Android device nor a Robolectric
runtime, so this field is null. Enabling Android resources does not supply that
runtime. This reproduces the earlier environment failure, not a screen crash.

The [official Compose Multiplatform testing guide](https://kotlinlang.org/docs/multiplatform/compose-test.html)
explicitly states that common Compose tests cannot run through Android local
test configurations and documents the JVM target. The unsupported Android-host
command is not silently excluded or claimed fixed here.

On the existing JVM target, the unchanged two tests initially failed for a
different reason: Skiko tried to create `/home/agent/.skiko` on a read-only home
filesystem. The installed Skiko 0.144.6 loader uses `skiko.data.path`, defaulting
to `user.home/.skiko`. Configuring only the `jvmTest` worker's cache under
`shared/build/skiko-test-cache` fixes that boundary without changing HOME,
permissions, authentication, Android fields, dependencies, or product code.

## Supported verification

With JDK21 and the project's declared JVM dependencies available:

```sh
./gradlew --max-workers=2 :shared:verifySharedUi
```

This verification task runs the complete `jvmTest` suite, including the common
About tests; it does not filter out failing tests or build an APK. Once normal
dependencies are cached, `--offline` can be added.

Observed evidence:

- Original Android-host reproduction: 2 tests, 2 platform NPEs, 18.290 seconds.
- Original JVM reproduction: 2 failures from the unwritable native cache,
  30.462 seconds, after resolving already-declared JVM dependencies.
- Cache-only fix: the original 2 tests pass unchanged, 10.134 seconds.
- Complete shared JVM suite: 14 tests across 9 suites; failures, errors and
  skipped tests all zero, 13.982 seconds.

The added About screen regression uses generated in-memory settings and the
existing Koin test helper, actual About/License composables, tab clicks, bundled
GPL text, and an in-memory rendered pixel buffer. It checks the backtube header,
visible nontransparent rendering, full-license access and return navigation.
No screenshot or user data is saved. Its first fixture run lacked Koin settings
initialization; that fixture error was corrected without a product change or
weakened assertion. Existing About assertions remain unchanged.

This is JVM/Skia UI evidence, not Android device, installation, audio, accessibility
or PC validation. The PC is offline; physical verification remains pending.
No new APK, deployment or main merge is part of this task.
