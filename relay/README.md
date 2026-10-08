# Backtube fault relay

This directory contains an optional relay. The relay turns automatic, **sanitized** Backtube app
faults into GitHub issues. It is scoped to Backtube only. It is **off by default** in both the
APK and the server.

> **Status:** None of the setup steps below have been executed. No GitHub App, installation,
> private key, Cloudflare account, deployment or public issue was created, configured or
> deployed for this relay by this work.

## What is reported

- A report contains exactly five allowlisted fields. The validator in `src/protocol.mjs` defines
  them, and it rejects any other field.
- URLs, raw stack traces, account data, device identifiers and provider (extractor/service)
  faults are excluded.
- No client headers are forwarded. The Worker builds a fresh canonical JSON request for the
  Durable Object.

## Defaults (off)

- **APK:** `BuildConfig.APP_FAULT_ENDPOINT` is empty, so nothing is scheduled or sent.
- **Server:** `wrangler.jsonc` sets `REPORTING_ENABLED` to `"false"` and leaves `GH_APP_ID`,
  `GH_INSTALLATION_ID` and `GH_BOT_LOGIN` blank. Reporting runs only when
  `REPORTING_ENABLED === "true"` and all four GitHub App bindings are present. Otherwise the
  Worker answers `503 {"status":"disabled"}` and contacts neither the Durable Object nor
  GitHub.

Only build an APK with a public endpoint after a separately approved deployment:

```
./gradlew assembleRelease -PappFaultEndpoint=https://<host>/v1/fault
```

## Android delivery

Delivery uses `AppFaultScheduler`, `AppFaultOutbox` and `AppFaultUploadWorker`. It works as
follows:

- There is a single unique, one-time WorkManager job constrained to `NetworkType.CONNECTED`.
  There is no periodic work, polling, alarm or service.
- The outbox state is persisted in the no-backup files directory:
  - each fault gets up to 3 attempts;
  - each claim has a 15-minute lease;
  - retries back off from 15 minutes up to 24 hours;
  - faults expire logically after 7 days; expired records are pruned on outbox access, not by
    a timer, so physical files can remain while the app is idle or the endpoint is off;
  - the outbox holds at most 8 faults;
  - at most 3 faults are recorded locally per day.
- Existing behavior is preserved: ACRA/manual error reporting, playback and offline use work
  unchanged when delivery is off or fails.

## Server design

- All reports go to one global SQLite-backed Durable Object named `backtube-global-v1`.
- The relay creates at most **three issues per rolling 24 hours** across all clients.
- If an issue creation has a pending or unknown outcome, it keeps holding its slot. The relay
  reconciles it only by searching for the issue marker. Confirmation may be delayed, but the cap
  is never exceeded.
- There are no retries of ambiguous `POST`s, and no alarms, cron, services or background tasks.
  Request bodies are never logged, and observability is disabled.
- A stop flag is checked before every provider fetch. This includes the installation token
  request and each search pagination step.
- The ledger has two SQL tables and a KV sentinel. Once initialized, missing or damaged state
  fails closed. There is no automatic reset after damage.
- To stop reporting, an operator may disable the endpoint (ship an APK without it) or disable
  the server (`REPORTING_ENABLED=false`).

**Known limitation:** The endpoint is public and unauthenticated by design. Anyone can fabricate
reports or use up the daily issue quota. The relay deliberately does **not** add device
identity or authentication. The global cap bounds the impact.

## Future setup (requires explicit user approval first)

Each of the following steps needs explicit **user** approval *before* it is performed:

- creating or installing the GitHub App;
- issuing or importing its private key;
- creating a Cloudflare account or choosing a plan;
- deploying the Worker;
- shipping an APK that opens public issues.

### GitHub App

- Target repository: exactly `wnswlfhvkr-code/backtube`, and no other.
- Permissions: Issues **write**, plus the implicit Metadata read.
- The installation covers only this repository.
- `GH_BOT_LOGIN` must exactly match the App's bot login.
- `GH_APP_ID`, `GH_INSTALLATION_ID` and `GH_BOT_LOGIN` are non-secret Worker vars.
- `GH_APP_PRIVATE_KEY` is a server runtime secret in PKCS#8 PEM format:
  - it is held only in memory, for JWT signing;
  - it never goes into the APK, the repository or logs;
  - it is never replaced by a personal access token.
- GitHub provides the downloaded key as an RSA key, which may need PKCS#8 conversion. Do that
  only under a future approved operator process. This repository neither provides nor stores a
  key.
- Token flow reference:
  https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-an-installation-access-token-for-a-github-app
- There are no other API keys or Claude OAuth credentials in the relay or the APK.

### Cloudflare

- The relay targets Workers Free with SQLite-backed Durable Objects. See the official pricing
  page: https://developers.cloudflare.com/durable-objects/platform/pricing/
- Verify free-plan availability and limits before any deployment.
- Never enable a paid plan or overage from this project.

## Development

Requires Node.js 24.

```
cd relay
npm ci
npm test
```

`npm test` runs against real Node SQLite and Miniflare/workerd. All outbound requests are
mocked, and keys are synthetic and in-memory only. To check packaging only, without deploying,
run:

```
npx wrangler deploy --dry-run --outdir build-dry-run
```

Do **not** run a real deploy from this repository.

## Verification status

- Android runtime behavior on API 23 and 35 has **not** been executed (no emulator).
- Unit tests and APK builds are separate evidence.
- No battery measurements have been made, and none are claimed.
- Do not treat the work as fully tested until a verification document records the results.
