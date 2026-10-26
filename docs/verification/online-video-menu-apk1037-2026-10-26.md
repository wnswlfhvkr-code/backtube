# Backtube 1037 menu update candidate, 2026-10-26

This APK was built from product source `918e80ee6321766e739b145712543b6c38708be1` without the later offline-worker-drain queue change. It is an update candidate for the prior 1036 test-distribution package, not a public release.

| Check | Result |
| --- | --- |
| Package | `org.schabi.newpipe.continuous.testofflinelibrary20261011`, equal to 1036 |
| Version | `versionCode=1037`, `versionName=0.29.1-backtube-menu-20261026` |
| File | `backtube-menu-918e80e-1037-20261026.apk`, 11,564,603 bytes |
| APK SHA-256 | `5a1205b1654a88d1bf1c6ddbd04ea25035771020f72752aa97f39d379d974732` |
| Signing certificate SHA-256 | `4fc26294d54b6c9adb276b0b3aab38592077fe28c9f3c64d93da5d9ff6bb5bbc`, equal to 1036 |
| Signature and alignment | APK signature valid; 16 KiB page zip alignment valid |
| SDK and permissions | minSdk 23, targetSdk 35, and declared permissions equal to 1036 |

The existing approved debug signing input was used through the continuous build. The build guard confirmed the original signing path; no new signing identity was generated, and key contents were not read or copied. A first packaging attempt used a mixed-case branch suffix and therefore produced a different package; that artifact was rejected and not delivered. The final candidate above uses the exact 1036 package.

## Update and behavior checks

- The cloud API 23 test device held the exact 1036 APK (`d163993b1231c9af18d75f5ef8b959e37614746f19cc683660ac8d37bd6a51d5`). `adb install -r` installed 1037 without deleting the app.
- A generated saved video's metadata and media bytes, and the app's settings file, matched their pre-update hashes immediately after installation. No actual user data was accessed.
- The 1037 offline shelf opened with two generated playable saves. A generated local video opened in MAIN and rendered video frames with the playback timeline. This is a short synthetic media check, not a physical-phone or provider-download check.
- Source-level final run: 671 JVM tests, no failure/error/skip, app and Android-test builds, Checkstyle PASS. API 23 instrumented checks: online-video menu 1/1, existing duplicate-save behavior 2/2, test provider boundary 5/5 PASS. The menu instrumented check used the same product source in a debug variant; the installed continuous APK was checked separately for update and saved-video playback.

Known limits: API 35 and physical-phone tests were not run. Real provider downloads were not run. R1 backup-restore risk remains. The user's persistent offline-download wait is not attributed to a confirmed device condition; its independently reproduced worker-drain scheduler fix is on a separate source branch and **is not present in this 1037 APK**. Main was not merged and no public release was created.
