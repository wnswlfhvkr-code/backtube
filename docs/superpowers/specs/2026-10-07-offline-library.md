# Temporary offline library

Provide per-content temporary offline storage, progress, interruption/retry, a saved-items shelf, same-app playback and storage management. The interaction is a conventional offline library, without copying another service's brand or content.

Implement general local storage/playback infrastructure in cloud only. Connect completed Giga audio/video downloads and user-selected local documents to an app-private shelf. Never add extraction, authentication, DRM or download-block bypasses; never fetch or test protected service media. This is not the YouTube official offline API. Only generated media is used for validation. Service terms and technical capability are distinct.

Default budget 500 MB (decimal), expiry seven days after a copy completes. Copies are isolated from original downloads; deleting a shelf copy never deletes the source. Incomplete copies are never playable. Interrupted copies can be retried from the local source; no network retry is implied. A shelf item uses stable origin identity when available so ordinary playback can prefer its saved file before extraction. Local-only items must never fall through to a network extractor. Network return must not reload local playback or start paused playback. Existing PlayerService and sleep timer own playback.

Integration: an item-specific action in the existing DownloadDialog starts the unchanged Giga extraction/download/postprocessing flow into managed app storage. Completed ordinary Giga items and local documents can also be copied into the shelf. App-managed Giga output growth shares the 500 MB budget; temporary postprocessing scratch is separate and still constrained by available device space. Default policy requires validated, unmetered Wi-Fi; the shelf can explicitly allow other connections, while existing Giga network restrictions still apply. Local copies need no new downloader. The shelf provides audio playback through the existing background player, including audio from video files. Fullscreen offline video UI is outside this first increment.

Base: main 84b4e0e85. PR2 and PR3 remain unmerged and are not included. Preserve current timer engine; keep new UI outside detail layouts to avoid their timer conflicts.

Delivery: code, tests and minimal development documentation in a separate Draft PR to the existing backtube repository. Do not include user conversations, downloaded media, personal files, credentials or unreviewed execution logs. Do not change repository visibility, merge or deploy.
