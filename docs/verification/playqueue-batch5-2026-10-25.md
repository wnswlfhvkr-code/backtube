# Playback queue review batch 5, 2026-10-25

## Scope and source

- Built on batch 4 commit `ee4b0695b7e1988fa65ea597cb570a64d6e139eb`,
  retaining batch 3 and earlier playback, offline, and UI fixes.
- Materialized Web review Library bundle `libfile_e8404af4337081918a27dc1827349301`
  and verified SHA-256 `93a586e5411aa07532d247096721b25cace9e5986cb807f50f9448d6493c04a0`.
  Its extracted-method harness is not an Android integration test.
- Changed only queue append, queue adapter APPEND notification, queue Activity adapter
  ownership, and media browser ID error handling. Batch 8's overlapping media-browser
  candidate is not separately applied. No schema, source identity, release, or package
  behavior was intentionally changed.

## RED observations and minimal fixes

| Boundary | RED observation | Fix |
| --- | --- | --- |
| Selected and pending automatic items | Five of six new JVM cases failed: an empty append threw, selected automatic B was replaced by manual C, pending B returned after unshuffle, raw removal omitted REMOVE, and manual enqueue-next changed the selection. | Remove only a *pending* automatic tail through the ordinary remove path when nonempty manual items arrive. Preserve an empty append event. |
| APPEND adapter notification | Three API 23 checks saw insertion indices beyond their actual snapshot for single, rapid, and pending-tail replacement appends. | Refresh the current snapshot on APPEND because the event has no insertion index and delivery can lag queue edits. This trades insertion animation for correct row membership. |
| Queue Activity ownership | After queue replacement the previous adapter still retained its Rx subscription. | Unset its selection listener and dispose it before replacing or clearing the Activity's adapter. The queue and player remain owned by their existing components. |
| Media-browser parser | Generated malformed numeric and unknown-type media IDs threw synchronously from the MediaSession entry point before the error callback. | Defer ID extraction inside the existing scheduled Single subscription and error handler. |

The first Activity adapter test attempt failed in its fixture because it inflated
AppCompat UI with an unthemed context; a themed context and main-thread Activity
construction were required. With the corrected fixture, the old adapter subscription
failure was reproduced before the product change. No product code was changed for
the fixture errors.

## Verification

- JVM: `:app:testDebugUnitTest` **604 tests, 0 failures/errors/skips** on the
  final source; seven new queue cases passed after five of the initial six
  produced expected RED failures. The seventh covers an actual shuffled queue.
- `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, and `:app:runCheckstyle`
  passed on the final source.
- API 23: the six new focused cases report `OK (6 tests)` after confirmed RED
  failures. Six existing playback regressions also report `OK (6 tests)`:
  natural completion to recommendation, manual-queue preservation, duplicate
  next handling, explicit selection over old callbacks, manual next after initial
  failure, and no related-item start on initial audio failure.

## Limits

- Device tests use generated queue entries and malformed IDs. No real network,
  provider request, or user's saved content was used.
- Queue adapter tests inspect actual Android RecyclerView notifications and
  subscription state, but do not establish scroll position or animation quality
  under all layouts. Snapshot refresh is a correctness choice, not an optimization.
- The browser tests cover invalid-ID error routing, not every valid media-browser
  client or actual playback. The first-selection issue is not attributed to these
  four findings.
- API 35 remains NOT RUN because prior clean cloud AVD startups had system ANRs.
  Backup restore R1, batches 6 onward, main merge, and public release are outside
  this change.
