# Temporary Offline Library Implementation Plan

**Goal:** Provide per-item temporary storage and a saved-items shelf using the existing Giga downloader and Player.
**Architecture:** Pure Java durable OfflineStore; Android OfflineLibrary bridge and shelf; local source resolution before extraction. The existing DownloadDialog can target managed storage. Completed downloads and local documents can also be copied into it.
**Stack:** Java, Android API 23+, ExoPlayer 2.19.1, RxJava, existing Gradle/JUnit.
**Spec:** [Temporary offline library](../specs/2026-10-07-offline-library.md)

## Constraints and decisions

- Cloud development; generated media only for validation. No new extraction, authentication, DRM or access-control bypass.
- Separate branch based on main `84b4e0e85f02f29a8523486345db5fc5df9e395d`. PR2/3 remain unmerged; confirmed baseline regression fixes from PR3 are selectively ported for full CI validation.
- Reuse existing downloader, foreground services and sleep timer; no account/server.
- App-private copies: 500,000,000 bytes, seven-day expiry, default unmetered validated Wi-Fi. Wi-Fi restriction can be disabled; cap and lifetime are fixed in this increment.
- Local document copying restarts from its source after interruption. Existing Giga resumption remains responsible for managed downloads.
- Expiry is evaluated on inventory access; physical removal waits for active readers and is otherwise lazy.
- First increment plays audio through the background player, including audio from video files.
- Publish only reviewed source, tests and development documentation to a separate Draft PR. No merge/deployment.

## Completed implementation

- [x] Durable store, atomic metadata updates, incomplete/ready distinction, growth quota, expiry, deletion, active reader retention and restart recovery.
- [x] Local-only source selection before remote extraction, explicit missing/expired errors and tolerant fallback for ordinary online items when the library is unavailable.
- [x] Shelf, progress, pause/retry, per-item/all deletion, import and Wi-Fi control; Korean and English text.
- [x] Existing Giga managed output, shared quota and policy gates; existing completed-file import.
- [x] Persist shelf completion before removing durable downloader metadata; preserve and allow retry after a failed commit.
- [x] Preserve local playback and sleep timer on network return; suppress remote recommendations for local items.
- [x] Generated-WAV Android tests through the real Player and persisted queue reconstruction.
- [x] Independent code review and correction of scheduler, completion, pause, expiry and online-fallback findings.
- [x] JVM, style and APK validation; record device execution separately with its actual result.

## Validation boundaries

JVM tests cover persistence, quota, unknown length, missing/empty files, interrupted copy retry, cancellation, reader retention, restart expiry cleanup, local routing, RandomAccessFile growth, queue policy, repeated connectivity events and completion handoff/retry. Generated-WAV Android tests cover Player loading, paused queue reconstruction and timer expiry. They are not proof of real YouTube download support, full process-kill playback recovery, real transport switching or OEM background behavior. See [verification](../../verification/offline-library-2026-10-07.md).
