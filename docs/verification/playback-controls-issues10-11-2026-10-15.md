# Playback controls: issues #10 and #11

Issues: [#10](https://github.com/wnswlfhvkr-code/backtube/issues/10),
[#11](https://github.com/wnswlfhvkr-code/backtube/issues/11).
Product source: `b0dc428885a6f8b0c29a14bcb224f492512543ea`.
Base: `3aff69fbdd6687d26ead51a592f63289ac5b03f7`.

## Ownership and boundaries

The existing isolated source worktree was reused on
`fix/playback-controls-ux-20261015`. At resume, all ten worktrees were clean,
no task build wrappers were running, and the PR7 remote still matched the base.
One GPT-6.1 Sol High implementer owned source writes; a separate GPT-6.1 Sol High
reviewer independently reviewed the diff and verification evidence. The final
review approved source correctness and code quality with no unresolved findings.

The physical device belongs exclusively to Windows QA task
`01a13db8-4b0c-7697-adec-2c8334ec3005`. This source task performed zero device/adb
operations, installs, or Android test executions. No APK was generated. Existing
saved data and the issue8/issue9 source fixes were preserved. No Claude calls,
authentication changes, new permissions, paid settings, main merge, deployment,
or PR7 body edits were performed during this implementation.

## Issue #10: next-action description follows the icon

The shared mini-player image updater changed only the drawable. Both detail XML
variants initially paired a play icon with a pause description. The updater now
sets the localized `play`/`pause` description from the same playing-state boolean,
and both initial descriptions use `play`. Existing playback-update, click, and
service-stop callers share this updater.

JVM regressions invoke the real production updater and check the icon/description
pair across paused, playing, and paused transitions, plus both XML defaults.
Android rendering/localization boundaries are mocked. A compiled real-player
test also checks mini-player description transitions; it has not run on a device.
TalkBack announcements remain unverified.

## Issue #11: rotation preserves paused playback

The landscape reconnect path could enable autoplay while fragment/player queue
references were not yet synchronized, then call `player.play()` whenever rotation
was locked and playback was paused. Landscape checking now delegates only to the
UI; it does not change playback or autoplay state. The existing passive fullscreen
policy, including its paused/completed exclusion, remains unchanged.

The actual rotation-button listener handles explicit fullscreen entry. When a
horizontal video is inline and the activity is already landscape, it enters
fullscreen directly, without requesting portrait rotation. Otherwise the existing
orientation callback runs, and an inline entry is completed only if the callback
did not already enter fullscreen. Existing fullscreen exits cannot be converted
back into entry. Automatic size/startup callback callers retain their routing.

Independent review found the already-landscape inline edge in the first revision:
requesting portrait before entry would make reconnect exit fullscreen again.
A separate real-listener regression failed on that forbidden callback, then
passed after the direct-entry branch. Tablet/TV, vertical, portrait-entry,
fullscreen-exit, passive paused/completed, and no playback/seek mutation cases are
covered at the JVM boundary. Three added device tests compile but were not run.

This establishes and removes a source-level forced-play path. The single APK1020
physical observation has not been traced to this exact stack, so physical cause
and repeated device verification remain pending. Neither issue is closed here.

## RED/GREEN and fresh final verification

Before production edits, 12 executable focused tests had six genuine behavioral
failures and zero errors: missing dynamic description, mismatched XML description,
forced play, autoplay mutation, and paused/completed explicit fullscreen entry.
All 12 passed after the first fix. The independent-review edge added a separate
RED with eight fullscreen tests, one unwanted-callback failure and zero errors.
The final focused run passed all 13 tests. Earlier harness compilation, Android
mock setup, and formatting failures were excluded from behavioral RED evidence.

Final command, using existing supplied toolchains and an environment allowlist:

```text
./gradlew --offline --console=plain -DskipFormatKtlint -PappFaultEndpoint= \
  --max-workers=2 :app:testDebugUnitTest :app:compileDebugAndroidTestJavaWithJavac \
  :app:runCheckstyle :app:runKtlint :app:checkDependenciesOrder :app:lintDebug \
  :app:compileDebugSources
```

Exit 0 / BUILD SUCCESSFUL, 79.973 seconds. Generated XML reports **432 tests** in
65 suites, zero failures/errors/skips (baseline419 +13). Production source and
Android-test Java compilation passed. Checkstyle reported zero issues; Ktlint and
dependency ordering passed. Lint XML reported **0 errors /321 warnings**; its exit
code alone was not used as proof. `git diff --check` passed. No signed APK build
or runtime/device success is implied by these compile checks.

Structured evidence: `evidence/playback-controls-issues10-11-2026-10-15/summary.json`.
The later evidence commit changes documentation only; the product source above
identifies the reviewed and tested implementation.

## Remaining Windows QA

On a future authorized build, retain all data and existing system settings. Verify
the mini-player node description and TalkBack behavior, the original paused video
with rotation locked, portrait entry, already-landscape inline entry, recreation,
exit, and playing/paused transitions. Record the installed build identity. Do not
interpret this source result as APK1021 containing these changes.

Separately, [issue9 comment6188021631](https://github.com/wnswlfhvkr-code/backtube/issues/9#issuecomment-6188021631)
reports APK1021's first physical GREEN for original frames, related items and
download format choices; broader cross-flow QA and actual downloading were still
pending in that report. This does not close issue9 or establish issue10/11 device
verification.
