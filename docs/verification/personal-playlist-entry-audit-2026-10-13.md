# Personal playlist entry and saved-only controls audit

Parent-approved scope (2026-10-13): the user reports that only playlist bookmarks
are visible and wants user-created playlists plus saved-list-only Play all/order.
Reuse working functionality; fix only proven entry/connection gaps. Preserve
500,000,000-byte saved quota, no automatic expiry, original-user-file ownership,
and the active queue versus next Play all snapshot. No migration/name policy,
new release/signing credentials, reset, uninstall, Claude or auth bypass.

Audit baseline: clean `6e726c3390c095d94ab5ae424f763fb023f05b86` (record-only
update after product `acd03b5`). PR7 description was explicitly updated/read back
with source373/UI0/current-APK blockers before this follow-up implementation.

## Existing connections

| User-visible operation | Source evidence | Result |
| --- | --- | --- |
| Playlist screen | MainActivity bookmarks navigation -> BookmarkFragment; merged local and remote items | Already present |
| Create from a track/queue | VideoDetailFragment/StreamDialogDefaultEntry/PlayQueueActivity -> PlaylistDialog -> PlaylistCreationDialog -> LocalPlaylistManager.createPlaylist | Already present |
| Local list selection | BookmarkFragment selected(PlaylistMetadataEntry) -> NavigationHelper.openLocalPlaylistFragment | Already present |
| Playlist save/order | LocalPlaylistManager transaction; LocalPlaylistFragment drag/clearView/onPause -> updateJoin; ordered DAO joins | Already present |
| Personal playlist Play all | PlayButtonHelper -> LocalPlaylistFragment.getPlayQueue; new SinglePlayQueue from copied ordered metadata | Already present |
| Saved-library entry | MainActivity saved-library item -> OfflineLibraryActivity | Already present |
| Saved-only Play all | header playAll -> playSaved(null) -> OfflineLibrary.queueAll -> OfflinePlayQueue(validated Store snapshot) -> background player/queue | Already present |
| Saved reorder | handle and accessible Up/Down -> OfflineShelfOrder.Move -> synchronized atomic OfflineStore.move; reopen order | Already present |
| Saved safety | DEFAULT_LIMIT=500_000_000; expires normalized0; app-owned media/part paths; reader-aware removal; no original URI deletion | Preserved |

Existing OfflineStore/OfflineShelfOrder/OfflinePlayQueue/OfflineFirst regressions
cover quota, expiry normalization, original-file retention, missing files,
atomic failure/rollback, canceled drag, restart order, active snapshots and
local-source selection. The fresh full376 suite includes those existing tests;
no redundant saved-list feature was added. Actual Player/UI runs remain0.

## Proven gap and minimal connection

BookmarkFragment/fragment_bookmarks previously exposed only an explanatory hint,
with no standalone New Playlist action. The manager returned Maybe.empty for an
empty stream list. Existing schema already supports empty rows: metadata and
append-choice queries LEFT JOIN playlists, and maximum join index defaults to -1.
No schema/migration decision is required.

A permanent localized 48dp-minimum New Playlist button below the existing hint
now directly opens PlaylistCreationDialog with an empty non-null selection.
The list, error and empty panels follow this button. It deliberately reuses the
creation dialog rather than routing an empty selection into PlaylistAppendDialog,
whose existing track-choice path expects its first track. The existing transaction
now stores a named empty playlist with DEFAULT_THUMBNAIL_ID=-1, emits an empty
join-ID result as success, and preserves seeded creation and join-ID return values.
No auto-open/return-ID rewrite, duplicate-name or blank-name policy was added.

Meaningful JVM RED: 3 tests, 1 assertion failure, zero errors (empty creation
completed without value/insert). GREEN3/3; controls preserve seeded thumbnail/
join order and first append index0. These execute the real manager/transaction
body with Room DAO infrastructure replaced explicitly; they are not SQLite or
Android runs. Existing Android empty-list expectation now verifies persisted
visible metadata. New compiled fixtures cover standalone cancel/confirm and an
owned disk DB create/reopen/append/reorder/reopen without replacing the app DB.
Both remain unexecuted.

Verification: fresh JVM376/56 suites, zero failures/errors/skips; Checkstyle0;
Kotlin lint; debug/release lint0errors/366warnings/0new warning signatures;
Android source compilation and unsigned release build succeeded (5m30s).
A test-only ignored-result warning was corrected by asserting returned append
count; final Android compilation/Kotlin/debuglint passed again in43s. Product
source hashes stayed unchanged. Whole-plan independent review follows the
separate append-lifecycle investigation; it is not claimed complete yet.

Evidence: `/workspace/scratch/backtube-overnight-20261013/personal-playlist/`
contains creation-red.xml, creation-green.xml, entry-full-validation.log,
entry-fixture-lint-final.log, entry-final-verification.json and progress.md.

## Separate source candidate

PlaylistAppendDialog adds an accepted IO write to view-owned disposables,
requests dismissal, then disposes that collection in onDestroyView. A controlled
scheduler regression is drafted privately; semantic cancellation has not yet
been executed/proven. Do not claim device reproduction or change policy based
on source suspicion alone.

## Delivery and real QA limitations

Actual Android UI/drag/ExoPlayer/TalkBack runs0. SDK emulator/system images and
KVM are absent. Official Library materialization remains HTTP401 without retry
or bypass. Historical delivered APK1018 belongs to f82d5e0 and includes none of
this overnight work. Only unsigned build validation was performed; current-source
compatible signing and new Library delivery remain incomplete. No keys were
imported/read/copied/generated; no existing app/data deletion, reset, release,
main merge or deployment occurred. The coordinator deadline remains02:00UTC.
