# Saved library controls verification — 2026-10-11

Codex implemented the approved saved-library work. Claude usage, authentication
queries and connection retries remain stopped. This record concerns product
changes; superseded OAuth validation is not a resumption task.

## Result

- Saved library shows Play all and the playable count, grouped title/status/size,
  separate playback/management actions and labeled 48dp move controls.
- Shelf order persists across restart. New saves append. Play all creates a finite
  snapshot; later saves and shelf moves affect the next Play all operation.
- Saved queues retain local identity/scope on restore, reject online additions,
  suppress recommendations and safely skip unavailable entries.
- Automatic transitions use immutable media identity instead of timeline indices.
  Completion, unavailable transition targets and source failures choose a valid
  successor before removals. Repeat OFF stops at exhaustion; ALL may wrap to a
  valid remaining save. Failed sources cannot repeat themselves indefinitely.
- Valid unchanged shelf order opens without requiring a write. Repair write
  failures do not block complete files; explicit moves still persist before the
  visible order changes. Quota, indefinite retention, source ownership and open
  reader protection remain intact.
- Existing personal-playlist creation is easier to find from track/queue actions
  and the playlists screen; the dialogs and database behavior are reused.

## Verified

Final command, after the last production change:

```text
./gradlew --offline --console=plain -DskipFormatKtlint -PappFaultEndpoint= \
  :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

Exit 0 in 81.008 seconds. JVM: **338 tests, 49 suites, zero failures/errors/skips**.
Checkstyle: **zero errors**. Debug app and instrumentation APKs compile. Fault
endpoint is empty; automatic external reporting remains OFF.

Lint has four pre-existing errors and 366 warnings. The four implicated files
are byte-identical to source checkpoint `1c92066`. The errors are MissingClass
(manifest), WrongConstant (KeyboardUtil and PlayerService), and
UnspecifiedRegisterReceiverFlag (AudioFocusOwnerActivity instrumentation helper).
`abortOnError=false` means task success is not a clean lint report.

Regression evidence includes real order write-fault failures, deleted open-reader
transition identity, missing-tail repeat boundaries, valid middle successors,
bounded repeated source failures and rejected late timestamp callbacks.
Independent whole-range review and follow-up checks found no remaining material
finding in the reviewed source after fixes. Review did not repeat the tests.

## Runtime and delivery limits

Actual Android UI/ExoPlayer execution: **0**. The supported ADB attempt aborted
before device enumeration because the default `.android` home is read-only.
No home/key/permission workaround was used. New UI geometry, large fonts,
TalkBack, orientation and real ExoPlayer callback ordering remain unverified;
compiled instrumentation tests and source review are not device QA.

Local debug APK uses the existing local standard debug signer. Its public
certificate differs from the previously delivered CI APK certificate. Installed
certificate identity is unverified. Do not uninstall, clear data, change package
identity or create a replacement signing key to make an update proceed.

The existing draft feature branch is the preservation target. The separately
authorized CI artifact workflow will pin the final product commit, deliver a
version-code-1016 debug APK with its source suffix, and include exact APK hash,
public signer certificate digest, signature verification and unit counts in the
ZIP. CI standard debug signing is not evidence of compatibility with an existing
installation. CI artifact URL/expiry and final source SHA are reported with the
delivery result; no main merge or deployment is part of this work.

Decisions and red/green history are recorded in
`offline-playback-order-progress-2026-10-11.md`. No minor review finding remains
deferred: the timestamp issue was regraded and fixed because it changed playback.
