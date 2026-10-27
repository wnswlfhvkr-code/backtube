# Player UI review batch 6, 2026-10-25

## Baseline and scope

- Based on batch 5 commit `26aa730ac3352e365eeb0f8bf6e8b0fd0e6247fc`,
  preserving the batch 3–5 and earlier product changes.
- Materialized Library review `libfile_b93e215c16148191879086c96b7973e5`;
  SHA-256 matched `c7cda327ed5b4d707e6e03f2047faff687c84115f0ffc9b5c95e6db31f44c32c`.
  The review's Android-boundary substitute harness is supporting analysis,
  not actual device verification.
- Changed only popup size calculation, Main video orientation handling, and
  Video UI final-lifetime controls callbacks. No schema, playback engine, or
  release change was made.

## Reproduction and fixes

| Boundary | RED observation | Minimal correction |
| --- | --- | --- |
| Popup width/height | A requested 1000px width clamped to 400px kept a 562px height; an 80px request raised to 160px kept a 45px height. One in-range case passed. | Compute 16:9 height from the clamped width. |
| Main video size | API 23 zero-dimension renderer callback erased the previous vertical orientation decision. A valid horizontal size still updated orientation and surface ratio. | Return after the parent callback for either zero dimension; valid events continue. |
| Reused video binding | API 23 old UI's delayed hide hid the new UI's shared controls. A retained old click still ran its action after final destroy. The valid-size control case passed. | Final `destroy()` removes queued controls work and detaches/cancels the old root animation. Guards cover already dispatched hide/end callbacks, stale click/dismiss, and final-lifetime controls methods. `destroyPlayer()` remains separate so an engine rebuild can keep its UI. |

The first JVM attempt to run Main orientation checks with mocked Android
`DeviceUtils` failed in class initialization because the plain JVM Android
`Build` stub has null device fields. That is a test-environment failure and was
not counted as a product RED result. Main size and controls cases were then
reproduced using real Android views and a generated, nonplaying debug service.

## Verification

- Final-source JVM: **607 tests, 0 failures/errors/skips**.
- `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, and
  `:app:runCheckstyle` passed.
- API 23 new focused cases: **4/4 passed** after three product RED failures
  and one unchanged-size control pass.
- API 23 existing audio UI/playback regressions: **2/2 passed** (queue screen
  swipe/buttons/list and timer fade/cancellation respecting mute).
- API 23 existing popup playback regressions: **NOT VERIFIED**. The first
  selected popup test invoked the emulator's software H.264 decoder, which
  terminated the instrumentation process with native `SIGILL` before a test
  assertion/result. Emulator logcat identifies `le.h264.decoder` and
  `OMX.google.h264.decoder`. The video test was not repeated or counted as a
  product failure/pass.
- API 35: **NOT RUN**; prior clean cloud AVD startups had system ANRs before
  tests began. No Windows or user-device testing was performed.

## Environment and limits

- The API 23 debug emulator reached 37.9MB free during app install. Removed
  only the generated batch 4 and batch 5 debug app and test packages (four
  package IDs), after which `/data` had 174.7MB free. No AVD reset, cache wipe,
  user app data, source, or preserved evidence was removed.
  Removed IDs: `org.schabi.newpipe.debug.fixdownloadboundarybatch420261025`
  and `.test`, plus `org.schabi.newpipe.debug.fixplayqueuebatch520261025`
  and `.test`.
- Focused tests use generated views and the debug app service without network
  requests or user media. OS-level popup overlay layout, every volume route,
  and every animator/callback interleaving are not established by these cases.
- The lifecycle correction covers the controls handler and root animation
  callbacks only; it does not claim all older UI work is canceled. Batch 7+
  candidates, backup restore R1, a new APK, main merge, and public release
  are separate work.
