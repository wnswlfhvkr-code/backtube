# Issue #8: saved URI playback checkpoint

Issue: https://github.com/wnswlfhvkr-code/backtube/issues/8

This is a source checkpoint, not confirmation of the user's Samsung playback scenario.
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

Fresh core-source verification: all **412 JVM tests passed**, no errors/skips.
Checkstyle, Kotlin lint, dependency ordering, and `git diff --check` passed.
Command (existing toolchain, offline dependencies):

```text
./gradlew --offline --console=plain -DskipFormatKtlint -PappFaultEndpoint= \
  --max-workers=2 :app:testDebugUnitTest :app:runCheckstyle \
  :app:runKtlint :app:checkDependenciesOrder
```

## Runtime and delivery work still pending

Actual Android executions for this fix: **0**. An isolated software AVD was attempted,
but adb host startup failed with read-only-filesystem error (exit -6). No permission
bypass, new auth/key, existing AVD reset, or user-data deletion was attempted.
The earlier Windows Android counts and APK1019 checks do not establish this fix works.

Normal service/controller/restore device regression coverage, instrumentation compile,
final independent review, and the compatible signed APK are still pending at this checkpoint.
Original worktrees, signing input, and saved media are preserved. No Claude, OAuth,
paid API, main merge, deployment, or PR7 body edit is part of this work.

Saved entries still have unknown duration where recorded that way. This change does
not claim new per-track history completion/resume semantics. Last-session recovery
position is a separate existing behavior requiring runtime verification.
