# Personal playlist entry and saved-only controls audit

Parent-approved scope (2026-10-13): expose user-created playlists and audit
saved-list-only Play all/order. Reuse working functions; fix demonstrated entry
and save-lifetime gaps. PR7 description edits are explicitly authorized. Preserve
500,000,000-byte quota, no automatic expiry, original-user-file ownership and
active offline queue snapshot versus next Play all. No migration/name policy,
Claude/auth/token/key checks, new signing key, paid API, permission expansion,
reset/uninstall, main merge, deployment or new release. Deadline stays
2026-10-14T02:00:00Z. Root owns tracked edits/Gradle; agents draft/review read-only.

Verified product snapshot: `b5bc608c0f6c0d85132d39caa604bdfab1bdf116` (after standalone entry checkpoint
`d9dc37945828b8b6b50a00c91dac653181150741`; audit base
`6e726c3390c095d94ab5ae424f763fb023f05b86`). Exact remote tree, approved file
bytes and all verified source hashes were checked. The following record-only
checkpoint changes no app source; its exact branch HEAD is in PR7 and the final
handoff receipt. Historical 338/358/373/376 results are not this final result.

## Existing connections

| User-visible operation | Source evidence | Result |
| --- | --- | --- |
| Playlist screen | MainActivity bookmarks navigation -> BookmarkFragment, merged local and remote entries | Existing |
| Create from track/queue | VideoDetailFragment/StreamDialogDefaultEntry/PlayQueueActivity -> PlaylistDialog -> PlaylistCreationDialog -> LocalPlaylistManager | Existing |
| Standalone creation | Persistent New Playlist button -> existing creation dialog -> named empty metadata | Connected in this change |
| Personal list selection | Local PlaylistMetadataEntry -> NavigationHelper.openLocalPlaylistFragment | Existing |
| Personal content order | LocalPlaylistFragment drag/clearView/onPause -> transactional updateJoin -> ordered DAO joins | Existing; accepted save now survives teardown |
| Bookmark display order | BookmarkFragment -> local and remote manager transactions | Existing; accepted save now survives teardown |
| Personal Play all | PlayButtonHelper -> getPlayQueue -> new SinglePlayQueue from ordered copied metadata | Existing |
| Saved-library entry | MainActivity -> OfflineLibraryActivity | Existing |
| Saved-only Play all | header -> playSaved -> OfflineLibrary.queueAll -> validated Store snapshot -> OfflinePlayQueue/player | Existing |
| Saved reorder | handle/accessibility Up/Down -> OfflineShelfOrder.Move -> atomic synchronized Store move | Existing |
| Saved safety | quota500000000, expires0, app-owned media/part paths, reader-aware removal; original URI retained | Preserved |

The final full suite includes OfflineStore/OfflineShelfOrder/OfflinePlayQueue/
OfflineFirst regressions for quota/expiry normalization, source-file retention,
missing files, atomic failure/rollback, canceled drag, restart order, active
snapshots and local-source scope. No duplicate saved-list subsystem was added.
Actual Player/UI execution remains0.

## Standalone creation

Previously the bookmark screen only gave a hint and the manager returned
Maybe.empty for empty input. Existing LEFT JOIN metadata/append-choice queries
already support empty rows, and maximum join index defaults to -1.

A localized New Playlist button (minimum48dp) remains above list/empty/error
panels. It directly opens PlaylistCreationDialog with an empty selection instead
of passing it to PlaylistAppendDialog's first-track path. Existing transaction
stores named empty metadata with default thumbnail-1 and emits an empty join-ID
list as successful creation. Seeded creation, thumbnail/order and join-row-ID
result contract remain unchanged. No schema/migration, auto-open or naming-policy
change. First append starts at index0.

Meaningful manager RED3/1 assertion failure/0errors -> GREEN3. Two controls cover
seeded thumbnail/ordered join IDs and first append. Room infrastructure is mocked,
but real manager transaction bodies execute. Compiled Android fixtures cover
standalone cancel/confirm and owned disk DB creation/reopen/append/reorder/reopen;
they are unexecuted and do not replace/reset the app database.

## Confirmed append and order save lifetime

Actual dialog handler + manager + onDestroyView with paused IO proved an accepted
append was canceled before its transaction. RED2/1 -> expandedRED4/3 (including
first-track thumbnail and unhandled failure) -> GREEN4. Confirmed append and
thumbnail writes now outlive the dialog view; list observation remains view-owned.
Success/failure feedback uses captured application context and explicit onError.

Actual content/bookmark save methods and destroy lifecycle likewise lost queued
writes: two live controls passed, teardown cases gave RED4/2 assertion failures,
expandedRED6/4. Keeping writes alive then exposed ended-view error-panel access
(RED6/2). Errors use the original live panel or log when that view is gone.

Fresh independent whole-plan review found one Important introduced issue: old
completion could clear a replacement view's newer dirty flag; old failure could
show on its error panel. Same-saver/replaced-adapter regressions produced RED8/2,
expandedRED10/4 (zero errors), then GREEN10 with captured adapter/saver ownership
guards. New dirty order stays pending and the next save writes it. Original-view
or no-view successful completion may clear only its matching saver; a replacement
view receives neither old completion nor old failure UI effects.

Final focused17/17 (3creation+4append+10order), zero failures/errors/skips. These
use real factories/handlers/save/destruction/Rx and real transaction lambdas with
explicit Android/Room boundaries; they do not prove SQLite durability or device
navigation. Finite writes are deliberately independent of view disposables,
marked only at the three write methods with scoped CheckResult rationale.
No process-death retry, serialized-write architecture or new error/naming policy
is claimed. Preexisting live-view multi-save races, creation-dialog onError and
mixed bookmark transaction partial-failure/retry behavior remain outside this
bounded connection change; they were distinguished by the reviewer.

## Final verification and review

Fresh full JVM390 across58 suites, zero failures/errors/skips. Checkstyle0,
Kotlin lint/dependency checks, Android test source compilation and unsigned
release build passed (6m9s). Debug/release lint each:0errors,
366warnings,0new issue/message/file signatures versus retained baseline XML.
An initial task-name setup failure ran no checks. First broad pass found four
new deliberate-subscription CheckResult warnings; scoped method annotations
then justified the final verification/build. Prior four baseline lint errors
are not current results. No app source changed during the final command;
complete source manifest and exact remote read-back agree.

Independent reviewer: Critical0; Important1 reproduced/fixed as above; Minor
stale audit text corrected here. No second review pass was claimed. Evidence in
`/workspace/scratch/backtube-overnight-20261013/personal-playlist/` includes RED/
GREEN XML, final-validation.log, final-verification.json, frozen source manifest
and secret-free progress ledger. No completed check was rerun without a new
source change, failure or unresolved warning.

## Delivery and actual QA limits

Android UI/drag/ExoPlayer/TalkBack/narrow-font measurements:0 actual runs. SDK
emulator/system images and KVM are absent; earlier ADB read-only-home restrictions
were not bypassed. Instrumentation source compiles only. Required screenshot
materialization and current APK transfer remain blocked by official Library
HTTP401 before transfer; no retry/bypass and no local pixels claimed here.

Historical delivered APK1018/Library receipt belongs to sourcef82d5e0 and contains
none of the overnight fixes. This environment produced only unsigned normal
release validation (org.schabi.newpipe,0.29.1/code1015), which is not the signed
parallel-test package update. No current-source compatible signed APK or new
Library file ID exists. No key import/read/copy/generation, existing-app/data
operation, new release, main merge or deployment occurred.

Unsigned validation receipt only:11,505,811bytes; SHA256
`99b9a8b38f73d126f42e92fefcfc910f69e83a6755f15e00ea84bd40bf79f6c2`.
`apksigner verify` exited1 (missing signature manifest). No signer comparison
or current signed Library file ID can be reported. This is not a user installer.
