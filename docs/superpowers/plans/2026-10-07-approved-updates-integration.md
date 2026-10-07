# Approved Backtube updates integration

## Scope and immutable starting points

Work in `integration/approved-updates`, separate from PR4. Preserve PR4 at
`19b4522173acf214cfece77a0255d600c55aab58` and its existing APK/evidence.
Remote main is `84b4e0e85f02f29a8523486345db5fc5df9e395d`; PR2 is
`dc03e7723cd6f128375fcdd05ae0b5a95ec60e72`; PR3 is
`bff8f4a441df966566955f06da5c12e96f5378fb`. None is merged by this work.

The user reports interruption/quiet audio on the previously installed version,
and explicitly has not checked the 19b4522 test APK. Do not classify that report
as a failure of the test APK or claim a physical-device fix.

## Execution order

1. Before integrating PR2/3, test real Android focus exchange between Backtube's
   PlayerService and a separate test APK Activity/MediaPlayer, using generated
   local WAV only. Assert distinct UIDs and observe actual OS callbacks.
   Cover transient loss, duck, permanent loss, manual pause, timer expiry and
   explicit Play. Preserve timer/mute gain and system volume. No focus polling
   or unsolicited reacquisition when the OS does not grant focus.
2. Build and run JVM checks plus Android API23/35 CI on that pre-integration head.
   Record actual evidence, including unexpected failures. API37 is documentation
   review only unless a real runtime is separately exercised.
3. Integrate PR2 shared SleepTimerDialog and detail action, with PR3's final
   timer tests. Remove the redundant detail Background action requested by the
   user, keeping automatic background continuation and the player engine intact.
   Preserve current-track artwork/swipe, offline audio, focus and recovery work.
4. Keep PR4's newer offline-only CI and diagnostics. Carry forward any genuinely
   missing PR3 fixes after file-level comparison, rather than overwriting newer
   tests/workflows. Add focused timer/background UI regression coverage.
5. Run JVM, Checkstyle, APK compilation and full API23/35 CI on the integrated
   head. Obtain independent source review. Claude advice is recorded only if
   actually supplied by the parent (no Claude tool/CLI exists in this executor).
6. Deliver the exact CI test APK with source/build SHA distinction, package,
   version, signature digest, file SHA256 and Library identity. Existing CI debug
   signing only; no personal keys, main merge or release publishing.

## Audit at the starting head

PR4 already contains main's background/data saver, quality refresh, selected-song
playback, natural queue progression and recommendation alignment changes.
PR2 detail timer/shared dialog is missing. PR2 itself retained Background, so the
requested redundant-button cleanup is not claimed to be previously implemented.

PR3's playlist boundary fix, nullable count fix and their JVM tests are byte-for-
byte identical to PR4. Database migration/history/playlist fixtures, scheduler
cleanup and detached-preference regression test also match exactly. The detached
preference guard is present in PR4's otherwise different detail fragment.
PersonalPlaybackTest has newer offline/current-track coverage; its final timer
tests must be integrated selectively. Live SubscriptionManagerTest remains
excluded from the network-disabled regression suite. Old PR3 workflow/scripts
must not replace PR4's later aggregate status and diagnostic handling.

## Verification commands

Use `/workspace/.backtube-environment/env.sh` for local JDK/SDK configuration.
Run `./gradlew testDebugUnitTest checkstyle assembleDebug assembleDebugAndroidTest`
with repository-required formatting flags if necessary. CI's
`scripts/run-android-verification.sh` owns real API23/35 runtime coverage; the
cloud executor has no KVM and must not claim local emulator success.

Record each completed step and evidence in
`docs/verification/approved-updates-integration-2026-10-07.md`.
