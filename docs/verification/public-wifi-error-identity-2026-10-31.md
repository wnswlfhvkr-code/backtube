# Public Wi-Fi detail error and player identity (2026-10-31)

Baseline: personal 1041 source `b966504dbcaa1b4a51a04080312ada227d9c8e95`; this change is based on the existing gesture branch `b6beb387716ed6fe071eff52838d5fcbae05e318`.

The user's screenshots show a requested YouTube detail error alongside a different, already playing item's video/notification. The official YouTube app played the requested video on the same Wi-Fi. The Backtube error string maps to `SignInConfirmNotBotException` from the extractor's `LOGIN_REQUIRED` bot challenge path. This is a provider response to Backtube's anonymous request; the screenshots do not prove that the Wi-Fi itself requires authentication or that the IP is blocked. A normal, login-free request cannot guarantee access when the provider requires verification. No challenge bypass, client switch, proxy, cookie transfer, or Wi-Fi security change was made.

For a newly selected item, `VideoDetailFragment.runWorker` makes one detail extraction subscription. An extraction error goes to `showError`; it does not itself start the selected item's player or automatically retry. The retry button starts a forced user-requested reload. Playback media-source recovery is a separate bounded path for a queue item already sent to the player. The detail error path left the previous MAIN video view attached behind the new item's title and error. This was the confirmed app UI defect; the provider challenge's underlying cause remains unverified.

On a detail error, the fragment now detaches only a MAIN video view attached to this fragment when the active queue item differs from the shown detail identity. It does not stop the previous playback service or replace the active queue. A later service/view callback cannot reattach the unrelated video while the error panel remains. If the user returns to the already playing item's details, the detached view is scheduled for reattachment. An unrelated popup view or a MAIN view owned by another parent is left alone.

## Verification

- RED: `VideoDetailErrorIdentityTest.failedSelectionHidesOnlyPreviousVideoView` failed against the prior error path. GREEN: it passed after the UI fix.
- RED: `returningToPlayingItemAfterErrorSchedulesVideoReattachment` failed before the revisit fix. GREEN: it passed after the revisit fix.
- PASS: same-item error keeps its own video UI; focused JVM tests passed. The revisit unit test verifies the UI reattachment callback is scheduled. It does not render a real Android surface.
- PASS: final `:app:testDebugUnitTest` (705 tests, 0 failures/errors/skips), `:app:assembleDebug`, `:app:runCheckstyle`, and `git diff --check`.
- PASS: independent code review found a revisit regression in the first change; it was fixed and re-reviewed without a remaining blocking finding.
- NOT RUN: real YouTube request on the user's public Wi-Fi, physical-phone UI test, provider challenge resolution, and signed personal APK. The prior cloud YouTube request received a 403 and was not repeated or bypassed.

The existing list/detail title-language mismatch is a separate unresolved issue. The prior gesture implementation is preserved; this change is not yet in the previously delivered 1041 APK.
