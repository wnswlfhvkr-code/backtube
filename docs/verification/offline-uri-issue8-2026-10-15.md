# Issue #8: saved URI playback checkpoint

Issue: https://github.com/wnswlfhvkr-code/backtube/issues/8

This is source verification, not confirmation of the user's Samsung playback scenario.
The issue stays open until equivalent runtime evidence or user confirmation is available.
Baseline APK1019 source: `61d1103a16e4c811d58bf2211fe3330f0ae4208d`.

## Observed failure and source changes

The APK1019 mapping ID matches the user's obfuscated report. Internal
`backtube-offline:` identities reached URL acceptance through ExtractorHelper;
the report has no line numbers establishing the exact initiating gesture.
Controller expansion, restored details, history metadata, and sparse metadata
were independently identified as possible online-entry paths.

- ExtractorHelper resolves internal identities from owned store metadata before
  service selection, InfoCache, force-load handling, or provider extraction.
- Internal store lookup is canonical ID-only. An origin alias cannot substitute
  another saved file. Normal web-origin lookup remains supported.
- Saved detail navigation opens the saved controller or shelf. Switching retains
  the current queue and pause state; explicit selection delivers its requested queue.
- Non-Activity controller/shelf launches carry the required new-task flag.
- Delayed resolutions cannot replace newer same-context detail navigation, and
  callbacks do not start playback from stopped/destroyed Activities.
- Restored detail workers and local metadata callbacks avoid online detail tabs.
- Staying on a saved controller does not lock its expansion gate for a later
  online queue; online duplicate-launch suppression remains in place.

## Regression evidence

Initial navigation regression failed with MainActivity instead of PlayQueueActivity.
Five local metadata cases failed with provider parsing, wrong error categories,
caller-service rejection, or stale cache, then passed with owned-store resolution.
Restored-worker regression showed two ExtractorHelper calls, then no extraction.

Independent review prompted additional genuine failures before correction:
canonical ID/origin collisions; service-context activity flags; reversed completion
order; stopped-but-surviving Activity callbacks; and the controller expansion gate.
Test harness compile, Android Log plumbing, and formatting failures were corrected
separately and are not counted as bug reproduction evidence.

Fresh final-source verification: all **412 JVM tests passed**, no errors/skips.
Checkstyle, Kotlin lint, dependency ordering, and `git diff --check` passed.
Instrumentation Java compiled. Android debug lint reports **0 errors, 321 warnings**;
the new navigation ignored-subscription warning was removed. Existing warning counts
are recorded without claiming a warning-free build.
Command (existing toolchain, offline dependencies):

```text
./gradlew --offline --console=plain -DskipFormatKtlint -PappFaultEndpoint= \
  --max-workers=2 :app:testDebugUnitTest :app:runCheckstyle \
  :app:runKtlint :app:checkDependenciesOrder \
  :app:compileDebugAndroidTestJavaWithJavac :app:lintDebug
```

## Runtime and delivery work still pending

Actual Android executions for this fix: **0**. An isolated software AVD was attempted,
but adb host startup failed with read-only-filesystem error (exit -6). No permission
bypass, new auth/key, existing AVD reset, or user-data deletion was attempted.
The earlier Windows Android counts and APK1019 checks do not establish this fix works.

Compiled device coverage now includes the normal saved queue-all service intent, actual
Next control, repeated controller expand action with pause retention, actual service
restore action and recovery position, and unreadable saved bytes without deletion.
The existing shelf-row test also exercises controller expansion. The separate process
restore phase no longer overwrites the engine with a reflected test initialization.
These tests remain **unexecuted**, and no runtime RED/GREEN is claimed for them.

The network boundary rejects/counts HTTP requests and restores its previous downloader,
localization, and country on failure. Zero requests alone cannot exclude extractor entry
that fails before HTTP; the real JVM routing regressions and controller assertions provide
complementary evidence. Test readiness waits for bound metadata/title and the accessibility
action. Longer generated media and first-item assertions prevent natural advance from
masquerading as a successful Next action. History/resume preference presence and values
are restored, and failure cleanup covers playback screens across lifecycle stages.

Independent core and final instrumentation reviews found no remaining source blocker.
The compatible signed candidate APK was built and saved to Library as a single file.
Device behavior remains unverified.
Original worktrees, signing input, and saved media are preserved. No Claude, OAuth,
paid API, main merge, deployment, or PR7 body edit is part of this work.

Saved entries still have unknown duration where recorded that way. This change does
not claim new per-track history completion/resume semantics. Last-session recovery
position is a separate existing behavior requiring runtime verification.

## Delivered candidate APK

- File: `backtube-offline-uri-fix-81f219a-1020-20261015.apk`
- Product source: `81f219a0ce99e6e353cb3d29444c7d5f195a637d`
- Version: `0.29.1-offline-uri-fix-20261015`, code **1020** (previous 1019)
- Package: `org.schabi.newpipe.continuous.testofflinelibrary20261011`
- Size: **11,627,838 bytes**
- SHA-256: `9cd8a5d92c396ce1590824423ec2897bb03e6d53007177bb042a676ebefd933d`
- Public APK certificate SHA-256:
  `4fc26294d54b6c9adb276b0b3aab38592077fe28c9f3c64d93da5d9ff6bb5bbc`
- Mapping ID: `a4d0fb736ca4c0254f9807aa49d56215c24aa841f9dd77849c7a6f976309d2f0`

Build exit 0; the existing signer guard passed and the signing input identity remained
unchanged. APK signatures match APK1019; package, minimum SDK, and permissions remain
compatible; version code increases; 16KiB zip alignment passes. The existing APK1019
hash matches its retained record. No key was generated, inspected, or copied.
The new saved-identity guard is present in the APK dex and the new saved resolver and
navigation paths are present in the build mapping. The build source and original
worktrees remain clean and preserved.

These are APK update prerequisites, not an installation, update, or playback result.
Actual installation/update and Android runtime tests remain **unexecuted**. Issue #8
stays open for user confirmation or equivalent reproduction. Machine-readable evidence
is in `evidence/offline-uri-issue8-2026-10-15/summary.json`.
