# Download boundary review batch 4, 2026-10-25

## Baseline and scope

- Started from batch 3 commit `5190a55a4be47076bb795b59f537be4f6bb59077`.
- The four target product files were unchanged from Web review baseline
  `6095d418bb36e6bcc3a38d51a40fe99ac17a12f3`.
- Materialized Library bundle `libfile_1e75c6e1e0488191b6739f9cb4a33f58` and
  verified SHA-256 `0584cadbc17d62f023b59c69a6fd0baf1ec1df5f279dcee33baf792e4ae9f106`.
- Tests use generated URI/provider rows, app-cache files, and isolated download databases.
  They do not access user files, real providers, or the network.

## Reproduction and changes

| Boundary | Original behavior observed | Minimal change |
| --- | --- | --- |
| SAF exact child lookup | A broad provider row `clip.mp4.backup` won ahead of `clip.mp4`. A prefix directory could reach `delete()` while creating `clip.mp4`. | Require an exact case-folded display-name match in `findFileSAFHelper`; keep the separate numbered-name prefix search. |
| Stored target equality | Same name/MIME placeholders under different parents compared equal; equal document IDs from different SAF authorities compared equal. | Scope invalid placeholder identity to its parent, and valid SAF identity to authority plus document ID. Valid direct paths and valid SAF parent aliases retain their prior behavior. |
| Mission adapter binding | Rebinding one pending holder appended a duplicate; binding that holder to a finished item left a stale pending entry. | Remove the holder from pending tracking before each item bind, then add it only for a pending mission. |
| Ranged HTTP 403 recovery | `DownloadMission` created logical worker ID 0, while recovery was reserved for ID 1; the only worker could exit without requesting recovery. | Reserve recovery for logical worker ID 0. Thread display numbering stays unchanged. |

## Verification

- RED: three SAF lookup tests failed on the original prefix match, including a simulated
  prefix-directory deletion call. The worker test failed for both ID 0 and ID 1; its
  non-403 regression passed. Seven real API 23 Android cases ran, with four expected
  failures in the two equality and two holder-binding bug paths. Three compatibility
  cases passed before the changes.
- GREEN JVM: `:app:testDebugUnitTest` reports 597 tests, 0 failures, 0 errors,
  0 skips. `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, and
  `:app:runCheckstyle` pass. After adding the adapter's real master menu items
  to its test fixture, API 23 focused instrumentation reports `OK (10 tests)`:
  five target identity cases, two holder binding cases, and three existing
  offline stream/duplicate-save regressions. The first GREEN instrumentation
  attempt reached the corrected holder count, then failed at recycle because
  the fixture had omitted those menu items; the corrected fixture passes.

## Limits

- The SAF lookup test uses a provider boundary double that deliberately returns rows
  wider than the selection. Provider-specific permission and case behavior remain untested.
- Valid document ID case folding and legacy unknown-parent placeholder comparison are
  retained. No URI or database records are rewritten.
- The holder test calls the real Android adapter and RecyclerView holder with generated
  missions; it does not cover every layout/animation/Handler ordering.
- Worker ID 0 is a designated recovery leader. This does not elect another leader if
  worker 0 has already exited before another worker alone receives 403. No real HTTP
  provider or concurrent worker recovery was run.
- The Web bundle's two uppercase unique-name diagnostics remain deferred; this exact
  lookup change does not claim to fix them. Batch 5 and later, backup restore R1,
  APK release, and main integration are separate work. API 35 was not run because both
  prior and clean cloud AVDs had system startup ANRs before tests began.
