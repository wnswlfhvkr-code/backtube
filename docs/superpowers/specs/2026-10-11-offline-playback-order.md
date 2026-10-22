# Saved library playback and order

Extend the existing private saved library with a visible Play all action and
playable count, readable title/status/size groups, separate playback and management
controls, and accessible move-up/move-down actions. Manual order survives process
restart. New saves append to the shelf. Play all uses the validated READY files in
the displayed order and creates a finite snapshot: later saves and shelf moves
affect the next Play all operation, without rearranging an active queue.

The queue uses only stable backtube-offline identities. Missing, deleted,
truncated or unreadable items cannot fall through to remote extraction or
recommendations. Skip unavailable items within the saved snapshot, stop when none
remain, and retain normal next/previous/repeat behavior for remaining saves.
An already opened deleted file may finish its current playback; future playback
must not reopen it. Retain the existing reader protection and 500,000,000-byte
quota, explicit-deletion-only retention and source-file ownership rules.

Make existing personal playlist creation discoverable from track/queue actions
and the playlists screen, reusing existing dialogs and database behavior.
Do not introduce an empty-playlist engine or new online metadata lookup.

Use the existing feature worktree and draft PR. Claude remains paused; implement
locally with Codex. No new credentials, paid APIs, external providers, uploads of
user media, main merge, deployment or new external publication. Preserve sanitized
checkpoints in the existing draft branch. Distinguish compiled/unit-tested UI
from actual Android screen validation; do not claim runtime QA when blocked.
