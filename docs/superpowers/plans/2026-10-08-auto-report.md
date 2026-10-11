# Automatic fault delivery Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans task-by-task. Steps use checkbox (`- [x]`) syntax.

**Goal:** Prepare bounded sanitized fault delivery without live credentials or deployment.

**Architecture:** Durable binary Android claim/complete feeds an isolated HTTPS
transport and unique one-time WorkManager. One SQLite Durable Object reserves
global issue quota and reconciles ambiguous creation through a GitHub App adapter.

**Tech Stack:** Java/JUnit4, AndroidX WorkManager/OkHttp, Node24 node:test and
node:sqlite, Cloudflare Workers/SQLite Durable Objects, secret-free Wrangler.

**Spec:** `docs/superpowers/specs/2026-10-08-auto-report-design.md`

## Global Constraints

- Exactly five wire fields; existing enum/version/API allowlists.
- Three new issue reservations per rolling 24 hours, across all clients.
- Three local sends per record; 15-minute interrupted lease; seven-day retention.
- Backoff bounded to 24 hours; no polling/periodic work/cron/alarms.
- Endpoint unset and relay disabled by default. No live credentials or issue calls.
- Actual Claude Opus5.5 product author; GPT6.1sol high integration/review.
- Preserve PR6, draft PR only, no main merge/deployment/paid settings.

## Review Focus

- A stale sender must not ack a newer claim; persist before network I/O.
- Lost SQLite tables must not reopen global quota, including both-table loss.
- A timeout/crash may already have created an issue; missing lookup cannot authorize another POST.
- Delayed quota reservations cannot allow six new creations in one 24-hour window.
- ACRA sender process and empty endpoint must not register reporting network jobs.

### Task 1: Durable app claim/complete

**Files:** `AppFaultOutbox.java`, new autoreport unit tests.

**Interfaces:** `claim(long now): Optional<Claim>`; Claim exposes sanitized fault,
generation and attempt count. `complete(Claim, Outcome, long now, long retryAfter)`;
Outcome distinguishes ACK, RETRY and RETAIN. `nextEligibleAt(long now)` exposes
the earliest retry for scheduling without sending network requests.

- [x] Claude writes tests for durable attempts, stale complete, lease recovery,
  bounded retry/retention, old ledger migration, quota and corruption.
- [x] Run standalone javac/JUnit and record RED from the missing feature.
- [x] Claude extends binary ledger and existing APIs without resetting state.
- [x] Run new and existing sanitizer/outbox tests GREEN; compare baseline 13 tests.

### Task 2: SQLite relay core and server authentication

**Files:** `relay/src/*.mjs`, `relay/test/*.test.mjs`, `relay/package.json`,
`relay/wrangler.jsonc`.

**Interfaces:** strict payload validator, deterministic signature/issue renderer;
real SQL ledger reserve/reconcile/complete, injectable GitHub HTTP at the boundary;
Cloudflare Durable Object adapter with persistent initialization sentinel.

- [x] Claude writes node:test tests against real node:sqlite persistence for
  dedupe, concurrent three/day reservation, rollback, 429, timeout, restart,
  partial/both-table loss, strict payload/size/stop and GitHub JWT/token scoping.
- [x] Run `node --test relay/test/*.test.mjs` RED before production modules.
- [x] Claude implements core, actual Worker/DO adapter, RS256 GitHub App adapter
  and secret-free configuration, with no provider fallback or live calls.
- [x] Run tests GREEN, check Wrangler local packaging and runtime if available.

### Task 3: Android event-only scheduling and isolated transport

**Files:** autoreport coordinator/transport/worker/scheduler, AcraReportSender,
App startup hook, `app/build.gradle.kts`, unit and Android instrumentation tests.

**Interfaces:** coordinator claims/attempts/completes using bounded HTTPS result;
scheduler consumes `nextEligibleAt` and uses unique one-time WorkManager work;
build endpoint string is empty unless explicitly configured for a later rollout.

- [x] Claude writes tests for HTTP-before-persistence rejection, timeout/429 ack
  rules, empty queue/endpoint no work and restart retention, then run RED.
- [x] Claude implements integration preserving manual error UI/playback/saving.
- [x] Run full JVM baseline/comparison, checkstyle/ktlint, build debug and Android
  test APK. Execute possible API23/35 instrumentation or report exact blockers.

### Task 4: Cross-review, operator gate and draft preservation

**Files:** relay operator README and verification report/author hash manifest.

- [x] GPT6.1sol high reviews code independently against all constraints and tests.
- [x] Claude writes regression tests RED and fixes any important findings GREEN.
- [x] Record actual commands/counts/blockers; preserve only model/version/file
  hashes as authorship evidence, no private reasoning or raw model transcripts.
- [x] Document exact later GitHub/Cloudflare permissions and default disabled state.
- [x] Verify diff/base preservation, commit/push isolated branch and preserve a
  draft PR. Do not merge or deploy.
