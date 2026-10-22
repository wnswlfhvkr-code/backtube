# Automatic sanitized fault delivery

## Scope and approval

Continue PR6 `be4b30a4850a70e13a0493a399318091205b5e65` and preserve its product
ancestor `2063f70c2948f47c1156d74be78c0a20cfd7e8ec`. The user approved sending
Backtube source, specifications and tests to Claude for implementation/review;
secrets and personal information are excluded. Claude Opus5.5 authors product
changes. GPT6.1sol high independently reviews integration. Preserve a draft PR;
do not merge main or deploy.

The supplied Library ZIP could not be materialized: both the initial supported
transfer and the one authorized consumer-local retry returned HTTP403. No local
file exists, so its supplied checksum is unverified. This specification derives
from the delegated requirements and verified PR6 source, not the unadopted relay
prototype. The first Claude design request timed out without an output file.
This design and plan are coordinator artifacts, not Claude implementation proof.

## Privacy boundary

The wire payload has exactly five fields: `schema=1`, `fault`, `component`,
`app_version_code`, `android_api`. Fault/component use existing fixed enum
allowlists. Numeric version must be positive and API 23..100. No URLs, device
identifiers, arbitrary strings, personal data, raw traces, network errors or
video-provider errors are included. The existing conservative sanitizer remains.
Server-authored issue title/body contain only these allowlisted fields plus a
deterministic public signature marker. Never log request bodies, credentials,
headers, raw GitHub responses or private reasoning.

## Android flow

Extend the existing locked binary outbox with versioned migration preserving all
records, receipts, rolling admission quota and clock high-watermark. Claim a
record under the durable transaction before HTTP: persist incremented attempts,
unique claim generation, interrupted-send lease and next retry deadline. A stale
completion cannot acknowledge a newer claim. At most three sends per record,
15-minute interrupted-claim lease, seven-day retention and bounded backoff up
to 24 hours; exhausted failures remain until retention expiry and do not reset
dedupe/quota. Corruption fails closed without wiping the ledger.

A pure Java delivery coordinator uses injected transport/time at the boundary.
Only a validated created/duplicate response with a positive issue number permits
ack. Timeout/429/transient errors retry within the durable limit; other failures
are retained. HTTPS transport is isolated from the app's downloader: no cookies,
identifiers or credentials, no redirect following, fixed bounded JSON request
and response, finite connect/read timeout.

The APK contains only an HTTPS endpoint, empty by default. Missing endpoint means
no reporting job/network. Only fault events and startup recovery of existing
pending records enqueue unique one-time WorkManager work; no periodic work,
polling, alarms, cron or standing service. Pending work uses network constraints
and bounded persisted deadlines. With no eligible pending record, cancel the
reporting job. Avoid WorkManager startup registration in the ACRA sender process.
Reporting failures must not interfere with manual ACRA UI, playback or saving.

## Relay flow

A Cloudflare Free Worker validates `POST /v1/fault`, streaming body limit 512
bytes and exact JSON keys/types, then routes every signature to one fixed SQLite
Durable Object. A global stop switch and missing server credentials fail closed
before GitHub I/O. No configured account, secret, live endpoint or deployment is
part of this change. Disable observability/body logs and scheduled triggers.

Real SQLite transactions reserve at most three new issue creations per rolling
24 hours across all clients. Reservations and monotonic clock survive restarts.
Before external I/O, persisted state denies a concurrent second creator. A known
429 preserves backoff; delayed retry requires renewed atomic admission where
necessary. Unknown create outcomes (timeout, crash, invalid response or 5xx) keep
their reservation and only reconcile using an exact own-bot signature marker;
never blindly repeat a POST because an eventually consistent lookup finds none.
Only confirmed creation or the same existing issue produces a delivery ack.

Initialization has a persistent KV sentinel independent of SQL tables. Validate
the complete schema and bounds on access; partial table loss, both-table loss,
malformed schema and corruption fail closed, rather than reinitialize quota.
Only an actually fresh object may initialize. There are no Durable Object alarms.

Server-only GitHub App authentication signs short-lived RS256 JWTs and requests
an installation token restricted to the Backtube repository and Issues:write.
Validate returned token scope before issue access. Runtime secrets stay in memory
and operator-managed bindings; never embed keys in APK/config/source. No PAT or
development-session credential fallback. A public endpoint can receive fabricated
allowlisted reports; the global cap bounds issue creation but is not proof that
the sender is a genuine installation. Do not add tracking to solve this.

## Validation and operational gate

Run tests RED before Claude implementation, then GREEN against real binary/SQLite
persistence. Cover offline/restart, claim-before-I/O, dedupe, concurrent quota,
429, ambiguous timeout, retained exhaustion, clock rollback, legacy migration,
both-table loss, stop switch and GitHub authentication/scoping. Compare the full
JVM suite to baseline and preserve playback/offline regression tests. Run possible
Android23/35 tests or name the exact environment blocker. Do not claim measured
battery behavior.

Provide secret-free Wrangler configuration and operator steps. GitHub App
creation/installation/key issuance/OAuth grant, secret entry, Cloudflare account
connection/terms and actual deployment require separate explicit confirmation.
Until those permissions and configuration are complete, send no public issue.

## Implemented boundary refinements

The implementation refines these boundaries. Scheduling and empty-queue
cancellation share a mutual scheduler lock; after a cancel, the persisted outbox
is rechecked so a concurrently recorded fault is rescheduled rather than lost.
When the endpoint is off (blank), startup cancels any old reporting job. The
worker computes its retry deadline from an injected completion clock, so backoff
is measured from the actual completion time. The relay checks the provider stop
gate before each GitHub fetch, including after the installation-token await.
The initialization sentinel is strict: any invalid value fails closed rather
than reinitializing quota. The original design provenance above remains
coordinator-authored; this refinement section was authored by Claude in review
of the implemented source.
