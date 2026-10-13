# Personal playlist entry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Make existing personal playlists directly creatable from the bookmarks screen, including an empty list that can later receive tracks.

**Architecture:** Reuse BookmarkFragment, PlaylistCreationDialog and LocalPlaylistManager. The existing Room schema already displays empty metadata and appends at join index 0. Keep the existing join-row-ID return contract; no migration, new playlist subsystem or naming policy.

**Tech Stack:** Java, Android XML/AppCompat, Room, RxJava3, Mockito5/JUnit and existing Android instrumentation source.

**Spec:** `docs/verification/related-tab-compact-offline-shelf-wip-2026-10-11.md` latest 2026-10-13 follow-up, plus parent-approved missing user-playlist entry audit.

## Global Constraints

- Saved-file quota stays 500,000,000 bytes; no automatic expiry or source-user-file deletion.
- Running offline queue remains a snapshot; next Play all uses newly committed shelf order.
- Preserve current schemas, seeded creation, existing bookmarks and join-row-ID return values.
- No Claude/auth/keys/paid API/permission expansion/reset/uninstall/main merge/deployment/new release.
- Official Library401 remains blocked without retry/bypass; actual Android runs 0 unless executed.
- Root owns tracked edits/Gradle. Agents draft privately or review read-only.
- PR7 body editing is now explicitly authorized. Fixed coordinator deadline is 2026-10-14T02:00:00Z.

## Review Focus

- Empty bookmark screen: creation remains visible beside empty/error states.
- Existing seeded creation: first thumbnail and ordered joins retain their contract.
- Empty saved playlist: metadata remains visible and first append starts at index 0.
- Cancel/name dialog: no playlist is written without confirmation; existing name behavior remains.
- Delayed append plus dialog destruction: investigate with controlled scheduler before proposing a fix.

### Task 1: Connect standalone existing playlist creation

**Files:**
- Modify: `app/src/main/java/org/schabi/newpipe/local/bookmark/BookmarkFragment.java`
- Modify: `app/src/main/res/layout/fragment_bookmarks.xml`
- Modify: `app/src/main/java/org/schabi/newpipe/local/playlist/LocalPlaylistManager.java`
- Create test: `app/src/test/java/org/schabi/newpipe/local/playlist/LocalPlaylistCreationTest.java`
- Modify test: `app/src/androidTest/java/org/schabi/newpipe/local/playlist/LocalPlaylistManagerTest.kt`
- Add compiled UI fixture if feasible: `app/src/androidTest/java/org/schabi/newpipe/local/bookmark/PersonalPlaylistEntryTest.java`

**Interfaces:** `createPlaylist(String,List<StreamEntity>) -> Maybe<List<Long>>` continues returning join-row IDs. Bookmark action directly calls `PlaylistCreationDialog.newInstance(List.of()).show(getParentFragmentManager(),tag)`.

- [x] Write JVM assertions: empty input emits one empty result and submits a named playlist with thumbnail -1; seeded creation retains first thumbnail/ordered joins; appending first tracks uses index0. Mock only Room DAO/transaction boundaries.
- [x] Run focused JVM tests and record semantic RED: baseline empty creation completes without a value/insert, not a setup error.
- [x] Remove explicit empty rejection and use DEFAULT_THUMBNAIL_ID when stream IDs are empty inside the existing transaction.
- [x] Add always-visible 48dp New Playlist button below existing hint; list/error/empty anchors follow it. Install its click listener directly to existing creation dialog.
- [x] Update existing Android empty-list expectation to persisted visible metadata; add isolated disk-reopen/append/reorder coverage where practical, compiled only here.
- [x] Run focused GREEN, applicable full JVM/style/lint/Android compilation/unsigned build; whole-plan independent source review remains after Task2.
- [ ] Publish only approved files on existing branch with exact ref/tree/read-back; refresh PR7 results and source/APK limits.

### Task 2: Investigate append disposal separately

**Files:** `PlaylistAppendDialog.java`; a private test draft first, tracked regression only after reviewable reproduction.

**Interface:** existing `onPlaylistSelected`/`onDestroyView` lifecycle and `LocalPlaylistManager.appendToPlaylist` scheduled IO.

- [ ] Draft/run actual handler + controlled IO scheduler regression at Android/Room boundaries.
- [ ] If semantic cancellation is reproduced, write a minimal ownership/completion fix and lifecycle/error tests before implementation; if not, report source-only risk and defer.
- [ ] Review/verify/publish as a separate checkpoint; never claim device reproduction from JVM mocks.

### Task 3: Audit retained offline controls and hand off

- [ ] Record entry -> Play all -> local-only snapshot, handle/accessibility move -> atomic Store order, reopen persistence and file-preservation evidence.
- [ ] Separate proven source/fixture results from real UI0, unresolved Library401 and absent current-source signed installer.
- [ ] Update resume/PR metadata after final verified source and return exact checkpoint evidence to coordinator.

Execution is already directly authorized by the parent; no repeated implementation approval is requested. Root implements inline with independent review. The plan was self-reviewed against the existing API/schema and bounded user request.
