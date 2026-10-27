# Player tail review batch 8, 2026-10-25

## Baseline and scope

- Based on batch 7 commit `ec61e7592f2729e8a2ba373b6d463f885a138d89`, preserving
  batch 3–7 and earlier product changes.
- Read official Library patch `libfile_5616bac2cb7c819184ac685cd5f4c783` and review
  `libfile_91da5c22990c8191836f59c1a639752c`. Their substitute-boundary
  checks were treated as review input, not app or Android verification.
- Changed only playback cache identity, recycled queue-row display, seekbar preview ownership,
  and clearing the displayed preview on metadata change. No saved file, cache deletion,
  schema, playback engine, main branch, or public release change was made.

## Reproduction and corrections

| Boundary | RED observation | Correction |
| --- | --- | --- |
| Service cache identity | Audio and video with the same info/stream IDs from different services yielded identical keys. | Append service ID to the key, rather than using it as `StringBuilder` capacity. Signed URL refresh stability, unknown-format content fallback, resolution and audio-track separation remain covered. |
| Recycled queue row | Android View retained A's title when B's title was empty, and duration stayed hidden after unknown duration was rebound to positive duration. | Set the title on every bind and explicitly show positive duration. |
| Preview reset | A stale reset cleared the current map; an old worker published into a newer request; an old crop could still return after the request changed. | Invalidate generation and map under one monitor, reject old work, check and publish under that monitor, then discard/recycle a crop if its request became stale. |
| Storyboard crop | A 40×50 image with a requested 50×50 frame threw `IllegalArgumentException` on Android. | Return no frame for nonpositive dimensions or a source smaller than a cell; normal whole-frame copy remains covered. |
| Displayed preview | Android UI retained the previous ImageView drawable after metadata changed. | Clear the drawable and hide the ImageView before resetting its holder. |

The publication race uses a separate test that blocks precisely at the monitor used by
`publishGeneratedData`: it failed when the generation check happened before entering the monitor
and passed after the check and insertion were placed together inside it. The earlier worker test
only covers a stale request stopped before loading and is reported separately.

## Verification

- Final-source JVM: **620 tests, 0 failures/errors/skips**.
- `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, and
  `:app:runCheckstyle`: **PASS**.
- API 23 focused device tests: **13 passed, 0 failed**. They cover two recycled-row
  transitions, eight preview ownership/crop/publication cases, and three playback/UI
  regressions. On the original source, seven of these device checks failed for the
  reported defects. The first corrected-source run passed 12/13; the remaining test
  incorrectly expected `1:00` where the app formats one minute as `01:00`.
  Correcting that test expectation and rerunning the same 13 yielded **13/13 PASS**.
  The final-source unit, debug build, Android-test build, and Checkstyle tasks were
  rerun after that test edit; all passed.
- API 35: **NOT RUN**; previous clean cloud AVD startups had system ANRs before tests began.
- H.264 video playback: **NOT RUN**; this API 23 emulator previously terminated in the
  software decoder with native `SIGILL`.

## Limits and follow-up

- Cache-key compatibility changes for existing temporary online Exo cache entries; they remain
  on disk until ordinary eviction but are not found under the new keys. Saved offline files use
  the separate `OfflineLibrary.source` path and were not changed or deleted.
- An already-running Coil bitmap load is not actively canceled. Generation checks prevent its
  stale result from publishing, but do not prove prompt network or decoder cancellation.
- The reviewed MediaBrowser children parser is still eager in `MediaBrowserImpl.onLoadChildren`.
  Batch 5 changed `MediaBrowserPlaybackPreparer`, a different entry point. The current batch 8
  instruction excluded the MediaBrowser candidate, so it remains unmodified and unverified here.
- All device inputs are generated/local; actual provider playback, physical-device UI, batch 9+
  candidates, backup restore R1, new APK delivery, main merge, and public release are separate.
- To allow the API 23 test install, only old synthetic debug app/test package pairs from
  prior Backtube test branches were uninstalled from the emulator. No source, SDK,
  dependency cache, preserved APK, journal, actual user app, or test evidence was removed.
