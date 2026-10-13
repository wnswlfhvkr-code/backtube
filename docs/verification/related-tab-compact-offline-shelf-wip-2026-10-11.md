# Related default tab and compact saved library — resume record

**Latest audit (2026-10-13):** Product source remains
`f82d5e0d6044f0e318c4c7d62ed6c6e3ac4d48f8`. The parent reports APK 1018,
`0.29.1-offline-ui-test-20261012`, already delivered. Its official Library metadata
was found, but the official download helper returned HTTP 401 before bytes were
obtained. This VM has not reverified that APK's producer SHA, digest, public signer
or signed-production build receipt. Those facts must come from the existing
production evidence; the filename alone is insufficient. Delivery remains reported
complete, while this provenance recheck is partial. See the dated audit below.
No build/test rerun, installation, ADB/authentication action or new APK is part of
this audit. The following 2026-10-11 sections are historical records.

**Latest scope (2026-10-11, follow-up):** Starting from clean
`4f8c53f39be157f28e8e9fffd003ee087be63409`, fix the existing debug 4/release 3
lint errors and eight compact-shelf warnings with narrow correctness/accessibility
changes and stronger regressions. No concurrent writer was found (old git/Java
processes are zombies). Fresh baseline lint reproduced debug 4 errors/373 warnings
and release 3 errors/374 warnings. Narrow fixes passed 358 JVM tests, Checkstyle,
instrumentation compilation and an unsigned release build. Final verification of
the corrected real About host registration and strengthened instrumentation tests
passed: debug/release lint each has 0 errors and 366 existing warnings, with no
new issue against the same-environment baseline. Source publication follows on
the existing branch, with exact remote HEAD/tree reported in the parent handoff.
The parent reports that the compatible signed version 1017 APK at source `4f8c53f`
was already built in the original existing signing environment and delivered.
That delivery remains complete; this follow-up returns source verification and
does not create keys, transfer keys, inspect credentials or deliver unsigned APKs.
Actual Android execution in this environment has been 0; check current availability
without bypassing the previously observed read-only ADB home or missing KVM.
Claude use/install/CLI/auth checks, resets, uninstall, merge, deployment, new public
release, paid APIs and PR-body edits remain prohibited. Preserve the approved UI.

The earlier integration/delivery sections below are historical environment-local
results, including the local signing limitation subsequently resolved by the parent.

The original `c9fe248` incomplete checkpoint is retained below as history. Its
preservation-turn stop instruction was superseded by the user's explicit request
to complete these approved changes in the current consuming environment.

## Source and scope

- Branch: `feat/claude-auto-report`, existing Draft PR7.
- Base before this checkpoint: `885dda9953c17d390f37f58bba01f0141942d270`.
- Existing worktree: `/workspace/backtube-auto-report-20261008`.
- Local preservation record:
  `/workspace/scratch/backtube-player-tabs-20261011/checkpoint-preserve-pao8_1we/CHECKPOINT.md`.
  That directory also contains all ten changed source files, the tracked patch,
  a manifest and copies of focused test evidence. Copies matched the live files
  before this checkpoint commit.

Only the approved ten product/test/resource files and this resume record are part
of the checkpoint. No screenshots, credentials, caches, generated APKs or user
data belong in this commit. Main, other worktrees and PR body are unchanged.

## Related items default tab

`VideoDetailFragment.readSelectedTab` defaults a missing selection preference to
Related items. Stored Comments, Related items and Description preferences retain
their values; existing tab restoration remains intact.

There is no provenance marker distinguishing a deliberate stored Comments choice
from the former default automatically saved by `onPause`. Do not reset or migrate
those preferences. An existing user can enable **Settings > Content > Show 'Next'
and 'Similar' videos**, select **Related items** in playback detail and background
the app once to persist that selection. The Related tab is omitted when that
content option is disabled or the layout uses a separate related-items pane.

## Compact saved-library groundwork: incomplete

The approved UI uses compact thumbnail/title/secondary-status rows, row tap for
playback, a separate right drag handle, delete in the overflow menu, collapsed
capacity/management settings and Play all in the header. Reuse the existing play
queue row pattern with a local placeholder, without fetching online thumbnails.
An active playback queue remains a snapshot; shelf changes affect the next Play
all action.

Written but not yet fully integrated:

- `OfflineShelfOrder`: stable-ID drag preview, drop-time atomic persistence,
  cancellation/failed-write rollback and accessible move alternatives. It retains
  concurrently saved entries and does not alter active playback queues.
- `OfflineShelfAdapter`: compact row binding, local-URL current-track highlight,
  separate play/menu/handle targets, binding guards and accessibility Up/Down
  actions. It was written after the last focused model test and has not been
  compiled.
- `offline_shelf_item.xml`, English/Korean strings and menu IDs.
- `OfflineShelfOrderTest` and the updated instrumentation test source.

**Resume at `OfflineLibraryActivity.java`.** It still uses `LinearLayout` rows and
the old cards. No RecyclerView, adapter or drag-handler integration has been made.

Next implementation work should connect the compact header and collapsed panel,
RecyclerView/adapter listeners, handle-only drag with swipe deletion disabled,
drop-time persistence and failure rollback, stale-refresh protection, accessible
menu actions and passive current-player metadata updates. Preserve existing
download, deletion, quota and local-only playback semantics. Cancel uncommitted
drag when leaving the screen; keep active playback queue snapshots unchanged.

## Verification limits

Previous focused test records were inspected again for this handoff:

- Selected tab preference: RED 4 tests with 1 expected failure; GREEN 4 tests,
  0 failures and 0 errors.
- Shelf order model: initial RED was an absent-class/API compilation failure;
  GREEN 9 tests, 0 failures, 0 errors and 0 skipped tests, Gradle exit 0.

These records establish the focused tab/model checks only. They do not verify the
adapter or complete UI. No tests or builds were rerun for this checkpoint.

Still pending: Activity integration, compilation of the adapter/resources and
instrumentation tests, appropriate full JVM checks and Checkstyle, independent
review, physical UI/runtime checks and a new combined APK. There is no new APK
for these changes. Existing device/emulator infrastructure remains blocked;
do not claim instrumentation execution or work around its permission boundaries.

Validate drag persistence/restart order and rollback, current-track identity,
row/handle/menu misclick prevention, accessible movement, empty/missing/deleted
files, local-only queue behavior and unchanged active queue snapshots.

## Handoff boundaries

Claude remains paused. Do not execute Claude CLI, authentication, subscription,
OAuth, login, token issuance or model requests. Do not inspect credentials or
environment values. Use the approved Codex model for resumed implementation.

Preserve user data and all existing changes. Do not reset, overwrite, uninstall,
clear app data, introduce paid providers/permissions, merge main or deploy.
PR body editing remains excluded. After the parent resumes implementation, retain
the existing parallel-test package and existing signer for the combined APK and
deliver through official Library tools. This preservation turn ends after push
and remote-HEAD confirmation; do not start further implementation here.

## Active resume — 2026-10-11 (current consuming environment)

- Started from remote HEAD `c9fe248fed153fc4c7dd8f9597c85d41da44442c` on
  `feat/claude-auto-report` in `/workspace/backtube`. Initial environment checkout
  was clean `work` at `84b4e0e85`; fetched the requested branch and switched without
  overwriting local edits. No other writing/build process was running.
- No checkout-specific AGENTS.md or .agents skills were present in this checkout.
- Both required Library images were officially materialized in this environment
  and inspected as pixels: `45079.png` (213566 bytes) and `45077.png` (200180 bytes),
  under `/workspace/library-files/backtube-ui/`. Old cards versus compact queue
  rows/placeholder/secondary information/right handles/gray current track confirmed.
- Scope remains the approved default Related tab and compact saved shelf. Activity
  integration is in progress. Existing WIP source files are preserved. No claims
  of new full JVM/build/UI success yet; historical 338 tests are not this result.
- Current ADB invocation failed: `/home/agent/.android` is read-only. No retry with
  expanded permissions or relocated home was attempted. Emulator binary and
  `/dev/kvm` are absent. Actual Android UI/drag/playback executions so far: **0**.
- Required update identity: `org.schabi.newpipe.continuous.testofflinelibrary20261011`,
  previous code 1016; expected public certificate SHA256
  `4fc26294d54b6c9adb276b0b3aab38592077fe28c9f3c64d93da5d9ff6bb5bbc`.
  Use only the existing Gradle signing workflow, compare the resulting APK's public
  certificate; do not read/copy/output/generate keys or fetch the previous key.
- Claude use/install/CLI/auth/OAuth/subscription/token checks are forbidden. No
  credential inspection, paid API, main merge, deployment, public release, PR-body
  changes, app uninstall or data reset. Push only approved source/test/resources
  and this resume record to the existing branch after verification.

### Current implementation and verified checks

Activity integration is now written: compact RecyclerView rows, Play all/count
header, initially collapsed storage/settings, local placeholder thumbnails,
READY-row playback, separate drag handle and track popup menu (delete/retry/pause
and accessible Up/Down). No online thumbnail lookup was added. Download/import,
quota, deletion and existing offline-only queue behavior are retained.

A drag previews the UUID order; an immutable Move performs one atomic store write
on drop. Pending commits ignore old refreshes; Activity revision guards discard
stale async reads. Failed writes and cancellations restore the committed preview;
leaving the screen dispatches cancellation before helper detachment. Existing
player metadata is polled through a non-creating service bind without replacing
listeners or changing its queue. Later Play all reads the newly committed shelf.

Independent read-only GPT-6.1 Sol / High review found two Important issues in the
initial integration: stale recovery callbacks could finish a newer drag; helper
detach could retain selection/auto-scroll. Both were corrected (holder token
retained through recovery and ACTION_CANCEL before detach) and re-reviewed with
no remaining Critical/Important findings. Runtime timing still needs a device.

Fresh checks in this consuming environment:

- Meaningful RED: direct JDK execution of the real OfflineStore/OfflineShelfOrder
  reproduced `Stale refresh replaced pending drop preview`; the same probe passed
  after the pending-commit guard. A matching permanent JVM regression was added.
- Focused OfflineShelfOrder: 10 tests, 0 failures/errors/skips, Gradle exit 0.
- Entire Android app JVM suite: **352 tests, 0 failures, 0 errors, 0 skipped**, 51
  suites, Gradle exit 0. This includes the 4 selected-tab preference tests and 10
  shelf-order tests. Historical 338-test success was not substituted for this run.
- Checkstyle XML: **0 violations**. Initial integration introduced 3 line-length
  errors and 1 method-length warning; corrected before the passing run.
- Kotlin lint passed; Android instrumentation test Java/Kotlin/resources compiled.
  Added pointer-input cancellation/drop, durable reopening, unchanged queue
  snapshot and non-overlapping compact touch-target assertions. Execution: **0**.
- Gradle compile setup uses the environment's preinstalled JDK/SDK/Gradle and its
  existing Android SDK user directory. No home-directory write permission or ADB
  permission was expanded. ADB was not retried after its read-only-home failure.

Related-tab conditions were checked against the actual source and layouts:
missing preference defaults to Related; saved Comments/Related/Description stay
unchanged. `initTabs` only adds Related when `showRelatedItems` is true and
`binding.relatedItemsLayout == null`. Phone layout has no separate pane; the
large-land layout has it. OFF/separate-pane layouts use the remaining tab(s), or
the existing empty fallback. These are source/layout checks, not device execution.
To save Related on an existing installation: enable Settings > Content > Show
'Next' and 'Similar' videos, select Related items in the phone playback detail,
then background once so `onPause` stores that actual selected page via `apply()`.
A stored former automatic Comments default cannot be distinguished from an
explicit Comments selection; no preference migration/reset was added.

### Signing blocker in this environment

The existing `continuous` variant still uses the existing Gradle `debug` signing
configuration. A task-local pre-validation guard checked only input-file presence
and failed with `EXISTING_SIGNING_INPUT_UNAVAILABLE; key generation is disabled`.
No key value was inspected, printed, copied, imported or generated, and no new
credential was requested. No previous thread's signing material was fetched.

The compatible-update route is blocked here. Build a clearly named **unsigned,
not installable** release verification artifact with the required package and
code 1017. Absence of a signer must never be described as matching the existing
certificate or as a data-preserving update. Release build/lint/artifact Library
saving and approved-branch push are the remaining steps.


### Final delivery and remaining limits

- `:app:assembleRelease :app:lintRelease` completed exit 0 in 4m30s; optimized
  release Java/resources/R8 packaging succeeded. `:app:lintDebug` also completed
  exit 0. The project's `abortOnError=false` does **not** mean clean lint.
- Debug lint: **4 unchanged baseline errors, 373 warnings**. Release lint: **3
  unchanged baseline errors, 374 warnings** (the fourth error is instrumentation
  only). All four implicated files are byte-identical to `1c92066`: manifest
  MissingClass; KeyboardUtil and PlayerService WrongConstant;
  AudioFocusOwnerActivity UnspecifiedRegisterReceiverFlag. New lint errors: **0**.
- The compact shelf has eight identifiable lint warnings: three
  NotifyDataSetChanged recommendations, four now-unused legacy card-action
  strings, and ClickableViewAccessibility on the handle's touch listener. The
  listener returns false to retain Android's normal clickable-view handling;
  it also has an explicit click action and accessible movement alternatives.
  These warnings are reported, not claimed as a clean lint result; actual
  TalkBack and gesture behavior remain unexecuted. Warning totals alone are not
  a same-environment baseline comparison with the historical 366 warnings.
- APK: `backtube-offline-ui-UNSIGNED-not-installable-1017.apk`, **11,505,235 bytes**.
  SHA256: `dcafd3ea18da3fd6760bdf3ff059889db42588d102357114e2b71cb3d1919c1f`.
  Package: `org.schabi.newpipe.continuous.testofflinelibrary20261011`.
  Version: `0.29.1-offline-ui-test-20261011`, code **1017**; min SDK 23 / target 35.
  aapt and output-metadata agree; zipalign check exited 0.
- `apksigner verify --verbose --print-certs` exited 1: `DOES NOT VERIFY`, missing
  META-INF/MANIFEST.MF. There is **no signer certificate** to match the required
  `4fc26294…bb5bbc`. This is **not installable and not a compatible update**.
  The previous installed test app and its data must be kept. Existing-key
  signing in a permitted environment is still required; no key recovery, new
  key issuance, credential inspection or signing-path workaround was performed.
- Official Library save succeeded. The exact Library ID is retained in the local
  delivery record and returned to the parent, without publishing a new APK URL.
  Local bytes after saving still match the verified size/SHA. Source-file hashes
  in the local delivery record match the tested/packaged approved source tree.
- Actual Android UI/drag/playback/TalkBack/update-install/coexistence executions:
  **0**. No emulator binary or KVM exists; the current supported ADB invocation
  failed before device enumeration. No existing app was installed over,
  uninstalled, reset or migrated.
- Existing Draft PR7 remains based on `feat/playback-feedback`; its body is not
  edited. Only the approved five product/test files and this resume record are
  to be committed/pushed on `feat/claude-auto-report`. No changes to build files,
  workflows, main, credentials, images, caches or generated APKs are committed.

Source implementation and requested local verification are finished. Remaining
blockers are existing-signature compatibility and actual Android runtime/device
verification. The unsigned Library artifact is compilation evidence only.

### Remote publication verified

The approved source/test/resume tree was published to the existing branch at
product commit `5eb18a59623fcc99e93fed5b5b9de1fa52374b07`. The remote tree SHA
`ac281af188ce37602c386b294474c3f414b3c25a` exactly matched the verified local
commit `4fa405bdccd0658830cf42456a84d3588867ba00`; all six blob SHAs matched.
The local transport commit is retained on `local/ui-source-4fa405bdc`.

Direct shell `git push` had no stored Git credentials and stopped with prompts
disabled. No authentication check/login/token retrieval was attempted. The
already connected official GitHub app uploaded the identical six blobs/tree and
fast-forwarded the existing ref only with expected HEAD `c9fe248...`. A subsequent
Git fetch confirmed the published HEAD/tree. PR7 remains Draft, its base remains
`feat/playback-feedback`, and its body is exactly unchanged.

This final documentation update records that publication; it changes no product
source. The exact final remote HEAD and private Library file ID are returned to
the parent and retained in the local delivery record. Source publication and
Library saving are complete; compatible signing and actual Android execution
remain the two blockers described above. No pipeline/signing-key generation was
triggered to work around these limits.

## Narrow lint and regression follow-up — 2026-10-11

Starting source: `4f8c53f39be157f28e8e9fffd003ee087be63409`. The approved compact
layout, local placeholder, persisted UUID order, drop-time atomic write/rollback,
active queue snapshot and local-only playback remain the implementation under test.
No new thumbnail lookup, preference migration or UI design was introduced.

The four existing debug errors (three in release) were corrected at their causes:

- Register the actual `net.newpipe.app.ComposeActivity` used by About navigation,
  replacing the nonexistent legacy activity and restoring its intended About label.
  Both final merged manifests contain exactly one actual host, `exported=false`,
  the About label and the shared manifest's inherited `adjustResize`; neither
  contains `AboutActivity`.
- Express the existing unrestricted keyboard-hide flag as the supported literal
  zero. Forced keyboard hiding on non-touch devices keeps the same flag value.
- Express the validated saved repeat mode with ExoPlayer's `RepeatMode` contract
  and named OFF/ONE/ALL constants. Malformed persisted modes remain rejected.
- Register the token-checked, separately installed audio-focus fixture through
  `ContextCompat` with EXPORTED explicitly on all supported Android versions.
  Its existing cross-UID test control is retained; it is instrumentation-only.

The eight compact warnings across variants were resolved without suppression or
resource/test deletion. Three full adapter invalidations now dispatch synchronous
DiffUtil changes using saved UUID identity and copied pre-mutation order. Retained
rows rebind because download progress is external to Entry and positional move
accessibility actions need current positions. Drag preview still emits its existing
move events; refused refreshes during a pending write produce no update.
Four existing strings now label playback, positional movement and deletion
accessibility actions. The handle consumes touch events and explicitly performs
click only for a sequence that never started a drag; its accepted-drag latch is
installed once so rebinding/clearView timing cannot turn a drag into a menu click.
Keyboard/accessibility activation retains the movement menu.

Regression additions cover duplicate-title identity mapping, cancel restoration,
empty removal/insertion, live-status rebinding, valid/invalid saved repeat modes and
local queue scope. The four Related preference tests additionally verify that
reading any choice writes no preference or other setting. The older Android
playback test now waits for the generated import to be READY, scrolls its appended
virtualized row into view, and plays the exact saved UUID instead of searching for
the removed card button. Android source also checks the labeled playback action.

TDD evidence is bounded: fresh lint reproduced the requested failing baseline
before source edits, then the final reports cleared those errors/warnings. The
first attempted JVM adapter-observer test hit the Android mockable Observable
stub (`mObservers` null); that is an environment/test-harness failure, not a
reproduced product failure. Its replacement exercises real DiffUtil update events
without Android Observable and passes. No pre-existing tests were removed, and no
runtime RED/Android gesture execution is claimed for this follow-up.

Fresh final evidence in `/workspace/scratch/backtube-lint-followup`:

| Check | Result |
| --- | --- |
| App JVM | 358 tests / 52 suites; 0 failures, errors or skips |
| Checkstyle | 0 violations |
| Kotlin lint | successful/up-to-date; no Kotlin changed |
| Debug Android test Java/Kotlin/resources | compiled; not executed |
| Debug lint | 4 errors/373 warnings → 0 errors/366 warnings |
| Release lint | 3 errors/374 warnings → 0 errors/366 warnings |
| Lint new-issue comparison | 0 added issues in each variant |
| Final unsigned release build | successful; final corrected combined run 3m50s |
| Independent source review | no remaining Critical/Important finding |
| Actual Android UI/drag/playback runs | **0** |

Debug's old instrumentation reference already counted the playback string as used,
so seven warning instances disappear there; release removes all eight compact
warning instances. The same eight-warning scope across variants is resolved.
The remaining 366 warnings are pre-existing; lint is not globally warning-clean.
The transient About unused-resource warning from removing its old declaration was
eliminated by registering the actual host, without fake references or suppression.

The allowed current ADB attempt again failed before enumeration (exit 134):
`Cannot mkdir '/home/agent/.android': Read-only file system`. The current SDK has no
emulator executable, and `/dev/kvm` is absent. No home, permission or key workaround
was attempted. Gesture timing, TalkBack, rendering and ExoPlayer device behavior
remain unexecuted. The Related ON/OFF and separate-panel conditions were checked
in source and XML; phone has no separate pane, `layout-large-land` has one.
Existing installations still need Related visible, select it once on playback
detail, then background the app to trigger `onPause` persistence. Explicit prior
Comments and automatically stored old Comments remain indistinguishable, so no
setting is reset or migrated.

The parent confirms compatible signed APK version 1017 at source `4f8c53f` was
already delivered from the original existing signing environment. That is complete
and is not replaced by this follow-up's unsigned build. This task delivers source
verification; the parent coordinates any later compatible signing/update. No new
installable APK, new key, credential check, key transfer, Library upload, app
uninstall/reset, merge, deployment, public release or PR-body edit was performed.

## Planning, implementation and delivery provenance audit — 2026-10-13

The initial checkout was clean at product HEAD
`f82d5e0d6044f0e318c4c7d62ed6c6e3ac4d48f8`, tree
`7628bf43f9f1ac54e44865f42cbb786b65df4a92`. No other writing/build process was
present. Public branch fetch and PR7 metadata read-back confirmed that exact HEAD,
Draft status and stack base `feat/playback-feedback`. All twelve source/test/doc
hashes in the retained source handoff matched the checkout. This is a provenance
comparison, not a repeat of the earlier tests or builds.

### Requirements and acceptance boundaries

| Approved requirement | Actual implementation at product HEAD | Existing verification | Remaining Android acceptance |
| --- | --- | --- | --- |
| Related is the new default; preserve existing settings | Missing selection defaults to Related; stored choices retain their value. `onPause` saves the current page. Related exists only when enabled and no separate pane is present. | Four preference tests include no-write checks; phone and large-landscape XML/source guards were inspected. | Real playback-screen selection/persistence, OFF behavior and separate-panel behavior remain device-unexecuted. |
| Playlist-like compact saved rows and local thumbnails | RecyclerView rows have local placeholder, title/status, row playback, handle, overflow deletion, collapsed management and top Play all. No online thumbnail lookup was added. | Resources and instrumentation source compiled; compact/separate-target assertions exist. | Narrow screens and large text must remain legible without overlapping or clipping targets. Existing default-font height assertions do not establish large-font acceptance. |
| Durable drag order with safe failure/cancel | UUID preview; drop-only atomic persistence; stale refresh guard; failure/cancel restores committed order. | Order model and identity/diff regressions are part of the retained 358 JVM results; pointer instrumentation compiled. | Actual drag/recovery timing, rapid repeat gestures, pause/rotation and disabled animations remain unexecuted. |
| Accessible reordering and safe row/handle/menu activation | Up/Down actions, positional labels, labeled playback/delete, separate targets and a per-touch accepted-drag latch. | Identity mapping/model tests; accessibility/activation assertions compiled. | TalkBack focus order, spoken labels/actions and physical touch mis-inputs require device acceptance. |
| Local-only queue; preserve current snapshot and handle deleted/missing files | Existing OfflinePlayQueue validates local saves. Running queue keeps a snapshot; next Play all uses the new shelf order. Current row uses local URL identity. | Local queue/store/session regressions remain in the retained JVM evidence. | ExoPlayer playback, actual file disappearance/deletion, open-reader completion and current-track UI timing remain unexecuted. |

The retained source validation is 358 JVM tests/52 suites with zero failures,
errors or skips; Checkstyle zero; debug/release lint zero errors and 366 existing
warnings each; instrumentation compilation and unsigned release packaging.
The retained corrected combined build log ends `BUILD SUCCESSFUL in 3m 50s`.
The source hashes and recorded log were read again for this audit; no Gradle,
Android, emulator, ADB or installer was run. Actual Android UI/drag/ExoPlayer runs
remain **0**. Old PR-body 338-test/1016 values describe older work and are not this
source's validation. The PR body remains outside the authorized edit scope.

### APK 1018 evidence receipt

| Fact | Evidence and status in this audit |
| --- | --- |
| Delivery/version | Parent reports delivered code 1018, version `0.29.1-offline-ui-test-20261012`. This preserves the delivery status; version bytes were not read here. |
| Official Library filename | `backtube-offline-ui-parallel-test-f82d5e0-20261012.apk` |
| Library-reported size | 11,627,681 bytes; file identity is retained privately in the local audit receipt. |
| Actual producing source SHA | Not reverified here. The `f82d5e0` filename label is a retrieval hint, not a production source attestation. |
| APK SHA-256/public signer/package | Not reverified here; do not substitute the unsigned 1017 digest, 1016 certificate evidence or expected package for a 1018 binary verification. |
| Signed production build receipt | Not available in this checkout. The retained `f82d5e0` unsigned release build is source-validation evidence, not proof of the delivered signed APK's production. |
| Current byte-read blocker | Official Library helper failed with HTTP 401 during hosted-apps tool discovery, before transfer. No authentication retry, credential inspection, direct URL transfer or alternative download route was attempted. |

The existing APK production receipt was requested to resolve those missing facts;
no new signing or production was requested. Once that receipt identifies the real
producer SHA, compare it to the pinned product HEAD above and preserve the actual
relationship. A future documentation-only audit commit does not imply new app
source or a newly built APK. Do not claim a match from an APK filename, version
string or passing JVM tests alone.

Signed APK 1017 at source `4f8c53f` and the unsigned 1017 artifact remain historical,
separate deliveries/evidence. This audit does not withdraw reported APK 1018
delivery or present an unsigned build as its installer. Source implementation,
retained source checks, signed-artifact provenance and device acceptance are four
distinct statuses. No PC setup, ADB authorization, emulator installation, app/data
change, new APK, deployment, main merge, credential check or PR-body edit occurred.
