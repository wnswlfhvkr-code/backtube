# Backtube 1038 menu and offline queue update, 2026-10-26

## Exact build

- Product source: `07c778e8742f61ab8e6c01ec11d15ce44439b24e` on `fix/offline-worker-drain-queue-20261026`.
- APK: `backtube-menu-queue-07c778e-1038-20261026.apk`, 11,564,608 bytes, SHA-256 `344b9ea9ecd5695a97fd284d3529c3e48486a9392bd62552561f25ce1bd24826`.
- Library: `libfile_538c3d655278819180c6c1eb29373554`.
- Package: `org.schabi.newpipe.continuous.testofflinelibrary20261011`; versionCode `1038`, versionName `0.29.1-backtube-menu-queue-20261026`; min SDK 23, target SDK 35.
- Signer certificate SHA-256: `4fc26294d54b6c9adb276b0b3aab38592077fe28c9f3c64d93da5d9ff6bb5bbc`. This matches the previously delivered 1037 APK. `apksigner verify` and 16 KiB `zipalign -c` passed. The `aapt dump permissions` sets for 1037 and 1038 matched exactly.
- `:app:assembleContinuous` passed (99 tasks, 24 executed). The build used versionCode override 1038 and the existing continuous signing setup. No new signing key was created.

## 1037 to 1038 update and playback

The API 23 emulator already held the delivered 1037 package and generated offline media. `adb install -r` of the exact 1038 APK succeeded without uninstalling that package. Installed package metadata then reported versionCode 1038. SHA-256 hashes of one generated saved video's metadata and media, and the app preferences XML, were identical immediately before and after the update:

| Generated fixture | Before and after SHA-256 |
| --- | --- |
| Metadata | `306c3ad4d914b488e433213c7b9215d87d613f33c3468715a7aad1b347a90da2` |
| Media | `40a38ba2a0d3e1a1be551c65e549c93631d8a7eac17a2b2c89c4ebbb561ab880` |
| Preferences | `b52e352ce561c854193ba025b8fa4d5cc6ee84b6a41929234d10f303da3c7fc7` |

The 1038 app launched, showed both previously generated saved-video items in [History](evidence/backtube-1038-menu-queue-2026-10-26/api23-saved-history.png), and rendered [frames](evidence/backtube-1038-menu-queue-2026-10-26/api23-saved-video-frame.png) from the 30-second generated local video. This is a basic local playback smoke check, not a full audio/video/device regression. No real provider download or user files were used.

## Source-level and focused regression evidence

- The source diff from 1037 product commit `918e80ee6321766e739b145712543b6c38708be1` to 1038 product commit `07c778e8742f61ab8e6c01ec11d15ce44439b24e` changes `DownloadManager`, its queue unit/device tests, and the queue verification record only. The menu and duplicate-save implementation is unchanged.
- At that 1038 source commit, the focused queue change passed 674 JVM tests, build and Checkstyle, plus 4/4 API 23 fake-mission scheduler checks. The queue evidence and limits are detailed in `offline-worker-drain-queue-2026-10-26.md`.
- The preceding 1037 menu code passed an API 23 dialog inspection (1/1) and duplicate-save device checks (2/2). A fresh install of the 1038 source's separate debug package for repeating those device checks failed with `INSTALL_FAILED_INSUFFICIENT_STORAGE` on this emulator. They are **NOT RUN on the 1038 build**; no result is inferred from the failed test installation. The resulting test-only package was removed while retaining the 1038 app and its data.

## Limits and delivery state

The 1038 APK includes the independent queue wake fix, but the user's persistent queued download was not reproduced on their device; current Wi-Fi capability, Wi-Fi-only setting, and other missions remain unknown. Existing network-policy and concurrency gates were not changed. A physical phone, actual provider download, API 35 playback, and a fresh 1038 menu/duplicate device suite were **NOT RUN**. The existing R1 restore boundary remains unresolved. This is a direct update APK, not a main merge or public release.
