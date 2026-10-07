# Playback feedback and durable saved media

## Authorized scope and baseline

Start from verified integration code b5b6e3111 and its documentation checkpoint
896fa7aa5 on a separate `feat/playback-feedback` branch. Main remains 84b4e0e85;
PR3 remains bff8f4a44 and PR5 remains unchanged. Work only in cloud. Preserve
automatic focus resume default ON, deliberate OFF, timer, recovery and offline
playback behavior. No protected-media acquisition, credentials, signing changes,
new accounts, relay deployment, main merge or public release.

The later explicit request supersedes the original seven-day retention design:
saved media remains until explicit deletion; the 500 MB cap remains. Existing
files must not be evicted to admit a new save. Files already deleted by old
versions cannot be recovered by a metadata migration.

## Design and independent ownership

1. Navigation: Android Back returns from playback to main; main-root first Back
   displays `한 번 더 뒤로가면 종료됩니다` for one second; another within that window
   finishes the UI task. This does not stop the existing playback service or
   timer. Reset the armed window when navigation/lifecycle invalidates it.
   Diagnose center swipe interception and preserve seek/button interactions.
2. Player: listening mode beside quality uses the existing audio selection path
   and disables video work where supported. Preserve queue, position, volume,
   timer and playback intent. Popup expansion respects device/user orientation.
3. Storage: remove time-triggered expiry from all maintenance/read paths;
   migrate legacy expiry metadata without deleting surviving bytes. Retain
   physical space and shared 500 MB checks for partial and complete media.
4. Saved-media UI: independent navigation/menu entry and save action; completion
   action opens the saved list. Storage-full errors explain that users must
   delete an item. Notification denial must not prevent list access.
5. Error reporting: narrow app-fault classifier, metadata allowlist, bounded
   persistent local outbox, dedupe and rate limit. Exclude network/provider/data
   errors and all user content, URLs, paths, identifiers and credentials. No
   live issue or transport credentials; document the actual supported transport
   and any authentication blocker after inspection.
6. Slow playback: read-only investigation of this repository's ExoPlayer 2.19.1
   and Sonic behavior using primary sources. No speculative DSP modification.

Independent agents own navigation, player, persistence, saved UI and reporting;
one read-only agent researches audio. Root owns integration, documentation,
Gradle, CI, review and exact APK provenance. Shared-file insertions are coordinated
with their owner. Claude participation is recorded only if actually supplied.

## Verification and delivery

- Meaningful tests for Back timing/reset, central gesture versus seeking,
  listening transitions and orientation policy, redacted report contracts,
  legacy retention beyond eight days/reopen and no-eviction quota rejection.
- Generated local media only for Android playback and saved-list tests; retain
  process-restart and network-disabled checks on API 23 and API 35.
- Root runs JVM tests, Checkstyle, debug/test assembly, then GitHub KVM Android
  suites and continuous APK build. No local KVM availability is claimed.
- Review combined diff, preserve branch/PR remotely, and deliver the exact
  tested artifact with source/build SHA, package, version, SHA256 and signing
  fingerprint. Existing ephemeral CI signing means update compatibility must
  be verified rather than assumed. No installed-user-data deletion.
