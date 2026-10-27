# Backtube network consent update, 2026-10-26

## Requested behavior and implementation

- A connected Wi-Fi transport permits an explicitly started download or temporary save, including metered or unvalidated Wi-Fi. The former cross-network and offline Wi-Fi-only switches no longer control admission and were removed from their screens.
- On mobile data, each new request shows a confirmation before dispatch. Cancel, Back, no network, or an unsupported transport dispatch nothing. Consent is stored on the individual pending mission, including its metadata for restart and queue recovery; an older mission has no consent by default.
- The service rechecks the active transport before starting or resuming any pending mission. A switch to mobile pauses a running unapproved mission and leaves it queued until its item is retried from the download list or offline shelf. Manual pause clears the queued state; concurrent queue limits remain in force.
- The offline shelf now distinguishes a mission awaiting mobile approval from a queue wait. A Wi-Fi request bypasses the old metered/validated Wi-Fi restrictions. Normal and offline downloads share the same service admission rule.

## Reproduction and verification

- Before implementation, `DownloadNetworkPolicyTest` reproduced the restrictive Wi-Fi behavior and `OfflineQueueTest` reproduced an unapproved mobile start. The manual pause-all test reproduced a queued item restarting after reconnection.
- At product commit `a706d20d406216cbe23d2bf17716d5bfef5c09e2`, `:app:testDebugUnitTest` passed **683 tests, 0 failures and 0 errors**. `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, and `:app:runCheckstyle` passed without Checkstyle violations. `:app:lintDebug` passed with 0 errors and 352 warnings; the only warning in a changed product file is an existing `NotifyDataSetChanged` warning in `MissionAdapter`.
- API 23 instrumentation on a software emulator passed **9/9**: two mobile confirmation/cancel cases, two duplicate-save cases, one online video long-press menu case, and four synthetic offline queue drain cases. See [the device test log](evidence/backtube-1039-network-consent-2026-10-26/api23-focused.log). Test activity and queue missions were generated for this run. The initial run failed because the emulator had no active network and the tests assumed one; the final device tests inject only their synthetic network boundary and pass without a real download.
- All network tests use mocks, synthetic missions, or generated files. No actual provider download or user data was used.

## Compatibility and limits

- Existing serialized missions default to `mobileDataApproved=false`. The existing mission serialization UID and file format remain in place; the new field is covered by a round-trip test.
- Active mobile transfers remain subject to operating-system connectivity callbacks. A transport change while a socket operation is already in flight cannot be proved byte-atomic by these tests; no physical phone or actual carrier transfer was tested.
- No main merge or public release is part of this update.

## Final build and device results

- Source branch: `fix/download-mobile-confirmation-20261026`; product commit `a706d20d406216cbe23d2bf17716d5bfef5c09e2`, confirmed on the remote branch.
- APK: `backtube-mobile-confirm-a706d20-1039-20261026.apk`, 11,551,423 bytes, SHA-256 `b1dd5e03f8f1315abd39f935627707ebc17395aeadfcba6f587411e6fb73f65c`, Library `libfile_808e534e182c8191a9b633950b00a4c7`.
- Package `org.schabi.newpipe.continuous.testofflinelibrary20261011`, versionCode `1039`, versionName `0.29.1-backtube-mobile-confirm-20261026`; prior delivered APK was versionCode `1038` with the same package. `apksigner verify` and 16 KiB `zipalign -c` passed. Signer certificate SHA-256 is `4fc26294d54b6c9adb276b0b3aab38592077fe28c9f3c64d93da5d9ff6bb5bbc`, identical to 1038. The 11 declared permissions match 1038 exactly. The build used the existing continuous signing configuration and created no new key.
- `:app:assembleContinuous` passed from the product commit with the 1039 version overrides and existing signing guard. `adb install -r` of the exact 1039 APK over installed 1038 succeeded, without uninstalling the package. Installed metadata reported versionCode 1039, and `MainActivity` launched and became the resumed activity.
- Immediately before and after that update, SHA-256 was identical for a generated offline video's media (`86af0c0f8767a8be39ba487cb580c0ec50e53d38ae53c961ff4692416978cbfd`), its metadata (`414cff8769cbb815fb9e87ea81610abc5a60cec893176c89cd074631745898c3`), and the app preferences XML (`fb407e6b73e6a9efae4693f9d6ee86f8463aebbcd7c7fe48f933b46947cd639f`). This checks upgrade preservation for those generated files; it does not prove all user data paths or real phone behavior.
- No actual provider download, carrier data transfer, physical phone, API 35 playback, or full end-to-end Wi-Fi to mobile handoff was run. The original user's stuck download was not reproduced on their device, so its resolution is not claimed. Older unresolved R1 backup restore boundaries remain outside this change.

## GitHub release attempt, 2026-10-27

- The approved release tag `v0.29.1-backtube.1039` was created and remotely read back at the exact APK build source `a706d20d406216cbe23d2bf17716d5bfef5c09e2`. The existing repository visibility was kept public; main was not merged or changed.
- Release draft ID `425069608` was created with installation, SHA-256, debug signing, package compatibility, license/source access, tests, and limitations. It remains **draft and has zero assets**.
- The official `gh release upload` action for `backtube-mobile-confirm-a706d20-1039-20261026.apk` was definitively rejected by `uploads.github.com` with `HTTP 401 Bad credentials`. A subsequent read confirmed the asset was absent. Release metadata writes worked, so this is an asset-upload authentication-path failure, not a complete repository access failure. No alternate transfer or credential change was attempted.
- The draft title and body explicitly say APK attachment is pending. Do not publish until this same APK is attached and its remote size and SHA-256 match the Library file, then remove the draft warning. The built-in app updater checks NewPipe's upstream service, not this GitHub release; manual GitHub download is required.
