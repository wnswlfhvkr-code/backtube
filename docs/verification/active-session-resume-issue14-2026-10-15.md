# Issue 14: new selections start at zero; only the active session resumes

Issue: https://github.com/wnswlfhvkr-code/backtube/issues/14.
Reviewed product source: `52e23e539a6edd20ca157d3991e829dc8f2d16d4`. Base: `01840aff2124eff0832146b567299bf8e2ac84e9`.

## Cause and resulting behavior

Player.handleIntent used per-song database history to restore progress on ordinary
new selections. Its equal-queue fast paths also retained the current position.
Separately, per-item recovery cached on pause could reappear on automatic return,
and session saving could prefer stale recovery over the matching live position.
SerializedCache clones queues, so object identity cannot distinguish a new choice
from a player/view rebind.

New choices explicitly carry fresh-selection intent, including selecting the same
active item again. They start at zero and clear stale queue recovery. Existing
mode/rebind callers preserve the current position and paused state. History reads
used to choose playback start were removed; history writes and detail progress
reads remain. Stored preference keys remain, with accurate English/Korean labels
for the remaining detail progress display function. No record, preference, saved
file or app data is deleted, reset or migrated.

The existing last-session JSON store and paused PlayerService restoration remain
in use. Matching current source identity and queue index prefer the live content
position when saving; pending recovery for a different source/index is retained.
The queue, index, selected item and stop position survive the real JSON boundary.
No new unsolicited autoplay or automatic next-item historical resume is added.

Explicit timestamp requests retain their requested zero/positive position. The
request marker is consumed after use; TimestampChange no longer mutates cached
StreamInfo, so a later ordinary choice cannot inherit a consumed time link.
Fresh selection also invalidates prior retry timers/generation/budget/flags, so
old asynchronous recovery cannot overwrite the new zero or explicit timestamp.
Nonfresh retry, pause/resume and view transition behavior remains covered.

## Regression evidence and independent review

Fresh baseline: 444 JVM tests. Genuine behavioral RED phases, reported before the
corresponding production corrections, covered these independent boundaries:

- Original policy: 8 tests / 6 assertion failures, including new online/saved and
  same-item selections at 45,000 instead of zero, automatic return, and actual
  session JSON restoring stale 12,000 instead of live 23,000 ms.
- Consumed timestamp and cached-info isolation: 10 tests / 2 assertion failures,
  later ordinary selection at 17,000 instead of zero.
- Equal queue reuse: 14 tests / 2 assertion failures, disposed equal queue at
  45,000 and another active playlist item at 31,000 instead of zero. A nonfresh
  disposed rebind retained its intended 45,000 ms.
- Independent-review findings R1/R2: 21 tests / 6 assertion failures. Old pending
  retry restored 45,000 over fresh zero/17,000; previous retry budget blocked the
  new attempt; both 24-item equal queues generated 25 recovery events and 25
  actual full-session JSON/preferences writes, versus a bound of two.

These are overlapping phase totals, not an additive count. Earlier harness,
Android clock/mock and formatting failures are excluded from behavioral RED.

One GPT-6.1 Sol High source writer worked at a time; an independent GPT-6.1 Sol High
reviewer assessed all changed selection/mode/restore paths, specification and code
quality. R1 (stale retry) and R2 (quadratic session serialization) were corrected
in one focused wave with meaningful RED/GREEN and scoped independent re-review.
Batch recovery clearing emits a bounded notification instead of one full-session
save per queue item. Both review verdicts were accepted before source commit.

Generated old-source Java serialization fixtures verify the measured unminified
PlayQueueItem UID 6054959748728707165 and PlayQueue UID 4099004261666648821, absent
new fields, and legacy queue/index/recovery values. This does not establish
compatibility with previously shipped R8-mapped Java cache objects. The durable
last-session JSON schema is unchanged; no cache or user-data wiping is used.

Final required checks: **472 JVM tests / 72 suites**,
zero failures/errors/skips; Android-test Java and debug source compilation,
Checkstyle, Ktlint, dependency ordering and lint completed successfully.
Gradle exit 0, 95.393 seconds. Checkstyle has zero issues;
lint has zero errors and 323 warnings. The two additional
warnings above the 321 baseline are retained unused old preference-label strings.
Whitespace checks passed. The source retains the #8–13 fixes by ancestry.

New Android regressions use generated silent media and fixture-scoped identities
and history. They cover saved-history selection, same-active selection, retained
history and actual paused PlayerService restore at index 1 / 23,000 ms. These
and existing UI/process restart tests compile; none was executed on a device.

## APK 1024 candidate

- File: `backtube-active-session-fix-52e23e5-1024-20261015.apk`
- Product source: `52e23e539a6edd20ca157d3991e829dc8f2d16d4`
- Version: `0.29.1-active-session-fix-20261015`, code **1024**
- Package: `org.schabi.newpipe.continuous.testofflinelibrary20261011`
- Size: **11621388 bytes**
- APK SHA-256: `4a809a2573920f32d66e3537907f9b6364a7751ad253973dbad997002561cb23`
- Public certificate SHA-256: `4fc26294d54b6c9adb276b0b3aab38592077fe28c9f3c64d93da5d9ff6bb5bbc`
- R8 mapping ID: `7a0a4720b09d1d37c4238b42f9cb8a0ee704748bd9d21251e8fa7c6178e2d99d`

Built from a clean separate worktree pinned to the accepted product commit;
build exit 0 in 479.317 seconds.
The existing signing input was guarded and used normally; its identity remained
unchanged. No key was generated, inspected for contents, copied or exported.
Compared with retained APK1023: same valid signer/package/permissions, increased
version code, minimum SDK not raised, 16 KiB alignment passes, and APK1023 still
matches its recorded hash. All pre-existing source/APK worktrees were preserved.
The APK was saved to Library. Private delivery identifiers remain in local
delivery evidence rather than this repository. This later documentation commit
contains no further product changes.

## Windows QA acceptance still required

No device/adb operation, installation or physical playback was performed here.
On this exact APK, verify old online/saved/list/search selections and same-active
reselection start zero, including after a transient error and a time link; ordinary
pause/resume and popup/main/fullscreen transitions retain the current position;
automatic next/return starts zero; and restart restores only the last active
queue/index/stop position, paused. Verify existing history remains visible and
saved files/earlier #8–13 behavior remain intact. Confirm normal retries continue
and large equal-playlist reselection remains responsive.

No source test, compilation, signature or package compatibility check establishes
physical success. Issue14 stays open until exact-APK device acceptance. No Claude
execution, new authentication, permission or paid setting, main merge, deployment
or changes to prior #12/#13 comments were performed by this task.
