# Detail entry review batch 7, 2026-10-25

## Baseline and scope

- Based on batch 6 commit `ced8719cb78713680727172a08b60b0c5816c7e3`, preserving
  earlier product changes.
- Used Library review `libfile_0250589f3d588191a0973d0bc538e6f1` and verified
  ZIP SHA-256 `afac0f54c81f2ac75cc641e492a4338b3e39020305d3829106cd4ed575f8665f`.
  Its substitute-boundary harness was not counted as app or device verification.
- Changed only MainActivity stream-intent context and VideoDetailFragment deferred metadata,
  tab callback ownership, and queue observer ownership. No release, schema, download, or
  playback-engine change was made.

## Reproduction and correction

| Boundary | Source test RED before correction | Correction |
| --- | --- | --- |
| Stream intent owner | Navigation call received application context, not the Activity that owns pending saved playback. | Pass the MainActivity instance. |
| Deferred details | Same-title current metadata was skipped; old A callback entered rendering after selection B; a reused URL with another service was not recognized as a different item. | Check service ID, URL, and generation; render the latest same-item snapshot. |
| Posted tab | Old view's posted work accessed the replacement binding after a view swap. | Capture the scheduling binding and verify it is still current when the callback runs. |
| Queue observer | Replacing or refreshing queues retained prior subscriptions. | Dispose the previous observer before subscribing to the accepted queue and at disconnect/view destruction. |
| Selection before queue callback | Independent review found that selecting a different queue could retain the old observer if extraction failed before `onQueueUpdate()`. A new test failed on that path. | Release the old observer when `setInitialData()` replaces the queue; same queue identity retains its observer. |

The five initial boundary tests failed against batch 6 and passed after the narrow corrections.
The extra selection-before-callback test failed against the initial batch 7 correction and passed
after the review fix. New tests also cover same-URL/different-service callback rejection and
player disconnect. The independent reviewer found no further concrete issue in the changed paths
and did not run tests.

## Verification

- Final-source JVM: **615 tests, 0 failures/errors/skips**.
- `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, and
  `:app:runCheckstyle`: **PASS**.
- API 23 focused local-audio navigation and fragment lifecycle: **5/5 PASS**.
  The cases covered explicit selection despite old player callbacks, delayed old metadata,
  non-autoplay browsing, queue recreation followed by manual next, and a queued preference
  callback after fragment detachment. Instrumentation ended with `OK (5 tests)`.
- API 23 force-stop first-selection phases: **4/4 PASS**, comprising two seed and two
  verification runs in separate app processes. With a previous saved session and automatic next
  enabled, selected A stayed active before its real end and related B started only after A's
  natural completion. With no previous session and an initial source failure, A remained selected
  for manual retry instead of skipping to B; the retry played A. Each phase ended with
  `OK (1 test)` and the fixture rejected provider network requests.
- API 35: **NOT RUN** because the cloud AVD had system ANRs before tests in prior runs.
- H.264 video playback regression: **NOT RUN** here; the API 23 emulator's software decoder
  produced native `SIGILL` in the previous batch. This is not evidence of a product pass.

## Limits

- Tests use generated/dummy inputs and local audio. No user data or actual provider network
  stream was used. The force-stop cases cover the first-selection sequence with synthetic
  metadata and generated WAVs; actual provider playback on a physical device remains untested.
- Batch 8 and later candidates, backup restore R1, a new APK, main merge, and public release
  remain outside this batch.
