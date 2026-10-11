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

### 운영자 실행 체크리스트 (향후, 사용자 전용)

> 준비용 문서입니다. 아래 항목은 **하나도 실행되지 않았습니다**. 계정, App, 키, 자격 증명, 인증,
> 배포, 유료 플랜, 공개 이슈는 만들어지지 않았습니다. 이후 설정과 롤아웃을 사용자가 명시적으로
> 승인한 경우에만 **사용자가 직접** 수행합니다.

1. **GitHub App 등록과 설치 (사용자):**
   - 본인 계정 전용(private) App으로 등록합니다.
   - Repository 권한은 Issues **Read and write** 하나만 선택합니다. Metadata read는 자동으로
     포함됩니다.
   - 다른 repository/account 권한, OAuth 사용자 인증, Device Flow, webhook은 사용하지 않습니다.
   - 설치할 때 "Only select repositories"로 정확히 `wnswlfhvkr-code/backtube` 하나만 고릅니다.
   - 참고: https://docs.github.com/en/apps/creating-github-apps/registering-a-github-app/registering-a-github-app ,
     https://docs.github.com/en/apps/creating-github-apps/registering-a-github-app/choosing-permissions-for-a-github-app
2. **개인 키 (사용자, 비공개):**
   - 키 발급과 PKCS#8 PEM 변환은 사용자가 비공개로 직접 처리합니다.
   - 유일한 서버 런타임 시크릿 `GH_APP_PRIVATE_KEY`는 Cloudflare 시크릿 UI에 직접 입력합니다.
   - 키를 채팅, 어시스턴트, APK, 저장소, 로그, 명령줄 인자에 넣지 마십시오.
3. **Cloudflare Free 연결 (사용자):**
   - 사용자가 직접 Wrangler 계정 OAuth 로그인을 합니다. 참고:
     https://developers.cloudflare.com/workers/wrangler/commands/#login
   - 이 최소 수동 경로에는 별도의 `CLOUDFLARE_API_TOKEN`이 필요 없으며, 지금 발급하지 않습니다.
   - Workers Free의 SQLite 기반 Durable Objects 가용성과 한도를 확인합니다. 참고:
     https://developers.cloudflare.com/durable-objects/platform/pricing/
   - `wrangler.jsonc`에 있는 현재 바인딩과 마이그레이션만 사용합니다. 유료 플랜이나 초과 과금은
     쓰지 않습니다.
4. **비활성 배포 (승인 후):**
   - `REPORTING_ENABLED`를 `"false"`로 유지한 채 배포합니다.
   - 아래 표의 비밀이 아닌 식별자를 설정합니다.
5. **활성화와 엔드포인트 APK (별도 명시 승인):**
   - `REPORTING_ENABLED`를 `"true"`로 바꿉니다.
   - 공개 HTTPS `/v1/fault` 엔드포인트를 넣은 APK를 배포합니다.
   - 이때부터 공개 이슈가 생성될 수 있습니다.
6. **중지:**
   - `REPORTING_ENABLED`를 `"false"`로 되돌리거나, 엔드포인트가 비어 있는 APK를 배포합니다.

| 바인딩 | 종류 | 용도 |
|---|---|---|
| `REPORTING_ENABLED` | Worker var | 전체 스위치. 기본값 `"false"` |
| `GH_APP_ID` | Worker var (비밀 아님) | App 식별자. JWT 발급자 |
| `GH_INSTALLATION_ID` | Worker var (비밀 아님) | 설치 토큰을 발급받을 설치 |
| `GH_BOT_LOGIN` | Worker var (비밀 아님) | App 봇 로그인. `<app-slug>[bot]`과 정확히 일치 |
| `GH_APP_PRIVATE_KEY` | 유일한 시크릿 | 메모리 내에서 RS256 App JWT 서명에만 사용. 이 JWT로 단기 설치 액세스 토큰을 발급받으며, 개인 키가 설치 액세스 토큰을 직접 서명하지는 않음 |
| `APP_FAULT_ENDPOINT` | APK 빌드 값 (비밀 아님) | 공개 HTTPS `/v1/fault`. 현재 비어 있음 |

PAT, webhook secret, Claude OAuth 토큰, 제공자 API 키는 relay와 APK 어디에도 필요하지 않으며
추가하지 않습니다.

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

- Android runtime behavior on API 23 and 35 remains **unverified**.
  - A device run was attempted on 2026-10-08 and was BLOCKED by infrastructure: there was no
    KVM, and the ADB bridge could not start.
  - Each API level had 0 executed, 0 passed and 0 failed. This is not an application test
    failure.
  - Details and pending coverage: `docs/verification/auto-report-2026-10-08-mobile.md`.
- Unit tests, APK builds and relay tests (47/47) are separate evidence. They are not mobile
  results.
- No battery measurements have been made, and none are claimed.
- Do not treat the work as fully tested until a verification document records device results.
