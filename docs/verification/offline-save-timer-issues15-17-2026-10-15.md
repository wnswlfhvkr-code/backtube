# Issues 15–17: saved-copy deduplication, playback notice and timer input

Issues: [15](https://github.com/wnswlfhvkr-code/backtube/issues/15),
[16](https://github.com/wnswlfhvkr-code/backtube/issues/16),
[17](https://github.com/wnswlfhvkr-code/backtube/issues/17).
Reviewed product source: `2aad3ccc10783a035bdfcfb6c04bff2771d64a88`. Base: `304c98594c22f503d051f921b2684ee7c509ced0`.
The base retains #14 source `52e23e539a6edd20ca157d3991e829dc8f2d16d4` and APK1024.

## Ownership, causes and resulting behavior

All 13 existing worktrees were clean at start, no competing source/build wrappers
were running, and PR7/fix14 remote refs matched the base. The source worktree was
reused on a new isolated branch; earlier branches/APKs remain. One GPT-6.1 Sol High
writer owned source changes. A separate GPT-6.1 Sol High reviewer assessed the
whole change and performed a scoped re-review of concrete lifecycle corrections.
No user media, private database, screenshot or credential was inspected here.

### 15: duplicate registration, copy and download ownership

OfflineStore synchronized registration but always allocated a new UUID; pending
copies were guarded only per UUID. DownloadManager could allocate another mission
for the same owned output. Reusing a UUID alone was insufficient: the dialog's
common writable path could truncate complete bytes before manager rejection.

Registration now matches explicit service, content identity and MIME family.
Audio and video remain separate variants; format/quality within one family does
not allocate another saved copy. Unknown MIME retains its complete MIME value.
Owned internal/file identities resolve through metadata with cycle protection;
empty origins use a nonempty local source identity. Existing READY records take
precedence over active and then failed matches. Existing duplicate records, bytes
and shelf order are retained; no automatic dedup cleanup, reset or migration is
introduced. Unknown meaningful query strings are preserved.

Audited pure YouTube/PeerTube/MediaCCC link factories provide canonical URLs,
including provider instance-host scope. SoundCloud/Bandcamp factories can perform
network resolution, so they and unknown providers use the complete caller-supplied
canonical StreamInfo origin/local source. Arbitrary raw aliases on those providers
may remain distinct. This bounds equivalence conservatively instead of merging
unproven identities or requesting the network under the store lock. Factory
regressions use a mocked downloader; no live media request was made.

READY duplicates bypass estimated-size capacity checks and never acquire writable
ownership. A transient atomic dispatch claim is taken before writable inspection,
truncation or service dispatch, with manager ownership checked around acquisition.
The synchronized manager pending list rejects duplicate/stale same-id scheduling
before metadata allocation and owns accepted work. Imports retain a per-id copy
owner. Failures/cancellation/reselection reuse the registration rather than adding
another UUID; failed local source reselection uses the newly selected source.
Stale retries cannot reset READY or take over a handed-off download owner.

The independent review required two additional corrections: safe managed keep-file
cancellation while a transfer/postprocessor may still write, and a failed/imported
copy handing off to the user's new online-save request only when no copy owner is
live. Managed removal is rejected during postprocessing; otherwise it requests
pause and retains pending ownership, storage and metadata while an initializer or
transfer thread is alive. A retained owner gets its row and busy feedback back;
the user can retry removal after the worker stops. The initializer reference also
remains visible through its transition into transfer workers. No main-thread join
or Serializable mission/helper API change is introduced. FAILED/INTERRUPTED local
imports hand off on the same id only after both queued and running copier jobs
have released ownership; the network-save route does not restart the old source.
These corrections have production-boundary RED/GREEN and scoped-review evidence.
No existing READY media or legacy duplicate is deleted by these corrections.
Replacement initializers publish ownership before starting. Partial initialization
uses a detached in-memory copy for persistence, resetting only that copy to a safe
uninitialized state while retaining current queue/error values. Live blocks/thread
references are not changed. A process restart performs preparation again and keeps
an explicit pause paused. Prepared transfer progress continues to persist and
restore normally. Serialization format and mission/helper API shape remain.

### 16: narrow single-shelf success notice

Only the actual individual saved-shelf selection suppresses the ordinary
background-playing success Toast. Play all, generic background actions and saved
history routes keep their feedback. The foreground service, AUDIO mode,
fresh-selection/resume/play-when-ready flags, mandatory media notifications and
error notices remain. No #14 player/session/timestamp policy is replaced.

### 17: timer duration keyboard UI

English/Korean visible timer names remove Sleep/취침. Direct input is first,
followed by the same 15/30/60/90/120-minute presets and end-item/extend/cancel.
Custom input uses two numeric-keyboard fields with concise hours/minutes units,
not a clock time or scroll picker. Hours may be blank (zero), with no optional
wording; blank minutes likewise allow an hours-only duration. Both blank/zero
are invalid. Minutes must be 0–59 and total duration 1–1440 minutes (24 hours).
Default remains 0h30m. Text, negatives, overflow and excess values are rejected
before timer start or dialog dismissal; invalid paste is not stripped into a
valid number. Invalid input leaves an existing timer unchanged.

Core timer/end-item/extend/fade/volume restoration and lifecycle behavior remain.
ScrollView, resize soft-input mode and IME actions support the keyboard layout.
Actual keyboard/button visibility is unexecuted here and remains Windows QA.

## Regression evidence and independent review

Baseline:472 JVM tests/72 suites, zero failures/errors,35.731s. Genuine RED phases
reported before the corresponding corrections included:

- Initial7 tests/6 assertion failures/0 errors: concurrent/canonical/READY/import
  registration identities, duplicate manager metadata, the real shelf Toast path,
  and the actual custom dialog constructing zero keyboard fields instead of two.
- Additional3 tests/3 assertions/0 errors: generated READY bytes3→0 at the writable
  boundary, failed retry allocating a new UUID, and two import jobs instead of one.
- Individual stale READY retry, cancel metadata, provider purity and failed source
  reselection regressions, plus focused regressions for review findings P1/P2.

Phase totals overlap and are not additive. Mock/compiler/style setup failures
were excluded. Full-run order fixtures originally reused one local URI for
independent recordings; only generated source identity was changed, with every
ordering assertion retained. This was reviewed independently.

Final required checks: **512 tests / 80 suites**,
zero failures/errors/skips; Android-test Java compilation, debug source compile,
Checkstyle, Ktlint, dependency ordering and lint passed. Gradle exit0 in
99.372s. Checkstyle0; lint0 errors/323 warnings.
The inherited #8–14 regression suites remain included. Independent specification
and code-quality verdicts were accepted before the source commit.

Android tests cover generated saved playback plus actual timer field construction,
custom-first/preset mapping, invalid paste, numeric input, resize/viewport and
valid apply. They compiled but did not execute. No JVM/resource assertion or
compiled Android test is a physical rendering/playback result.

## APK1025 candidate

- File: `backtube-offline-timer-fix-2aad3cc-1025-20261015.apk`
- Version: `0.29.1-offline-timer-fix-20261015`, code **1025**
- Product source: `2aad3ccc10783a035bdfcfb6c04bff2771d64a88`
- Package: `org.schabi.newpipe.continuous.testofflinelibrary20261011`
- Size: **11624130 bytes**
- SHA-256: `3c25699ac29d084659a569459eb5df4cefb71ceb6f9b4dfad36e8921c8e3c599`
- Public signer certificate SHA-256: `4fc26294d54b6c9adb276b0b3aab38592077fe28c9f3c64d93da5d9ff6bb5bbc`
- R8 mapping ID: `e6beb8e5d93e319d7770787ff945ab72d4dd277b66666366a8bc30866202feea`

Build exit0 in390.591s from a clean separate worktree pinned to the
accepted product commit. Existing signer guard and signing-input identity checks
passed. No signing key was generated, inspected for contents, copied or exported.
Compared with retained APK1024: same valid signer/package/permissions, increased
versionCode, minimum SDK not raised and16KiB alignment verified. APK1024 still
matches its recorded hash; original source/APK worktrees were preserved.
The APK was saved to Library; private delivery/file IDs stay in local delivery
records, rather than this public repository. This later evidence commit contains
no further product changes.

## Remaining Windows QA acceptance

Install/update this exact APK without clearing data. Check repeated/rapid saving
of completed and pending content and URL variants; independent audio/video saves;
failed/cancelled import/download retry and source handoff; preservation of prior
duplicates, files and shelf order; single-shelf silence with actual playback and
media notification, Play all/error feedback; #14 fresh0/session restore; and timer
custom-first, hours-only/minutes-only, invalid/24h boundaries, presets, cancellation,
remaining time and fade/volume restoration. Check keyboard/apply visibility on a
small screen and with large font/accessibility settings.

No device/adb access, installation, new auth/permission/paid setting, main merge
or deployment occurred here. Issues stay open until exact-APK physical acceptance.
