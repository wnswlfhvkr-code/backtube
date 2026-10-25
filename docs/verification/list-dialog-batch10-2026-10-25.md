# List and dialog review batch 10, 2026-10-25

## Baseline and scope

- Based on batch 9 commit `e0504ba0539eea41182163a371f15234a18abdf4`, preserving
  batch 3–9 and 14 plus earlier 1036 changes.
- Read Library patch `libfile_336880c7e49c8191b815e29ea214d936` and review
  `libfile_e93c72ae5a208191ab3c835656fc1e1c`. Materialized ZIP
  `libfile_91b743b32e348191a10589d0e40bf10a`; SHA-256 matched
  `34480ce0828cd5dddb64f170625fbf9bf45c4abcdb3068be0f58eef34b781faa`
  and ZIP CRC verification passed. Web substitute-boundary checks are not app tests.
- Product changes are limited to ChannelTabFragment, PlaylistFragment,
  StreamSegmentItem and StreamDialogDefaultEntry. A themed Activity fixture exists only
  under `src/debug`; it is absent from release source and manifest.

## Reproduction and corrections

| Boundary | RED result on prior source | Correction |
| --- | --- | --- |
| Channel and playlist continuation | Both queues held head token `page-2` after the loaded-list cursor had advanced to `page-3`. Two API 23 tests failed. | Pass `currentNextPage` with the adapter snapshot, preserving loaded rows and selected index; null remains a completed nonempty queue. |
| Playlist total duration | With loaded durations 30+70 seconds, processing the second page produced 70 instead of 100; repeated processing would accumulate further. API 23 test failed. | Recompute from currently loaded stream rows after each result/page, replacing the cached total. The latest cursor sets the completeness flag. |
| Reused segment title | After an unnamed segment allowed two title lines, a named segment on the same binding still had maxLines=2. API 23 test failed. | Reset maxLines=1 when the channel row is shown. |
| Mark-as-watched error | In the real Rx action chain with controlled IO/main schedulers, error UI ran before main delivery and a detached Fragment reached requireContext. Two JVM tests failed. | Move observeOn(main) ahead of the UI side effect and skip it when the Fragment is detached. Keep the existing error completion behavior. |

The channel fixture initially used an empty tab filter and failed during `ChannelTabInfo`
construction. Giving it a valid synthetic filter and rerunning established the expected
cursor mismatch on both queue tests. All device URLs/items are synthetic; no provider
request or user playlist was used.

Final-source focused API 23 tests: **4/4 PASS**. The queue tests cover a later page,
last-page null cursor, loaded rows and selected index; the segment test covers
unnamed→named→unnamed on the same Android binding; the duration test covers loaded
pages, repeated processing and refresh. Final-source JVM: **623 tests, 0 failures,
errors or skips**. The three dialog tests cover error timing, detachment, and
success/empty results using the actual enum Rx chain with a fake manager and test
schedulers. `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, and
`:app:runCheckstyle` passed. Independent source review found no blocking regression;
the reviewer did not rerun tests.

## Cost and limits

- Duration recomputation scans the loaded adapter rows once per result/page: O(n) for
  n loaded rows. Across many pages, repeated scans can have quadratic total work.
  This favors a current snapshot over a stale increment; no new cache or performance
  improvement claim is made. Very large playlists need separate UI timing evidence.
- Queue tests inject cursor/adapter state and do not fetch actual extractor pages.
  The full parent handleResult/handleNextItems path, displayed completion wording,
  process-restored views, empty queues and physical-device UI were not exercised.
- The dialog tests exercise actual Rx operators but mock DB work and the Fragment;
  an actual Activity paused or state-saved during error delivery was not exercised.
- API 35 remains untested due earlier cloud AVD system ANRs. H.264 playback remains
  untested due earlier API 23 software-decoder `SIGILL`. Actual provider content,
  user files/DB, main merge, public release and a new APK delivery were outside scope.
