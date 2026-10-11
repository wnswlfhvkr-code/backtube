# Related default tab and compact saved library — resume record

**Latest state (2026-10-11):** Activity integration is complete; fresh app JVM
352/352, Checkstyle zero violations, instrumentation source compilation and
unsigned release build passed. Actual Android execution is 0 and compatible
signing is blocked. See the Active resume and Final delivery sections below.

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
