# Windows build and actual Android saved-shelf follow-up

Baseline: `4e8a34e4cec488fdd8b295bd855301fadac6655a`, PR7 branch
`feat/claude-auto-report`. An independent worktree/branch preserves the original
checkout and user project. This follow-up fixes only verified shelf geometry and
test fixtures. No store, quota, expiry, playlist, player, drag dispatcher or
atomic order-commit policy was changed.

## Reproduced failures and corrections

The first actual API35 x86_64 emulator run had 13 tests, 9 passing and 4 failing.
The emulator used the existing CodexYouTubeTest AVD with read-only and no-snapshot
flags; a separate debug package kept existing app data out of the follow-up.

- Both detached layout tests failed before measurement because their RecyclerView
  had no LayoutManager. Supplying the same LinearLayoutManager as production lets
  real row inflation/measurement execute. Both original long-title tests then
  passed without a product change.
- The handle-height check was a product issue: the shared queue thumbnail is 35dp
  with 6dp margins, so a short row's playback root and match-constraint handle were
 47dp despite the outer 48dp menu. A new short-title real-layout regression failed
  at 141px versus 144px required in its configured context. The offline adapter now
  applies ConstraintLayout's 48dp minimum to its playback root. The shared queue
  resource remains unchanged. Long-title growth, bounds, nonoverlap, status,
  progress and 48dp target assertions remain enforced at 320/360dp and fontScale 1/2.
- The drag test was a fixture issue. DOWN correctly selected the handle and
  disabled Play all, but center-to-center movement only aligned the dragged top
  with the candidate top. Actual RecyclerView 1.4.0 bytecode requires the dragged
  top to cross it (`diff>0`); merely drawing/waiting does not perform a move. Moving
 16dp beyond the center crosses that boundary. The test additionally requires
  actual adapter preview movement, no disk write before terminal input, CANCEL
  restoration of every visual adapter position and translation, a fresh source
  position for each gesture, UP persistence after reopen, unchanged active queue
  snapshot and local-only next queue. The production drag code was not changed.

Fixture-corrected RED was 5 tests/3 failures: both long-title layouts passed,
short-title geometry, handle height and drag still failed. Height correction
yielded 14/15 actual Android passes; crossing the verified input boundary made
the drag case pass. Independent QA identified a missing direct CANCEL visual-order
assertion; it was added and the final whole selected run passed 15/15 in 27.786s.
No remaining Critical/Important issue was found in the reviewed diff.

## Windows whole-JVM fixture corrections

Original failed XML/log evidence remains in the task-local evidence directory.
After using an ASCII Java temporary directory to avoid Mockito's Windows native
bootstrap-path failure,390 tests produced 396 XML records and 14 failures: six
additional Mockito teardown records followed missing resources. Eight unique
tests failed. Import ZIP fixtures constructed File(URL.file), leaving the Korean
path percent-encoded. URI conversion fixes fixture access while preserving all
import, malformed-input and serialized-injection assertions.

Windows cannot unlink an open RandomAccessFile. The JVM regression now keeps a
separate live backing handle and checks that deleting its store identity rejects
write and setLength without growing the backing file. A new Android regression
preserves the original condition: unlink the actual store file while its output
stream is open, verify the path is gone, reject write/setLength, and return used
bytes to 0. It passed on the actual emulator. No skip or relaxed exception check
was introduced, and the Android storage implementation was not changed.

## Final executed checks

- Windows app JVM: 390 tests/58 suites, 0 failures/errors/skips.
- Checkstyle: 0 issues; Kotlin lint successful.
- Debug/release lint: 0 errors, 308 warnings each in this Windows environment.
  Comparison to the same Windows baseline found zero new issue signatures.
  Earlier Linux 366-warning reports are historical and not substituted here.
- Debug and Android-test APK compilation succeeded. Actual selected Android
  instrumentation: 15/15, including layout 3, real unlink 1, disk playlist reopen 1,
  standalone creation 1, and saved-library access/gesture/accessibility 9.
- Physical Android devices, installed TalkBack, broad YouTube/ExoPlayer acceptance
  and the target signed preserving-update install remain unperformed in Windows.

Evidence names in the task-local `followup/` directory include `android-red.txt`,
`preview-red.txt`, `drag-down-red.txt`, `android-height-green.txt`,
`drag-boundary-green.txt`, `android-final-green.txt`,
`full-jvm-green-summary.json`, `height-green-build.log`,
`final-style-lint-build.log`, and the exact 1.4.0 javap records. The parent retains
the approved original APK1018 signing environment and performs compatible signing.
Windows generates no new key and copies/outputs/transfers no key or password.
Any Windows release output is unsigned signing input, never an installer.
