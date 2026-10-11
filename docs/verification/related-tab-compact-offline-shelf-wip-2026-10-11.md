# WIP checkpoint: Related default tab and compact saved library

This is an incomplete implementation checkpoint for handoff, not a completed
feature or a release candidate. Preserve these changes while resuming the approved
Backtube work in a fresh context.

## Source and scope

- Branch: `feat/claude-auto-report`, existing Draft PR7.
- Base before this checkpoint: `885dda9953c17d390f37f58bba01f0141942d270`.
- Existing worktree: `/workspace/backtube-auto-report-20261008`.
- Local preservation record:
  `/workspace/scratch/backtube-player-tabs-20261011/checkpoint-preserve-pao8_1we/CHECKPOINT.md`.
  That directory also contains all ten changed source files, the tracked patch,
  a manifest and copies of focused test evidence. Copies matched the live files
  before this checkpoint commit.

Only the approved ten product/test/resource files and this resume record are part
of the checkpoint. No screenshots, credentials, caches, generated APKs or user
data belong in this commit. Main, other worktrees and PR body are unchanged.

## Related items default tab

`VideoDetailFragment.readSelectedTab` defaults a missing selection preference to
Related items. Stored Comments, Related items and Description preferences retain
their values; existing tab restoration remains intact.

There is no provenance marker distinguishing a deliberate stored Comments choice
from the former default automatically saved by `onPause`. Do not reset or migrate
those preferences. An existing user can enable **Settings > Content > Show 'Next'
and 'Similar' videos**, select **Related items** in playback detail and background
the app once to persist that selection. The Related tab is omitted when that
content option is disabled or the layout uses a separate related-items pane.

## Compact saved-library groundwork: incomplete

The approved UI uses compact thumbnail/title/secondary-status rows, row tap for
playback, a separate right drag handle, delete in the overflow menu, collapsed
capacity/management settings and Play all in the header. Reuse the existing play
queue row pattern with a local placeholder, without fetching online thumbnails.
An active playback queue remains a snapshot; shelf changes affect the next Play
all action.

Written but not yet fully integrated:

- `OfflineShelfOrder`: stable-ID drag preview, drop-time atomic persistence,
  cancellation/failed-write rollback and accessible move alternatives. It retains
  concurrently saved entries and does not alter active playback queues.
- `OfflineShelfAdapter`: compact row binding, local-URL current-track highlight,
  separate play/menu/handle targets, binding guards and accessibility Up/Down
  actions. It was written after the last focused model test and has not been
  compiled.
- `offline_shelf_item.xml`, English/Korean strings and menu IDs.
- `OfflineShelfOrderTest` and the updated instrumentation test source.

**Resume at `OfflineLibraryActivity.java`.** It still uses `LinearLayout` rows and
the old cards. No RecyclerView, adapter or drag-handler integration has been made.

Next implementation work should connect the compact header and collapsed panel,
RecyclerView/adapter listeners, handle-only drag with swipe deletion disabled,
drop-time persistence and failure rollback, stale-refresh protection, accessible
menu actions and passive current-player metadata updates. Preserve existing
download, deletion, quota and local-only playback semantics. Cancel uncommitted
drag when leaving the screen; keep active playback queue snapshots unchanged.

## Verification limits

Previous focused test records were inspected again for this handoff:

- Selected tab preference: RED 4 tests with 1 expected failure; GREEN 4 tests,
  0 failures and 0 errors.
- Shelf order model: initial RED was an absent-class/API compilation failure;
  GREEN 9 tests, 0 failures, 0 errors and 0 skipped tests, Gradle exit 0.

These records establish the focused tab/model checks only. They do not verify the
adapter or complete UI. No tests or builds were rerun for this checkpoint.

Still pending: Activity integration, compilation of the adapter/resources and
instrumentation tests, appropriate full JVM checks and Checkstyle, independent
review, physical UI/runtime checks and a new combined APK. There is no new APK
for these changes. Existing device/emulator infrastructure remains blocked;
do not claim instrumentation execution or work around its permission boundaries.

Validate drag persistence/restart order and rollback, current-track identity,
row/handle/menu misclick prevention, accessible movement, empty/missing/deleted
files, local-only queue behavior and unchanged active queue snapshots.

## Handoff boundaries

Claude remains paused. Do not execute Claude CLI, authentication, subscription,
OAuth, login, token issuance or model requests. Do not inspect credentials or
environment values. Use the approved Codex model for resumed implementation.

Preserve user data and all existing changes. Do not reset, overwrite, uninstall,
clear app data, introduce paid providers/permissions, merge main or deploy.
PR body editing remains excluded. After the parent resumes implementation, retain
the existing parallel-test package and existing signer for the combined APK and
deliver through official Library tools. This preservation turn ends after push
and remote-HEAD confirmation; do not start further implementation here.
