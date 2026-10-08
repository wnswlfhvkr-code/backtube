# Automatic fault delivery verification (2026-10-08)

## Provenance

- Worktree branch `feat/claude-auto-report`, based on PR6
  `be4b30a4850a70e13a0493a399318091205b5e65`.
- Approved product ancestor `2063f70c2948f47c1156d74be78c0a20cfd7e8ec` is preserved.
- Product changes were authored through the official Claude CLI 2.1.294 using
  `oauth_token` authentication. Every author output reported `modelUsage` of exactly
  `claude-opus-5-5`.
- Authorship proof: `auto-report-2026-10-08-author.json`. It contains only file hashes
  and model metadata. It holds no raw responses, private reasoning or credentials.
- The supplied Library ZIP was not used. The initial transfer and the one approved retry
  both returned HTTP 403. The source ZIP is unavailable and its hash is not verified.
  The work derives from the remote approved PR6 source and the delegated specification
  (`docs/superpowers/specs/2026-10-08-auto-report-design.md`).

## Commands and results

Toolchain: JDK 21, Android SDK 37, Build Tools 36, Node.js 24, Wrangler 4.148.0.

### Android (Gradle)

```
./gradlew :app:testDebugUnitTest :app:runCheckstyle :app:assembleDebug \
  :app:assembleDebugAndroidTest -DskipFormatKtlint --console=plain --no-configuration-cache
```

- Exit code 0.
- JVM unit tests: 311 tests in 48 suites; 0 failures, 0 errors, 0 skipped.
- Pristine PR6 baseline: 266 tests in 43 suites. This work adds 45 tests.
- Checkstyle: PASS.
- Debug APK and instrumentation (androidTest) APK built.
- Pure autoreport tests: 50 PASS. The 8 OkHttp transport tests are covered by the full
  Gradle run above.

### Scheduler API-boundary check

```
JAVA_HOME=<JDK 21> python3 docs/verification/auto-report-2026-10-08-scheduler-check.py
```

- Exit code 0.
- A blank (default) endpoint at startup cancels an old reporting job.
- A stale empty-queue cancel does not remove a newer fault job; the new job survives.
- This checks the API boundary only. It is not an Android device execution.

### Relay

```
cd relay
npm test
```

- Node.js 24: 47/47 PASS.
- Includes real Node SQLite and Miniflare 5/workerd with persisted SQLite, covering the
  global cap across restart.
- Outbound requests: 0. Only synthetic in-memory RSA keys and fake HTTP were used.

```
npx wrangler deploy --dry-run --outdir build-dry-run
```

- Exit code 0. Packaging only; no account was used and nothing was deployed.

### Ktlint

```
./gradlew :app:runKtlint --rerun-tasks -DskipFormatKtlint --console=plain --no-configuration-cache
```

- Exit code 0. BUILD SUCCESSFUL in 5s; 4 tasks executed.
- Ktlint: PASS.

## Review findings fixed

Independent review raised seven findings. All were fixed, with RED->GREEN regression
coverage included in the counts above (no tests beyond those counts are claimed):

1. Late-confirmed creation counted in the correct rolling quota window.
2. Malformed reservation rows fail closed.
3. Corrupt sentinel, or missing sentinel on an existing ledger, fails closed. A missing
   sentinel on a genuinely fresh (empty) object initializes the ledger normally.
4. Stop flag re-checked after the installation token await.
5. Blank endpoint at startup cancels an old reporting job.
6. Stale empty-queue cancel no longer races and removes a new event job.
7. Retry deadline uses the actual completion time.

## Known limits

- Android API 23/35: no emulator, system image or device execution was available, so no
  instrumentation tests were run on a device.
- No real GitHub App, installation, private key, Cloudflare account, endpoint, runtime
  provisioning or public issue call was made.
- The public endpoint is unauthenticated by design. Anyone can fabricate allowlisted
  reports; the global three-per-24-hours cap bounds impact but does not prove sender
  authenticity.
- No battery measurements were made and none are claimed.

## Default state

- Default APK endpoint is empty; no reporting job or network is scheduled.
- Relay is off (`REPORTING_ENABLED="false"`, GitHub App bindings blank).
- No secrets, repository tokens, new authentication, paid plan, merge or deployment.

## Future approval steps

GitHub App creation/installation, key issuance, secret entry, Cloudflare account/plan,
deployment and shipping an APK with a public endpoint each need explicit user approval.
See `relay/README.md` ("Future setup").

## Pull request

This work is prepared for a draft PR stacked on base `feat/playback-feedback`,
preserving existing PR6. No merge to main and no deployment is authorized. This
document does not assign a new PR number or URL.
