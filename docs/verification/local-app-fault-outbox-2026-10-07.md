# Local sanitized app fault outbox

This change collects a narrow class of abnormal app faults locally. It does not
send reports, create GitHub issues, provision credentials, or deploy a relay.
The existing manual ACRA report screen and its consent flow remain unchanged.

## Reachable capture and public contract

`AcraReportSender.send` passes only the existing ACRA `STACK_TRACE` field into
`AppFaultRecorder`. ACRA 5.13.1's installed `ACRAConstants` default report field
list contains `STACK_TRACE` (verified with `javap`). The numeric application
version code and Android API level come from the current application build.

`SanitizedAppFault` accepts only a small exception-type allowlist whose first
fault frame is in a known app component. Unknown exception types, unknown
wrappers, provider/extractor frames, suppressed exceptions, malformed traces,
oversized input, and network/data causes are rejected. Classification is
deliberately conservative: many legitimate bugs will be omitted. A string-only
stack trace cannot authenticate perfectly forged stack-shaped exception text.
Even accepted text contributes no free-form bytes to the resulting payload.

The complete schema is:

```json
{"schema":1,"fault":"NULL_POINTER","component":"PLAYER","app_version_code":123,"android_api":35}
```

Fault and component values are fixed enums. The issue title and body are built
only from this schema. No title, URL, search, account, identifier, token, local
path, trace, raw exception message, device model, or user content is copied.
The new subsystem never persists raw traces; existing ACRA behavior is separate.

## Persistence and limits

`AppFaultOutbox` stores a versioned binary ledger in the application's private
`getNoBackupFilesDir()/sanitized-app-faults` directory. It keeps at most eight
records, accepts at most three new signatures per rolling 24 hours, deduplicates
pending signatures for their lifetime, and expires pending records after seven
days on the next outbox access (no scheduled deletion worker). Delivery
acknowledgements preserve receipts for the original 24-hour
admission window. Coarse signatures use only the four public fields above.
Local timestamps and pending flags are bookkeeping, never part of issue content.

Transactions use an in-process monitor plus a cross-process file lock, a synced
temporary file, and same-directory atomic rename on Android. The persisted clock
high-water mark prevents wall-clock rollback from resetting quotas. Damaged or
unknown ledgers fail closed without clearing quotas. Filesystem and runtime
capture failures do not recurse into error reporting or prevent the manual UI.

`pending` and `acknowledge` are the future transport boundary. No caller currently
transmits these records. The user has authorized public reports within this
narrow sanitized scope. A future sender must separately enforce delivery pacing,
confirm successful delivery before acknowledging, and use an approved
authentication path. New credentials or a relay are outside this authorization.

## Authentication blocker

GitHub supports creating issues through `POST /repos/{owner}/{repo}/issues` with
an authenticated GitHub App token or fine-grained personal access token with
repository **Issues: write** permission. See the official
[Create an issue endpoint](https://docs.github.com/en/rest/issues/issues#create-an-issue)
and [REST authentication guide](https://docs.github.com/en/rest/authentication/authenticating-to-the-rest-api).

There is no approved app-safe reporting credential or authenticated relay in this
scope. Development-session credentials are not distributable app credentials.
Embedding a token in the APK is prohibited. Consequently automatic public issue
delivery remains blocked; no live endpoint, test issue, or credential was used.

## Verification

Standalone `javac` plus JUnit 4 exercised sanitizer privacy, wrapped transport and
data exclusions, forged frame/cause text under disallowed root types, private
frame identifiers, restart persistence, deduplication after mock delivery,
rolling quota, clock rollback, queue capacity, expiry, corrupt ledgers, and
nonfatal storage failures. Transport in the test is an in-memory string only.
Gradle and Android runtime validation belong to the integrating parent task.
