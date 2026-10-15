# Saved library playback and order implementation plan

> **For agentic workers:** Use superpowers:executing-plans inline. Track steps below and obtain one independent final branch review.

**Goal:** Play saved files in a persistent manual shelf order and make existing personal playlist creation easier to find.

**Architecture:** OfflineStore owns a versioned order file committed with sync/rename.
A finite OfflinePlayQueue uses only local identities, rejects online additions,
and prunes unavailable saved entries at playback boundaries. Existing activity,
player, session persistence and playlist dialogs remain the integration points.

**Tech Stack:** Java, Android Views, existing ExoPlayer/RxJava/JUnit/Mockito; no new dependencies.

**Spec:** `docs/superpowers/specs/2026-10-11-offline-playback-order.md`

## Global constraints

- Preserve 500,000,000-byte quota, indefinite retention and reader-protected deletion.
- Shelf moves/new saves affect the next whole-play snapshot; no remote fallback.
- Claude paused; no credentials, paid/provider calls, main merge or deployment.
- Preserve existing draft PR and checkpoints; report actual UI blockers separately.

## Review focus

- Interrupted order writes must retain the prior order and all complete media.
- Deletion of preloaded/current files must not cause repeat loops or skip valid successors.
- Late recommendation callbacks and enqueue intents cannot blend online items into saved playback.
- Restored queues retain local scope, order, selection and recovery position.
- Narrow screens/large fonts retain labeled controls and 48dp minimum targets.

### Task 1: Durable shelf order

Files: `offline/OfflineStore.java`, `offline/OfflineStoreTest.java` under existing main/test Java roots.
Produces: `move(String id, int offset) throws IOException`; list() returns persisted shelf order.

- [x] Add restart, boundaries, new/delete, legacy/corrupt order and failed-write tests.
- [x] Run `:app:testDebugUnitTest --tests '*OfflineStoreTest'`; expect RED for missing move/order behavior.
- [x] Persist UUID order separately from entry .properties; sync/rename before changing visible order.
- [x] Run the targeted tests; expect zero failures; commit and preserve checkpoint.

### Task 2: Saved-only playback snapshot

Files: new `player/playqueue/OfflinePlayQueue.java`; modify `OfflineLibrary.java`, `PlayQueue.java`,
`Player.java`, `LastPlaybackSessionStore.java`; add OfflinePlayQueueTest and extend session tests.
Consumes: ordered `OfflineStore.list()`.
Produces: `OfflinePlayQueue(OfflineStore store, String uploader) throws IOException`,
`OfflinePlayQueue(List<StreamInfoItem> items, int index)`, `PlayQueue.isOfflineOnly()`,
`pruneUnavailable(OfflineStore store, boolean includeCurrent) throws IOException` returning whether current was removed.

- [x] Add real store/queue tests for finite order, no online append/enqueue, missing/truncated/empty, snapshot isolation, pruning/next/previous/repeat and session restoration.
- [x] Run targeted queue/session tests; expect RED for absent queue/scope behavior.
- [x] Build local-only finite queues; restore all-local sessions with that queue class.
- [x] Guard recommendation entry/callbacks and prune at completion, selection/navigation and automatic transitions; remove failed saved items with bounded exhaustion.
- [x] Run targeted and full unit suites; expect zero failures; commit and preserve checkpoint.

### Task 3: Library and playlist discoverability

Files: `OfflineLibraryActivity.java`, values/values-ko offline_strings.xml,
values/offline_ui_ids.xml, layout/fragment_bookmarks.xml and appropriate playlist strings/menu.
Consumes: store move and OfflinePlayQueue constructors.

- [x] Add generated-media Android tests for whole-play/count, accessible move controls and persisted shelf order, plus layout/playlist discovery checks where runtime is available.
- [x] Compile/run available tests; document a genuine device blocker instead of treating compilation as runtime RED/GREEN.
- [x] Add top whole-play/count, vertical labeled management/settings, grouped item controls, labeled up/down with 48dp targets and asynchronous durable moves.
- [x] Clarify existing track action and playlist screen helper text without changing playlist data semantics.
- [x] Compile debug/app-test APK and check style; expect successful compilation and no new style errors; commit.

### Task 4: Final verification and preservation

- [x] Run full unit suite, Checkstyle, debug/app-test build and lint once for final source; compare lint errors with recorded unchanged baseline issues.
- [x] Attempt supported existing Android runtime validation without home/permission/auth workarounds; report actual execution count.
- [x] Independent review of the complete new range; resolve material findings and rerun affected verification.
- [x] Save sanitized command/results, commits, limits and deferred findings in docs/verification; push only existing feature branch and verify remote HEAD/draft state.

Latest authorized continuation: use the existing CI artifact workflow to deliver the final pinned product APK (code 1016/source suffix), with public signature/hash evidence and existing-data safeguards. This does not authorize a main merge or deployment.

CI delivery complete: run 38125933187, artifact 11697696580, pinned product source 98511aba. Downloaded artifact hashes/signature verified; existing-install signer compatibility and actual Android runtime remain blocked/unverified.
