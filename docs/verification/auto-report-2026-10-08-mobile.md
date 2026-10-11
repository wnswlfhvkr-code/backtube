# Mobile verification attempt: automatic fault delivery (2026-10-08)

## Result

| API level | Image | Executed | Passed | Failed | Status |
|---|---|---|---|---|---|
| 23 | default x86 | 0 | 0 | 0 | BLOCKED |
| 35 | default x86_64 | 0 | 0 | 0 | BLOCKED |

- No mobile test passed and none failed. The seven `AppFaultAndroidSchedulingTest` tests were
  not executed. They neither passed nor failed.
- The block is an infrastructure failure: the ADB bridge could not be created. It is **not**
  a failed application test.

## Provenance

- The coordinator made these observations on 2026-10-08, around 14:56-15:09 UTC, in the
  selected `/workspace` cloud environment. This document only records them. The document
  author ran no commands.
- The shell and files remained accessible throughout.
- Preserved commits:
  - original/main `84b4e0e85f02f29a8523486345db5fc5df9e395d`;
  - PR6 `be4b30a4850a70e13a0493a399318091205b5e65`;
  - PR7 checkpoint `71e1be62c0d46051f1b20957e0611582b9951fb8`.
- PR7: https://github.com/wnswlfhvkr-code/backtube/pull/7. It is an OPEN draft with base
  `feat/playback-feedback` and branch `feat/claude-auto-report`.
- Identifiers of subsequent documentation-only commits are recorded in Git history and in PR7.
  The listed `71e1be62` commit is a preserved checkpoint, not necessarily the branch head.

## Environment setup observed

- At first, no emulator or system images were installed, and `/dev/kvm` was absent.
- Official SDK command-line tools 19.0: the package list first failed to fetch sources without
  an existing public proxy. It then succeeded using pre-existing, non-credential proxy and
  trust settings.
- `sdkmanager` runtime install exited 0. No new license was accepted. Installed packages:
  - emulator 37.2.12.0 (build 16428233);
  - official default API 23 x86 system image;
  - official default API 35 x86_64 system image.
- `avdmanager create avd` (Nexus 5 device) exited 0 for both AVDs:
  - `backtube_cloud_api23_20261008`;
  - `backtube_cloud_api35_20261008`.

## Blockers

1. **No hardware acceleration.** `emulator -accel-check` exited 3 with
   `KVM requires a CPU that supports vmx or svm`.
2. **ADB could not start.** `adb --version` and `adb devices` each died with SIGABRT (-6)
   *before* enumerating any device. The reported cause was
   `Cannot mkdir '/home/agent/.android': Read-only file system`.
   - No device count was obtained. This is **not** a successful enumeration of zero devices.
   - Probes of the supported configuration variables `ANDROID_USER_HOME` and, separately,
     the legacy `ANDROID_SDK_HOME` did not fix it.
3. **Gradle device task failed in infrastructure.** The standard project command

   ```
   ./gradlew :app:connectedDebugAndroidTest -DskipFormatKtlint --console=plain \
     --no-configuration-cache \
     -Pandroid.testInstrumentationRunnerArguments.notClass=org.schabi.newpipe.local.subscription.SubscriptionManagerTest
   ```

   exited 1. A repeat with `--stacktrace`, used only to locate the failure, reported
   `Could not create ADB Bridge. ADB location: /workspace/.backtube-environment/android/platform-tools/adb`.
   No `TEST-*.xml` device execution report was produced.

## Software-emulation launch probes

- Both probes used `-no-window -no-snapshot -no-audio -no-boot-anim -gpu software -accel off
  -memory 2048 -cores 2`.
- They set the documented `ANDROID_I_WANT_MY_TCG=yes` and standard writable `ANDROID_*`
  configuration directories.
- Neither probe changed SELinux or security flags, or `HOME`.
- Each startup probe was bounded to 20 seconds and then explicitly terminated:
  - API 35 exited 0 after the stop;
  - API 23 exited -10 after the stop.
- These termination statuses are **not** test outcomes and do **not** prove boot completion.
- No active emulator was left running. One non-running zombie process may still await
  platform reaping.
- Raw emulator logs are intentionally not recorded.

## Constraints kept

- `HOME` was never overridden.
- Filesystem mount/access policy, security settings and permissions were not changed.
- There was no privileged restart and no bypass through another environment.
- No new external account, GitHub App, provider credential, key, login, deployment, issue or
  paid setup was created.
- No APK was installed and no instrumentation was launched.
- The standard scripts were read but not executed, because of the bridge failure:
  - `scripts/run-android-verification.sh`;
  - `scripts/verify-offline-android.sh`;
  - `scripts/run-local-android-regressions.sh`.
- CI was not used:
  - `.github/workflows/ci.yml` has `workflow_dispatch` and an API 23/35 matrix, and the
    repository is public.
  - The PR7 base `feat/playback-feedback` gets no automatic checks.
  - The workflow's `Enable KVM` step changes udev/KVM permissions. It was therefore **not**
    dispatched, under the current explicit no-permission-change constraint.
- Any different runner or permission setup needs explicit authorization. It must not silently
  bypass the current block.

## Pending mobile coverage

None of the following has been verified on a device:

- automatic fault delivery:
  - a single one-time WorkManager job per fault event;
  - the `NetworkType.CONNECTED` constraint;
  - offline retry;
  - survival across process restart;
  - deduplication;
  - no job when the outbox is empty;
- regression coverage: back navigation, listening, playback and the offline library.

The existing seven `AppFaultAndroidSchedulingTest` tests compiled but were not executed. Their
scope is limited to controlled scheduling through `WorkManagerTestInitHelper`, with constraints
never satisfied. They do not cover the end-to-end worker HTTP path or the event receiver.

## Fresh non-mobile checks on current product bytes

These are **not** mobile results. They add no tests beyond the prior full JVM count of 311.

- Pure autoreport tests: 50/50, exit 0.
- Scheduler API-boundary check, exit 0, two scenarios:
  - a blank endpoint cancels an old job;
  - a stale empty-queue cancel preserves a newer event job.
- Relay: 47/47, 0 failed, 0 skipped, exit 0. This includes the two real workerd/SQLite cases.
  Outbound requests: 0.

The earlier full Gradle JVM result (311/311 in 48 suites; 0 failures, 0 errors, 0 skipped)
remains historical validated evidence. So do the APK builds, Checkstyle, Ktlint and the Wrangler
dry run. No product source changed.

## Next standard path

1. Provide a writable default user `.android` directory and an accessible, KVM-ready Linux
   cloud runner. Windows is not intrinsically required.
2. Run the API 23/35 matrix with the existing scripts.
3. Add only local or mock-only reporting instrumentation. Do not use a real endpoint.

## Official references

- Emulator command line, including `-accel off` (unsupported, slow, and requiring the specified
  environment variable): https://developer.android.com/studio/run/emulator-commandline
- Android environment variables (`ANDROID_USER_HOME`, legacy `ANDROID_SDK_HOME`):
  https://developer.android.com/tools/variables
- Android Debug Bridge: https://developer.android.com/tools/adb
