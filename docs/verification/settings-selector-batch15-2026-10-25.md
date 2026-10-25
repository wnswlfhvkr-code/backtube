# Settings and selector review batch 15, 2026-10-25

## Baseline and scope

- Based on batch 13 commit `56720634478db544a4583d6b45d4e1323e182cbb`;
  batch 14 and earlier 1036 work are already in its ancestry.
- The Library ZIP `libfile_158eee53c3888191b1cda62d860b4239` was
  materialized using the supported consumer-local flow. Its SHA-256 matched
  `6bc311f1a7f2346194ac6065c8b3c2abf198be5a48643cda5534ac035e31f778`
  and ZIP CRC passed. The companion patch and review are
  `libfile_9dbc5d96065c8191aa9e0c79b5d2bb54` and
  `libfile_39a01ed547648191a0713906979caa09`.
- Web substitute checks were not counted as app tests. This change does not
  import/export real subscriptions or modify user settings and data.

## Reproduction and correction

| Boundary | RED on baseline | Correction |
| --- | --- | --- |
| Preference XML parser | Three JVM tests showed the acquired parser stayed open after normal completion, feature failure, and read failure. | Close the `XmlResourceParser` in `finally`. |
| Local playlist tab ID | The pinned NanoJSON round trip truncated IDs above the 32-bit range. | Read the stored ID as `long`, preserving the existing serialized form. |
| Malformed tab settings | Wrong `tabs` value types and an unknown playlist enum escaped the checked parse boundary. | Turn those errors into `InvalidJsonException`, which the existing `TabsManager` fallback handles. |
| Selector row and view ownership | Eight API 23 tests failed on baseline: stale rows selected replacement items, empty/list changes did not notify adapters, and old subscriptions outlived their views. | Dispose view-owned subscriptions, ignore late callbacks, publish copied list snapshots with adapter notifications, and check list/view identity before selecting a bound item. |

The selectors were tested with an in-memory Room database and synthetic
channel, group, and playlist entities. The tests do not touch actual user
subscriptions or playlists.

## Verification and limits

- Final-source `:app:testDebugUnitTest`: **646 tests, 0 failures, 0 errors,
  0 skips**. `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, and
  `:app:runCheckstyle` passed in the same Gradle invocation.
- Final-source API 23 focused tests: **9/9 PASS**. Eight selector checks
  cover channel/group/playlist old-row selection, current-row selection after
  rebind, empty/list updates, and disposal on view detach. One compiled
  resource check verifies parser close after an early XML failure. The first
  Android run had the expected eight selector RED failures plus one test
  fixture failure from an API 24-only preference cleanup call. The fixture
  was corrected to use an isolated API 23-compatible preference clear; the
  final run passed all nine against the modified product APK.
- Independent read-only review found no commit blocker; the reviewer did not
  run tests. NanoJSON filters non-object members of an otherwise valid `tabs`
  array, consistent with the existing invalid-entry ignore policy. This batch
  did not change that policy.
- The late-callback checks inject an old observer after a real Fragment view
  detach and attach synthetic replacement fields. They prove the generation
  and disposal guards, but do not reproduce a device-timed Room emission
  during a full destroy/recreate cycle. The playlist query itself is checked
  for disposal after view detach.
- API 35 remains untested because of cloud AVD system ANRs. H.264 on API 23
  remains untested after a software decoder `SIGILL`. Actual device behavior,
  real provider data, and real user settings are not verified here.
- Residual batch 13 risk: `SubscriptionManager.kt` around line 155,
  `rememberAllStreams`, only inspects the first channel tab and ignores
  extraction/database errors, so
  old-stream marking can be incomplete. This batch did not change it.
- Residual batch 11 risk: `local/history/StatisticsPlaylistFragment.java`
  around lines 343–357 can deliver a history deletion callback after the
  view detaches.
  This batch did not change it.
- No new APK delivery, main merge, or public release is part of this batch.
