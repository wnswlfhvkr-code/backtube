# Issues #12/#13: saved playlist duration and retired Trending

Issues: https://github.com/wnswlfhvkr-code/backtube/issues/12 and
https://github.com/wnswlfhvkr-code/backtube/issues/13.
Reviewed product source: `6e2da616374a58087998540d6e5d801f010599cd`.
Base: `28074c9dc92c2a3fe9343befc7d5d4deca6bac79`.

## Ownership and scope

All eleven existing worktrees were clean at start; no active source agents or task
build wrappers were found. PR7 remote matched the base. The existing isolated
source worktree was reused on `fix/playlist-duration-trending-20261015`, preserving
earlier branches and APK worktrees. One GPT-6.1 Sol High implementer owned source
writes, and a separate GPT-6.1 Sol High reviewer approved specification compliance
and code quality with no unresolved product findings. Cause, impact, intended
minimal changes and genuine RED results were reported before production edits.

The user reports APK1022's physical core #8/#9/#10/#11 regressions GREEN. Those
results apply to APK1022; new changes have no device acceptance yet. This source
task performed no device/adb operations or installations. Windows QA retains sole
device ownership. User media, database contents, titles, URLs, screenshots and
private logs were not inspected or copied here. Generated test fixtures only.

## #12: exact matching source mechanism

OfflineStore.Entry has no duration metadata. Single and all saved queues previously
constructed StreamInfoItem with its default duration `-1`. PlayQueueItem copied
that value into immutable queue metadata, and PlaylistDialog/StreamEntity passed
it into new playlist rows through the normal DAO upsert path. Independent playback
can discover a 287-second timeline without repairing this queue/entity field.

The local playlist header summed every duration and marked the sum complete.
For the issue values, `635 + (-1) = 634 seconds`, exactly `10:34`. The missing saved
row duration follows from that unknown entity value. This confirms the source
mechanism matching the observation; the physical database value and stack were
not measured here.

Saved queues now obtain optional seconds from readable owned media metadata using
MediaMetadataRetriever on existing IO paths. Release is performed in finally for
the supported Android versions. Missing, unsupported or malformed metadata stays
unknown; no original network URL is probed. Existing sparse internal saved rows
are hydrated in memory on IO before main-thread delivery. Existing positive
metadata, identity and order are retained. No DB rewrite, schema/data migration,
file deletion or reset is needed.

The header sums positive known seconds only and explicitly labels unknown/zero/
live durations as incomplete in English/Korean, alongside the existing `+` format.
The known fixture 635+287 yields15:22; an unavailable saved duration is identified
as unknown rather than subtracted or presented as a complete total.

## #13: retired legacy route, not a proven HTTP outage

The actual cached v0.26.5 extractor jar has SHA256
`923bf0a60938d570ad176ed7b08fa9fec7d0772d7a386ca89a2dd3b9212979c7`.
Its YoutubeService registers live/category kiosks and the legacy `Trending` id.
Drawer selection maps that id directly; no wrong-id app routing was found.
The legacy extractor requests `FEtrending` and parses a general trending header/tab.

[Tagged upstream service source](https://raw.githubusercontent.com/TeamNewPipe/NewPipeExtractor/v0.26.5/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/YoutubeService.java)
marks the general route deprecated since2025-07-21. Current
[official YouTube help](https://support.google.com/youtube/answer/7239739?hl=en)
describes category-specific Charts separately. The cached jar and production route
were inspected, not inferred solely from a dependency version label.

Exact physical HTTP status/parser stack and content-country preference remain
unobserved. Credential-free public GET diagnostics in this execution environment
returned URLError without an HTTP status/body; this is not evidence of a provider
HTTP response or global outage. Same-execution search/playlist success from QA also
does not prove the legacy kiosk's exact failure type.

The app now recognizes only YouTube plus exact legacy Trending before extraction.
A dedicated unsupported-feature state provides localized retirement and search/
category-menu guidance. Existing error policy suppresses futile retry/report;
this state also suppresses the obsolete browser action. Normal parsing/network
errors and other providers/category routes retain their behavior. No extractor
upgrade, authentication, paid API or silent provider/content fallback was added.

## RED/GREEN and independent review

Fresh baseline:432 JVM tests in65 suites, zero failures/errors/skips. Before
production edits, eight focused tests had six genuine assertion/comparison
failures and two controls: saved single queue/entity, all queue, sparse fragment
loading, negative/zero unknown aggregate and actual legacy kiosk load path.
Earlier import/style/incomplete fixture failures were excluded from bug RED.

Final focused coverage:12 passing tests, including IO deferral, unavailable or
malformed metadata, no DB manager writes/original-URL resolution, known and
explicitly incomplete totals, exact route controls, and real ErrorPanelHelper
reuse restoring normal report/retry/browser actions after the retired state.
Two Android native-metadata tests use generated WAV/invalid cache files; they
compile but were not executed.

Final required command:

```text
./gradlew --offline --console=plain -DskipFormatKtlint -PappFaultEndpoint= \
  --max-workers=2 :app:testDebugUnitTest :app:compileDebugAndroidTestJavaWithJavac \
  :app:runCheckstyle :app:runKtlint :app:checkDependenciesOrder :app:lintDebug \
  :app:compileDebugSources
```

Final exit0 / BUILD SUCCESSFUL,24.867s. Generated XML:69 suites/**444 tests**,
zero failures/errors/skips. Checkstyle0 issues; Ktlint/dependency ordering and
source/Android-test Java compilation passed. Lint XML: **0 errors/321 warnings**.
An earlier full run had322 warnings because of an ignored finite test subscription;
retaining/disposing it removed that warning without weakening assertions. The
final full run and scoped independent re-review passed. `git diff --check` passed.

The original432 source regressions remain included. Product source is descended
from the verified #8, #9 and #10/#11 commits. No main merge, deployment, PR7 body
edit, Claude execution, new key/authentication, permission or paid setting change.

## Remaining acceptance

Issues remain open. On the exact new APK, Windows QA should verify the retained
mixed playlist's saved row duration/header, adding saved single/all queue entries,
unknown metadata fallback and reorder/preservation. Verify the legacy Trending
notice, available search/category navigation, and ordinary error/retry behavior.
Native metadata codecs and rendered/device accessibility remain unexecuted here.
No source-test or APK signature result implies physical success.


## APK1023 candidate

The exact reviewed product source above was built from a clean separate worktree
with the existing signing input only. Build exit0,269.186s; original-signing guard
and signing-input identity preservation passed. No new key was generated, read for
inspection, copied or exported. Signing uses the existing key normally.

- File: `backtube-playlist-trending-fix-6e2da61-1023-20261015.apk`
- Version: `0.29.1-playlist-trending-fix-20261015`, code **1023**
- Package: `org.schabi.newpipe.continuous.testofflinelibrary20261011`
- Size: **11629373 bytes**
- APK SHA256: `444302ab291a556011bd11d0a2371a6c9d0c6e6fc2129418e6716cc89db52791`
- Public certificate SHA256: `4fc26294d54b6c9adb276b0b3aab38592077fe28c9f3c64d93da5d9ff6bb5bbc`
- R8 Mapping ID: `e51480e8a58c2227f89f08a9fd523020301603832a59fdfa13fe833995a91ddd`

Compared with retained APK1022: same valid signer/package/permissions, version code
increases1022→1023, minimum SDK not raised,16KiB alignment passes, and retained
APK1022 still matches its recorded hash. Prior source/APK worktrees were preserved.
The APK was saved to Library; its private delivery identifiers remain in the local
delivery record, not this public repository. No installation/update was performed.

This later evidence commit changes documentation only. The APK contains product
source `6e2da616374a58087998540d6e5d801f010599cd`, not an unverified later implementation. APK build/signature
success is distinct from physical/native-codec/UI acceptance. Both issues stay open.
