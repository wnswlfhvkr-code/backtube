# Search and list review batch 9, 2026-10-25

## Baseline and scope

- Based on batch 14 commit `ee9a5dda338e87b3583296a291ea30c963c8f29a`, preserving
  batch 3–8, 1036 and the batch 14 DAO corrections.
- Read Library patch `libfile_b931745f7d308191a045f20eb46272f4` and review
  `libfile_51d8db11569481918b78efaea9b93a08`. Their substitute-boundary
  checks informed the change; they are not Android results.
- Product diff changes only `InfoListAdapter` and `SearchFragment`. The 1036
  `StreamMiniInfoItemHolder` request/lifecycle implementation was not changed.

## Reproduction and correction

| Boundary | Confirmed before | Correction |
| --- | --- | --- |
| Footer pagination | The Android adapter observer received a range insert and an extra move of a newly added row as though it were the footer. This failed both no-header and header/repeated-page cases. | Keep the range insert, which already shifts the footer; remove only the extra move notification. |
| Search draft save | Saving submitted query A with unsubmitted editor text B changed `searchString` to B. | Store B in an `@State` editor field and preserve A as the result/page query; restore editor and suggestion seed from the draft where present. |
| Search history | The original keyword path subscribed to history before `startLoading`, which immediately cleared the same composite. | Start loading before registering the current search's history subscription. |
| URL transition | The URL branch returned without the keyword path's cleanup of prior work. | Clear previous composite work and dispose the prior result subscription before URL/keyword branching. Empty submission retains its prior no-op behavior. |

Three Android tests failed on the original source: two footer notification cases and one
active-query/draft case. On the corrected source **5/5 focused API 23 tests passed**:
those three, a fake-history subscription lifecycle test, and a recognized-URL branch
test confirming prior keyword work is disposed before routing. The lifecycle tests use
an overridden loading boundary to suppress provider searches; no actual provider
request or user history write is part of those assertions. The draft test also invokes
the application's Bridge save/restore mapping and checks both restored fields.

Final product-source JVM `:app:testDebugUnitTest`: **620 tests, 0 failures/errors/skips**.
`:app:assembleDebug`, `:app:assembleDebugAndroidTest`, and `:app:runCheckstyle` passed.
Independent source review found no blocking regression in the two-file diff and did
not rerun the tests.

## Limits

- The URL test confirms cancellation at the routing boundary, not an Activity launch or
  delivery of a provider result. Actual delayed provider callbacks, the full Fragment
  view recreation with `nextPage`, and physical-device navigation were not run.
- A history write is still asynchronous. A subsequent search or pause can cancel it
  before the IO transaction starts; this batch fixes immediate self-cancellation only.
- Empty-query submission remains the existing no-op and does not cancel a prior search.
- API 35 remains untested due earlier cloud AVD system ANRs. H.264 playback remains
  untested due earlier API 23 software-decoder `SIGILL`.
- No actual user DB/media, cache deletion, new APK delivery, main merge, or public
  release. Batch 10–13 and 15 candidates remain separate.
