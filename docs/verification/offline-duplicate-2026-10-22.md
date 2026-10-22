# Completed offline-save feedback — candidate 1030

Status: candidate for user acceptance. Draft PR #23 stacks on draft #22. No release
publication or main merge. APK1027 remains **DO NOT INSTALL**. Windows QA is not
required and was not run; all execution used the existing cloud environment.

## Behavior

A completed save of the requested media kind now shows the short floating message
**이미 저장된 항목입니다** (English fallback: “This item is already saved”). It exits
before filename preparation, thumbnail work, output allocation, download dispatch
or saved-library navigation. The generic entry skips the selector when every
available downloadable kind is already complete. If another kind remains available,
the existing audio/video selector stays available; confirming a completed kind
shows the message and closes it. Audio and video remain distinct under the existing
save-identity rules, including canonical provider URLs and owned local identities.

The preflight runs off the UI thread, coalesces rapid taps and ignores stale view
or stream results. Only READY metadata with positive, readable, exact-length owned
bytes counts as complete. Pending, failed, interrupted, missing, truncated or
unreadable saves remain eligible for the existing retry flow. Registration is
checked again before thumbnail/file work if another request completes concurrently;
the existing atomic download claim remains unchanged.

Size lookups are lazy by selected kind. Completed kinds make no size HEAD requests.
Unsaved video-only candidates still fetch required secondary audio metadata, even
when an independent audio save exists. Combined-video candidates do not fetch
unrelated audio sizes. Existing playback, saved-copy labels, thumbnails, queue order,
positions and listening mode are preserved.

## Artifact identity

| Field | Value |
| --- | --- |
| File | `backtube-offline-duplicate-f77bc09-1030-20261022.apk` |
| Exact build source | `f77bc096bb551edeef84c1bc07b9db1f3c0dab7e` |
| Baseline PR22 head | `eb3e92fe0f6bd2c8f5a95dc80d7516e23a081483` |
| Package | `org.schabi.newpipe.continuous.testofflinelibrary20261011` |
| versionName | `0.29.1-backtube-offline-duplicate-20261022` |
| versionCode | `1030` (previous: `1029`) |
| Bytes | `11548132` |
| APK SHA256 | `3497a90217015429e88148e236ae1770eb9ad8f219ed954b8c985193d82d70d8` |
| Signer certificate SHA256 | `4fc26294d54b6c9adb276b0b3aab38592077fe28c9f3c64d93da5d9ff6bb5bbc` |

Built from a clean isolated checkout with the existing signing input. No new key,
authentication or charging configuration. Signature and 16 KiB zip alignment verify;
package, permissions and minimum SDK compatibility match APK1029. Full GPL text and
component notices remain packaged, and the 12 source icon assets are unchanged.
Targeted credential filename/private-key/token-prefix checks found no indicators;
this is not a complete security audit. No credential contents or values were read.

Corresponding source is available at [the exact build commit](https://github.com/wnswlfhvkr-code/backtube/tree/f77bc096bb551edeef84c1bc07b9db1f3c0dab7e).
Later evidence/document commits are not the APK build source. The Library delivery
is tracked privately; private Library identifiers are not included in this public
record. Use the APK as a normal update to the same package, without uninstalling or
clearing application data. It remains an acceptance candidate.

## Verification

- App unit tests: **560/560**, 90 suites, zero failures/errors/skips; shared JVM:
  **14/14**, 9 suites. This includes 15 new cases for entry, confirmation, state,
  media-kind identity, readability, secondary audio and concurrent completion.
- TDD reproduced missing feedback and unnecessary size requests at the existing
  boundaries, unreadable-file completion, and the secondary-audio regression before
  correction. Independent read-only review has no remaining concrete findings.
- Checkstyle, Android test compilation, debug/test APK assembly and continuous APK
  assembly pass. Android lint: **0 errors, 346 warnings**, same count as APK1029.
- Cloud API23, software rendering, radios disabled: **10/10** instrumented checks.
  Two use the real button, selector and Android short Toast: 20 rapid taps on a
  completed video produce feedback without another selector/save, and saved audio
  allows the video selector while confirming the completed audio only shows feedback.
  Korean resource text and owned bytes/state are checked.
- Five thumbnail/shelf tests preserve distinct covers/labels, row order, cached and
  local-frame artwork, safe fallback and nonblocking loading. Three generated
  VP8/Vorbis WebM tests verify actual video frames plus audio, mixed-queue transition,
  audio background playback, listening-mode switching and position/volume/timer state
  without extractor network requests. These instrumented flows use the debug variant
  built from the identical product source.
- Actual minified **1029 → 1030** installation with `-r`, without granting new runtime
  permissions, preserves a generated owned WebM and seeded valid legacy-format READY
  metadata byte for byte; the new version and saved-library launch are confirmed.
  This is an update preservation check, not a normal import/network download test.

Machine-readable scoped evidence: [summary.json](offline-duplicate-2026-10-22/summary.json).

## Remaining limits

Physical-phone acceptance is **NOTRUN**. H264 compatibility was not revalidated;
the prior cloud-image decoder limitation remains. Real provider downloads/DASH mux,
normal import flow and the complete task-removal/process-death matrix were not run
for this change. No formal release or main merge is authorized by these results.
