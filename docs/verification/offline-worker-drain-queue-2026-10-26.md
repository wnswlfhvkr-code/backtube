# Offline queue after a paused worker drains, 2026-10-26

## Report and scope

The user reported one offline audio save remaining on `허용된 네트워크·다운로드 순서 대기` while connected to Wi-Fi. That label means a mission exists, has no recorded error, is enqueued, and is not running. The user's actual network capabilities, app Wi-Fi-only setting, and other missions are unknown. This change addresses one independently reproduced scheduler boundary; it does not establish that this boundary caused the user's report.

## Reproduction

`DownloadManager.resumeMission` correctly avoids restarting an offline mission while a paused initializer or transfer worker is still alive. A deterministic fake worker and allowed-network policy reproduced the gap: reconnect and queue activation happened during worker cleanup, then the worker ended, but no later event called `runMissions`. `OfflineQueueTest.pausedWorkerDrainWakesAllowedOfflineQueue` failed before the change at its final running assertion. A second test found that explicit pause on an already queued, non-running offline mission left `enqueued=true`.

## Minimal change

When an offline queued mission is blocked solely by a live old worker, `DownloadManager` starts one daemon waiter for that mission. The waiter joins a snapshot of those old threads outside the service thread and posts one queue retry to the service handler. The retry checks the mission still belongs to the pending queue and is still enqueued. Existing `runMissions` and `canStartMission` recheck active network, offline Wi-Fi policy, error state, and queue limit before starting; this change does not bypass those gates or raise concurrent download count. Explicit pause, mission removal, and service force pause cancel a pending waiter. A worker that never exits leaves its own mission pending; the waiter uses no polling loop.

## Verification

- RED: allowed network + prior live worker + worker exit left the mission stopped; explicit pause failed to clear the queued flag.
- GREEN: the worker exit schedules one retry, and explicit pause cancels the queued state and pending wake.
- Focused `OfflineQueueTest`, `OfflineCancellationOwnershipTest`, and `DownloadForcePauseTest`: PASS.
- Final `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:runCheckstyle`: PASS, 674 JVM tests, 0 failures, 0 errors, 0 skipped.
- API 23 Android instrumented scheduler checks: 4/4 PASS using a freshly installed isolated debug package. Fake missions never open a URL or write a media file. They cover old-worker drain waking once, queue limit 1, an unavailable offline-policy gate, explicit pause canceling a late wake, and synthetic normal/error termination allowing the next mission. The test uses a narrow override of the offline-policy result; product code still uses `OfflineDownloads.networkAllowed`.
- The first API 23 run failed all four cases before the scheduler assertions because the test APK's context could not open its SQLite DB. The fixture was corrected to use the isolated target debug app's context; rebuilding and rerunning passed 4/4. These initial infrastructure failures are not product failures.
- This separate change was not included in the 1037 menu APK. Real service download callbacks, real provider download, the user's device, API 35, and physical phone: NOT RUN.

The user's device may instead be blocked by a metered or unvalidated Wi-Fi network, the app's Wi-Fi-only setting, an earlier running mission, or another condition. Its setting state is still needed before attributing this code defect to the reported item.
