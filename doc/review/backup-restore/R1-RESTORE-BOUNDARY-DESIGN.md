# R1 복원 경계 설계 — 구현 전 문서

조사 기준은 `4c7a34c0622d03c42217bba468b272eeb46512f4`입니다. R2가 포함된 별도 브랜치이며, 전달된 APK1036에는 이 브랜치의 R2 변경이 없습니다.

이번 단계는 소스·기존 병합 매니페스트·공식 문서를 읽고 설계한 작업입니다. 실행 코드/설정/권한 변경, DB 열기·복원, 서비스 중지, 프로세스 재시작, 테스트·빌드·APK 제작은 수행하지 않았습니다. 실제 사용자 자료는 접근하지 않았습니다. 아래 새 경계와 테스트는 모두 **미구현·미실행**이며 전체 실행 코드 감사를 완료했다는 뜻이 아닙니다.

## 1. 결론과 보장 범위

현재 `NewPipeDatabase.close()`나 서비스 stop, Rx dispose, WorkManager 취소 중 어느 하나도 앱 전체 DB 작업의 종료 확인으로 사용할 수 없습니다. 특히 종료 과정 자체에서 새 저장 작업이 생깁니다. 따라서 `ImportExportManager`에 close/rename만 추가하는 두 파일 패치를 안전한 R1 해결로 제안하지 않습니다.

권고하는 다음 설계 검증 순서는 **재시작 후 유지보수 모드에서 일반 DB 진입을 차단하는 방안의 독립 실증**입니다. 오래된 DAO를 같은 프로세스에서 모두 회수하는 부담을 줄일 수 있지만, 자동 초기화·재진입·다른 프로세스까지 막았다는 증거가 먼저 필요합니다. 기존 ProcessPhoenix 호출만으로 그 증거를 대신할 수 없습니다. 이 방안이 검증되기 전에는 구현·배포 승인안이 아닙니다.

최소 목표는 DB 교체 트랜잭션이 실패하면 이전의 **논리적 DB 내용**과 설정을 유지하는 것입니다. checkpoint/reopen 때문에 물리 DB 바이트가 정상적으로 변할 수 있으므로, 교체 전 파일 불변 검사와 checkpoint 이후 논리 내용 보존 검사를 구분합니다. 현재 DB+선택적 설정 가져오기는 공동 원자 트랜잭션이 아닙니다. 설정을 취소하면 새 DB는 유지하는 기존 UX를 바꾸지 않습니다. DB 교체 commit 전까지 설정·마지막 import URI를 쓰지 않아야 합니다.

## 2. 실제 소유권 조사

아래 링크는 조사 기준 커밋에 고정했습니다. 직접 singleton 획득 표현이 발견된 제품 파일은 16개입니다. 인자로 AppDatabase를 받는 manager와 그 상위 서비스/Worker를 추가 추적했습니다. 이 수는 동적 실행 전체를 빠짐없이 증명한 수가 아닙니다.

| 소유자/경로 | 확인한 사실 | 복원 경계에 필요한 조건 |
| --- | --- | --- |
| [NewPipeDatabase](https://github.com/wnswlfhvkr-code/backtube/blob/4c7a34c0622d03c42217bba468b272eeb46512f4/app/src/main/java/org/schabi/newpipe/NewPipeDatabase.kt#L45) | AppDatabase 생성의 유일한 제품 builder 검색 결과. singleton 생성/close만 synchronized. 반환된 DB/DAO의 사용은 그 lock 밖. checkpoint cursor를 명시적으로 닫는 코드가 없고, import에서는 checkpoint를 호출하지 않음 | 새 획득 차단과 실제 쿼리/트랜잭션 종료 확인을 구분. checkpoint cursor close와 결과 검증도 필요 |
| [AppDatabase](https://github.com/wnswlfhvkr-code/backtube/blob/4c7a34c0622d03c42217bba468b272eeb46512f4/app/src/main/java/org/schabi/newpipe/database/AppDatabase.kt#L35) | 스키마9, DAO 10종을 외부에 반환. builder는 기존1→9 migrations 사용 | 임시 DB에도 같은 migration/Room schema 검증 경로 사용. 새 schema/destructive migration 도입 금지 |
| [HistoryRecordManager](https://github.com/wnswlfhvkr-code/backtube/blob/4c7a34c0622d03c42217bba468b272eeb46512f4/app/src/main/java/org/schabi/newpipe/local/history/HistoryRecordManager.java#L62) | DB와 history/search/stream/state DAO를 final 필드로 보관. saveStreamState 등의 실제 transaction은 IO 작업 | manager 생성 차단만으로 기존 작업을 막을 수 없음. 실제 SQL 완료 acknowledgement 필요 |
| [Player.destroy](https://github.com/wnswlfhvkr-code/backtube/blob/4c7a34c0622d03c42217bba468b272eeb46512f4/app/src/main/java/org/schabi/newpipe/player/Player.java#L894) | 종료 시 progress/session 저장 후 databaseUpdateDisposable.clear. 저장은 비동기 subscribe이며 성공을 기다리는 API가 아님 | 마지막 저장의 완료/실패를 먼저 받아야 함. clear를 flush 성공으로 취급하지 말 것 |
| [PlayerService](https://github.com/wnswlfhvkr-code/backtube/blob/4c7a34c0622d03c42217bba468b272eeb46512f4/app/src/main/java/org/schabi/newpipe/player/PlayerService.java#L244), [MediaBrowserImpl](https://github.com/wnswlfhvkr-code/backtube/blob/4c7a34c0622d03c42217bba468b272eeb46512f4/app/src/main/java/org/schabi/newpipe/player/mediabrowser/MediaBrowserImpl.kt#L54), MediaBrowserPlaybackPreparer | Player cleanup과 browser/preparer dispose가 별도. 소스 주석도 바인더 때문에 stopService가 완전 종료되지 않을 수 있음을 명시. browser/preparer도 DB 보관 | media button/Android Auto/binder 재진입 차단, reader/preparer 종료 확인. player가 null인 상태도 DB 독점 증거가 아님 |
| [LocalPlaylistFragment](https://github.com/wnswlfhvkr-code/backtube/blob/4c7a34c0622d03c42217bba468b272eeb46512f4/app/src/main/java/org/schabi/newpipe/local/playlist/LocalPlaylistFragment.java#L331) | onDestroy에서 disposable 처리 후 saveImmediate. 확인된 snapshot 저장 subscribe는 뷰 밖에서도 지속하도록 구현 | 화면을 닫은 뒤의 저장을 포함해 확인해야 함. debounce/drag 완료 쓰기 및 늦은 callback을 새 DB에 적용하면 안 됨 |
| LocalPlaylistManager, RemotePlaylistManager, BookmarkFragment, playlist dialogs | 주입받은 AppDatabase/DAO 보관, playlist 갱신/삭제/북마크를 비동기로 수행. 일부 UI는 view destroy 시 subscription을 취소 | 주입 경로도 포함한 operation epoch/완료 확인. 단순 direct getInstance 검색만으로 누락하지 말 것 |
| [FeedLoadService](https://github.com/wnswlfhvkr-code/backtube/blob/4c7a34c0622d03c42217bba468b272eeb46512f4/app/src/main/java/org/schabi/newpipe/local/feed/service/FeedLoadService.kt#L69), [FeedLoadManager](https://github.com/wnswlfhvkr-code/backtube/blob/4c7a34c0622d03c42217bba468b272eeb46512f4/app/src/main/java/org/schabi/newpipe/local/feed/service/FeedLoadManager.kt#L287) | manager는 SubscriptionManager/FeedDatabaseManager 보관. cancel은 AtomicBoolean 설정. 병렬 추출·buffer 뒤 DatabaseConsumer가 transaction 수행; 그 consumer 내부에는 cancel flag 검사 없음 | cancel 전에 큐에 들어온 batch/transaction이 끝났다는 확인 필요. dispose나 cancel 반환을 drain으로 보지 말 것 |
| [SubscriptionManager](https://github.com/wnswlfhvkr-code/backtube/blob/4c7a34c0622d03c42217bba468b272eeb46512f4/app/src/main/java/org/schabi/newpipe/local/subscription/SubscriptionManager.kt#L23), FeedDatabaseManager | DB/DAO를 필드에 보관, subscription/feed 갱신·삭제·group 작업. database()/subscriptionTable()로 외부에도 DB/DAO 노출 | 외부로 나온 참조까지 범위 포함. 오래된 batch가 복원 후 재개되지 않도록 generation 격리 |
| [SubscriptionImportWorker](https://github.com/wnswlfhvkr-code/backtube/blob/4c7a34c0622d03c42217bba468b272eeb46512f4/app/src/main/java/org/schabi/newpipe/local/subscription/workers/SubscriptionImportWorker.kt#L47) | doWork 이전 필드 초기화에서 SubscriptionManager 생성. coroutine chunk 수집 중 upsertAll. unique chain 이름 SubscriptionImportWorker | doWork 첫 줄의 검사만으로 constructor의 DB 접근을 막지 못함. worker 생성/DB factory 및 시작 경계 모두 필요 |
| [SubscriptionExportWorker](https://github.com/wnswlfhvkr-code/backtube/blob/4c7a34c0622d03c42217bba468b272eeb46512f4/app/src/main/java/org/schabi/newpipe/local/subscription/workers/SubscriptionExportWorker.kt#L34), ImportConfirmationDialog | export도 subscription DAO reader. import/export는 독립 WorkManager unique chain으로 enqueue; 이름은 SubscriptionImportWorker/exportSubscriptions | main DB에 접근하는 작업만 정지·지연. cancelAllWork로 무관 작업을 취소하지 않음. terminal state/Operation 완료와 실제 코드 종료는 별도 |
| [OfflineThumbnails](https://github.com/wnswlfhvkr-code/backtube/blob/4c7a34c0622d03c42217bba468b272eeb46512f4/app/src/main/java/org/schabi/newpipe/offline/OfflineThumbnails.kt#L43) | singleton executor와 대기열이 존재. 썸네일 fallback에서 새 getInstance 후 Room blocking reader를 실행 | media 파일을 변경하지 않고 DB fallback만 admission/epoch에 참여. 늦게 실행되는 queue도 새 DB를 열지 못해야 함 |
| [SparseItemUtil](https://github.com/wnswlfhvkr-code/backtube/blob/4c7a34c0622d03c42217bba468b272eeb46512f4/app/src/main/java/org/schabi/newpipe/util/SparseItemUtil.java#L110), FeedFragment, SelectFeedGroup/SelectPlaylist | late metadata callback의 stream upsert와 DAO-backed 선택/피드 readers | 비서비스 UI와 늦은 callback도 포함. writer뿐 아니라 cursor/readers도 닫힘 확인 |
| [App](https://github.com/wnswlfhvkr-code/backtube/blob/4c7a34c0622d03c42217bba468b272eeb46512f4/app/src/main/java/org/schabi/newpipe/App.kt#L83), 기존 병합 manifest | onCreate에 Phoenix process 조기 return. debug와 APK1036 continuous 병합 manifest 모두 기본 프로세스 WorkManagerInitializer, 별도 :acra/:phoenix 포함 | Application 조기 return만으로 provider 초기화를 차단했다고 볼 수 없음. 다른 프로세스의 main DB 미접근도 실증 대상 |

DB와 무관한 다운로드 미디어·mission 파일·오프라인 이미지·WorkManager 자체 DB는 복원 대상에서 제외합니다. 프로세스 재시작으로 진행 중 다운로드/재생이 영향을 받을 수 있으므로 별도 회귀 조건으로 남깁니다. 이 저장물은 이번 조사에서 열지 않았습니다.

## 3. 입증/미입증 경계

| 항목 | 현재 증거 | 판단 |
| --- | --- | --- |
| singleton 생성 시 동기화 | 기준 소스 | 확인. DAO 사용 전체의 상호배제는 아님 |
| 모든 DB 사용자의 중지/종료 | dispose/cancel/stop 호출은 있으나 공통 ack 없음 | 미입증 |
| 마지막 player/playlist 저장의 완료 | 종료 시 비동기 쓰기 경로 확인 | 미입증. graceful stop 도중에도 write 발생 가능 |
| close 이후 retained DAO 사용 여부 | 이번에 실제 Room 실행하지 않음 | 특정 DAO가 반드시 재오픈/실패한다고 단정하지 않음 |
| 새 프로세스에서 초기 DB 진입 차단 | WorkManager 자동 초기화 및 factory 확인 | gate 미구현; cold start 독점성 미입증 |
| SQLite/WAL 안전 교체·실패 원복 | 현재는 직접 copy 후 sidecar 삭제 | 없음 |
| 복사 오류에 의한 부분변경 | 앞선 R1 임시파일 진단에서 사전 검사 성공 후 두 번째 읽기 실패 재현 | 기존 위험 확인. 동시 live Room 손상 재현은 아님 |
| R2 취소/late result 차단 | 앞선 API23 9개 테스트 | 해당 preflight 경계만 확인. DB 교체 전체로 일반화하지 않음 |

WorkManager 공식 API도 취소 후 실행 코드가 계속 동작할 수 있음을 설명합니다. [WorkManager 취소 계약](https://developer.android.com/reference/androidx/work/WorkManager#cancelUniqueWork(java.lang.String)). 취소 Operation 완료나 CANCELLED 상태를 SQL 작업 종료 확인으로 대체하지 않습니다.

## 4. 대안 비교와 선택 조건

| 대안 | 장점 | 필수 범위/위험 | 판단 |
| --- | --- | --- | --- |
| A. 같은 프로세스의 모든 DB 작업에 owner lease/epoch + drain | 강제 재시작을 줄이고 저장 완료를 명시적으로 관리 가능 | 모든 raw DAO/DB 탈출 경로, 실제 SQL/cursor/transaction 수명, 서비스·UI·Worker·executor 참여가 필요. getInstance만 lock하거나 Rx doFinally에서 lease를 풀면 SQL 완료 전 해제될 수 있음 | 가장 넓은 변경. 두 파일 최소안으로 불가 |
| B. 기본 앱 프로세스 재시작 후 유지보수 모드 | 이전 프로세스의 객체를 이어 쓰지 않아 retained 참조 회수 범위를 줄임. 기존 재시작 경로 활용 가능성 | 이전 프로세스 종료 확인, durable restore marker, 모든 factory/startup 진입 차단, 자동 Worker/binder 재시작 처리, crash 복구 필요. 재생/다운로드 중단 비용 | 다음 단계에서 우선 실증할 설계. 검증 전 채택 확정 아님 |
| C. 별도 :restore coordinator 프로세스 | UI와 복원 파일 작업의 수명 분리가 명확해질 수 있음 | 기존 프로세스를 종료/격리하지 않으면 여전히 같은 DB 파일을 공유. 새 component/process 선언과 App/provider bootstrap 검토, 다중 프로세스 lock/journal 필요 | 단순 process 이름 변경으로 해결 불가. B가 성립하지 않을 때만 범위 확대 검토 |
| D. close + atomic move만 추가 | 코드가 작아 보임 | 기존 writer/cursor, WAL tuple, 복구 중 재진입을 막지 못함. 단일 파일 원자 rename은 여러 sidecar의 공동 원자 교체가 아님 | 안전한 안으로 채택하지 않음 |

ProcessPhoenix3.0.0은 helper process에 기존 PID를 전달하고 종료·재시작을 수행합니다. 호출 이후 기존 프로세스 동작은 라이브러리 계약상 정의되지 않으며, DB transaction/rollback 절차를 제공하지 않습니다. [정확한3.0.0 source](https://github.com/JakeWharton/ProcessPhoenix/blob/3.0.0/process-phoenix/src/main/java/com/jakewharton/processphoenix/ProcessPhoenix.java), [PhoenixActivity](https://github.com/JakeWharton/ProcessPhoenix/blob/3.0.0/process-phoenix/src/main/java/com/jakewharton/processphoenix/PhoenixActivity.java). 이를 호출한 뒤 원래 프로세스에서 파일 교체를 계속하는 설계는 금지합니다.

Android의 Application.onCreate 순서 보장은 content provider를 제외합니다. [공식 초기화 계약](https://developer.android.com/reference/android/app/Application#onCreate()), [App Startup](https://developer.android.com/topic/libraries/app-startup). 따라서 B에서도 onCreate에 복원 함수 하나를 넣는 것으로 끝나지 않습니다.

## 5. B를 검증하기 위한 최소 모듈 범위

아래는 미래 구현의 경계 목록이며 지금 변경한 파일 목록이 아닙니다. 파일 수 확정치는 최초 prototype이 증명한 진입점에 따라 달라집니다. DB schema/권한 변경은 필요 조건으로 제안하지 않습니다.

| 모듈 | 필요한 책임 |
| --- | --- |
| 새로운 RestoreCoordinator + durable journal/file-operations 경계 | 하나의 restore ID, 단일 소유자, 단계 기록, 취소 cut-off, crash recovery, 원복. 사용자 파일 경로를 ZIP entry 이름에서 만들지 않음 |
| NewPipeDatabase와 DB builder | normal-open와 maintenance-open 구분; stage 전용 DB 이름으로 같은 migrations/Room validation 재사용; restore pending 시 일반 획득 fail closed/defer. UI thread에서 coordinator를 기다리는 blocking lock은 사용하지 않음 |
| App/bootstrap, 실제 provider/WorkManager 초기화 진입 | pending journal을 일반 DB 사용보다 먼저 발견. worker constructor를 포함한 조기 접근 차단. 실제 필요가 입증되면 미래 startup 설정 변경 범위를 별도 명시; 현재는 어떤 설정도 변경하지 않음 |
| PlayerService/Player 및 browser/preparer | 신규 명령·바인더 작업 admission 중지; 마지막 progress/session 저장 ack; reader 종료 ack. 실패/timeout이면 교체 시작 금지 |
| LocalPlaylist/UI/History/Feed/Subscription managers | 종료 과정의 save와 buffered writes를 old epoch에서 완료; 오래된 callback은 새 DB에 적용 금지. A를 택하면 이 범위 전체의 실제 작업 lease가 필수 |
| FeedLoadService/Manager, subscription import/export enqueue/Worker | 새 일감 차단, 기존 작업 완료/종료 확인. 취소된 작업을 무작정 재enqueue하지 않음. 복원 후 이전 batch가 그대로 재개되지 않는 정책 필요 |
| OfflineThumbnails와 SparseItemUtil 등 late reader/writer | stale executor/metadata callback의 factory 진입 차단/지연. 오프라인 media 파일은 변경하지 않음 |
| ImportExportManager/BackupFileLocator/ZipHelper | private stage로만 복사·한도·중복 DB entry 거부·파일 식별. 현재 extractDb의 live Files.copy 호출은 coordinator commit 경로로 대체해야 함 |
| BackupRestoreSettingsFragment/NavigationHelper 경로 | 파일 준비→명시적 복원 요청만 위임. 설정/finish/재시작은 commit 결과 후 한 번만 수행. 현재 settings dialog의 dismiss/click 중복 finish도 coordinator와 함께 정리 필요 |
| 테스트용 file-ops/DB/worker fixtures | 사용자 경로와 분리된 전용 DB, IO/lifecycle/crash 실패 주입. production에 test-only 상태 머신을 넣지 않음 |

B에서도 전역 factory gate 하나만으로 모든 조건이 끝난다고 가정하지 않습니다. 이미 열린 다른 프로세스 handle, raw builder 우회, worker construction, 자동 재시작이 모두 차단됐다는 시험이 필요합니다. FileChannel lock은 협조하는 코드의 소유권 표식일 뿐 SQLite 자체의 기존 handle을 닫아주지 않습니다.

### 미래 API 계약의 최소 형태

이름은 설계용이며 현재 코드에 추가하지 않았습니다.

| 계약 | 입력/성공/실패의 의미 |
| --- | --- |
| prepareArchive → StagedCandidate | 취소 가능한 IO, 검증된 private archive/DB 식별만 반환. global singleton을 열거나 live DB를 쓰지 않음 |
| requestRestore → RestoreId | durable 요청과 admission 차단. UI에 성공을 먼저 알리거나 설정을 저장하지 않음 |
| stopAndAcknowledge(oldEpoch) → StopResult | 사용자에게 확인된 player/playlist 마지막 저장과 실제 작업 종료의 ack. disposable 취소 알림과 구분 |
| enterMaintenance(id) → ExclusiveDbOwner | process/진입점/파일 소유권이 확인된 유일한 token. 일반 getInstance guard는 마지막 방어이며, 정상 진입점이 maintenance를 먼저 지연 처리해야 함. uncaught exception이나 UI blocking을 정상 UX로 사용하지 않음 |
| installValidateOrRollback(owner, stage) → RestoreResult | PREPARE_FAILED/ABORTED_BEFORE_SWAP/RESTORED/ROLLED_BACK/RECOVERY_REQUIRED를 구분. 성공·원복 결과는 실제 reopen 검증 후만 반환 |
| release(VerifiedCommit) | durable epoch 전환 뒤 새 owner로 정상 진입 허용. settings 상태는 별도 pending/imported/declined/failed로 구분 |

## 6. 복원 프로토콜과 실패 처리

1. **STAGING**: 원본 archive를 bounded IO로 private stage에 복사합니다. 최종 후보는 live DB와 같은 파일 시스템의 전용 이름으로 준비하며, archive·후보·원복 세트·validation sidecar가 공존할 공간을 고려합니다. 용량 사전 확인만으로 IO 성공을 보장하지 않습니다. 사용자가 고른 provider를 commit 시 다시 읽지 않습니다. 일반 텍스트/손상 ZIP/중복 DB entry/없는 DB/크기·시간 초과/취소는 여기서 종료하며 live DB와 설정은 쓰지 않습니다. 네트워크·외부 매체에 원복 사본을 만들지 않습니다.
2. **STAGED_VALID**: 분리된 SQLite 연결로 header, integrity_check, 필요 foreign-key/schema 검사를 수행합니다. Room의 기존 migrations를 별도 stage DB에 실행하고 schema validation까지 성공한 후보를 만듭니다. stage에서도 checkpoint 결과와 연결 종료를 확인하고, migration 결과가 stage WAL에만 남은 상태로 main 파일만 설치하지 않습니다. 별도 standalone 재오픈으로 후보가 자체 완결적인지 검증합니다. 낮은 버전을 단순히 스키마9와 다르다는 이유로 거부하지 않습니다. 지원되지 않는 미래 버전/마이그레이션 실패는 live 변경 전 거부합니다. 이 지원 정책과 Android 실제 동작은 아직 미검증입니다.
3. **QUIESCING**: 새 UI/서비스/Worker/metadata 작업 admission을 닫되, 기존 승인된 마지막 player/playlist 저장은 old epoch에서 ack까지 완료합니다. 실패·작업 종료 미확인·deadline 초과 시 복원을 시작하지 않습니다. Rx dispose나 WorkManager cancel 결과만으로 이 단계를 완료하지 않습니다.
4. **RESTART_HANDOFF**: private journal에 검증된 stage 식별·복원 의도만 durable하게 기록하고 기존 프로세스 재시작을 요청합니다. 호출 후 원래 프로세스에서는 DB/file 작업을 계속하지 않습니다. 새 유지보수 소유자는 기존 writer process 종료와 모든 진입점 fencing을 확인해야 합니다. 확인 실패 시 다음 단계 금지입니다.
5. **EXCLUSIVE_OLD_CLOSED**: 일반 DB opens가 막힌 상태에서 이전 DB의 SQLite recovery/checkpoint를 소유한 연결로 수행합니다. cursor를 닫고 checkpoint 결과를 검증하고 DB를 닫습니다. busy/error/다른 handle 의심 시 수동 sidecar 삭제나 swap으로 진행하지 않습니다. 원래 `.db/-wal/-shm/-journal` 존재 조합을 기록합니다. checkpoint는 논리 내용 보존을 검사해야 하며 파일 바이트 동일성만으로 판정하지 않습니다.
6. **OLD_SAVED**: 닫힌 이전 DB 세트와 설정을 변경하지 않는 원복 기준점을 보존합니다. 파일마다 old/new 존재 여부와 이동 의도를 journal에 기록해 어느 동작 사이에 죽어도 판단할 수 있어야 합니다. journal/후보/원복 파일·부모 디렉터리의 durability 순서를 실제 Android 파일 시스템에서 입증해야 합니다. 공간 부족·지원하지 않는 atomic move/fsync는 fail closed입니다. live 파일 위 Files.copy fallback은 금지합니다.
7. **NEW_INSTALLED**: verified candidate를 같은 파일 시스템의 live 위치에 설치합니다. 이전 DB의 WAL/SHM을 후보에 결합하지 않습니다. sidecar를 먼저 무조건 삭제하지 않습니다. multi-file 원복 가능성과 old set 보존이 확인된 상태에서만 stale sidecar를 격리합니다. 한 파일 rename의 원자성과 전체 교체의 crash 내구성은 별개입니다.
8. **VERIFIED_DB_COMMIT**: 여전히 일반 admission을 막은 상태에서 정상 factory와 같은 설정으로 새 DB를 열어 Room schema/필수 DAO 읽기/무결성 확인 후 닫거나 새 owner에 인계합니다. 후보 존재·식별이 다르면 Room의 빈 DB 생성으로 넘어가지 않습니다. 하나라도 실패하면 새 handle을 닫고 old set을 원복·재오픈 검증합니다. 원복 실패는 gate를 닫아둔 복구 필요 상태이며 원복 사본 삭제/무한 재시도/정상 앱 진입은 금지합니다.
9. **RELEASED**: 검증된 commit/epoch를 durable하게 기록한 뒤에만 일반 진입을 재개합니다. 새로운 서비스/UI는 새 DB owner로 만들고 이전 객체를 재사용하지 않습니다. 이 시점 뒤 정상 사용자가 새로 쓴 자료를 오래된 원복본으로 자동 덮어쓰면 안 됩니다.
10. **SETTINGS/CLEANUP**: DB commit 후에만 기존 선택적 설정 질문을 제시합니다. 취소하면 이전 설정을 유지하고 새 DB는 유지합니다. 설정 실패를 DB+설정 전체 성공으로 표시하지 않습니다. 현재 설정을 credentials 포함한 별도 dump로 복제하거나 로그에 내보내지 않습니다. DB 실패 단계에서는 설정 edit/clear/마지막 URI 쓰기가 없어야 합니다. DB+설정 공동 원자성은 별도 미해결 범위입니다. 원복 파일 정리는 RELEASED 이후 안전성을 확인해 idempotent하게 수행합니다.

SQLite 공식 문서도 WAL이 DB의 지속 상태 일부임을 설명합니다. 이를 main 파일에서 임의로 분리하지 않습니다. [SQLite WAL](https://www.sqlite.org/wal.html). integrity_check만으로 foreign-key 오류를 검사하지 못하므로 별도의 확인이 필요합니다. [SQLite integrity/foreign-key 검사](https://www.sqlite.org/pragma.html#pragma_integrity_check). SHM은 WAL index용 임시 자료이며 authoritative backup 데이터로 가정하지 않습니다. 실제 SQLite recovery에 따라 재생성될 수 있지만, 소유권 확인 전 임의 삭제하거나 다른 세트와 혼합하지 않습니다.

### 취소와 동시 요청의 cut-off

STAGING/STAGED/QUIESCING 등 파괴적 이동 이전에는 취소 후 old DB로 정상 복귀할 수 있습니다. OLD_SAVED부터 검증 commit 전까지는 UI 취소가 파일 이동을 임의로 중단시키지 않습니다. coordinator가 완전한 old set의 rollback/reopen을 확인한 뒤에만 취소 완료를 반환합니다. 새 요청은 그동안 활성 교체를 대체할 수 없습니다. RELEASED 이후 DB commit을 취소한다고 오래된 old set을 적용하지 않습니다. 설정 질문의 취소는 기존 설정 유지에만 해당합니다.

### crash journal의 판단 기준

| 발견 상태 | 일반 DB 진입 | 복구 원칙 |
| --- | --- | --- |
| stage만 존재, old save 전 | 우선 복구 소유자 판단 | live 그대로 검증; 불완전 stage만 정리 가능. 사용자 자료 파일은 정리 대상 아님 |
| OLD_SAVED 중 일부 이동 또는 install 이전 | 금지 | per-file intent/존재 상태로 old set 완성. 불명확하면 사본 보존·복구 필요 표시 |
| NEW_INSTALLED, 아직 검증/commit 전 | 금지 | 새 DB를 서비스에 노출하지 않고 검증 또는 old rollback |
| VERIFIED_DB_COMMIT, RELEASED 전 | 금지 | 새 DB 재검증 후 release; 실패 시 old rollback 가능. 아직 정상 writer를 허용하지 않았다는 증거 필요 |
| RELEASED 이후 cleanup 미완료 | 정상 데이터 epoch 유지 | 오래된 old set으로 자동 rollback 금지. 기존 새 DB 자료를 보존하며 cleanup 재개 |
| journal 손상/동시 coordinator/rollback 오류 | 금지 | destructive 동작 중단, old/stage 보존. 임의 추정 경로로 DB를 만들지 않음 |

## 7. 실패주입 테스트 계획 — 모두 NOT RUN

테스트 DB는 임의 이름의 전용 앱 cache/internal fixture 디렉터리에 생성하며 기본 `newpipe.db` singleton이나 실제 설정 파일에 연결하지 않습니다. 하나의 테스트 root에만 한정된 FileOps 실패 주입을 사용하고 OS 권한 변경·실제 디스크 고갈·사용자 backup 복원은 하지 않습니다. WorkManager 테스트도 격리된 scheduler/worker fixture를 사용합니다. Room/기기 시험과 JDK 파일 모델 시험 결과를 따로 집계합니다.

| ID | 주입/시나리오 | 반드시 확인할 oracle |
| --- | --- | --- |
| T01 | 정상 현재 스키마 DB, marker가 서로 다른 old/new | 새 Room DAO 결과 정확, old snapshot 별도 보존, 설정은 기존 값 |
| T02 | 지원하는 예전 스키마별 backup | stage migration/WAL checkpoint/standalone reopen 성공; live old unchanged until commit; destructive fallback 없음 |
| T03 | 텍스트/없는 DB/중복 DB entry/잘린 ZIP/CRC/central directory 손상 | live DB tuple·설정·URI 불변, stage 정리만. R2의 central-directory 한계를 새 stage 경로가 막는지 실증 |
| T04 | SQLite header/페이지/foreign key/Room schema 손상, 미래 user_version | stage에서 거부. 실제 SQLite/Room API로 확인, JDK 문자열 검사를 대체 증거로 사용하지 않음 |
| T05 | stage provider read/close IOException, checksum mismatch, decode/entry/시간 상한 | live 불변. blocking provider가 늦게 돌아와도 DB 변경 없음 |
| T06 | 취소, 화면 제거, 두 번째 restore 선택 | 파괴적 이동 전 이전 요청 무효; 이동 중이면 rollback/reopen 완료 후 취소 반환. 이전 stage/콜백이 새 요청을 덮지 않음 |
| T07 | player 마지막 progress save를 latch로 보류/실패 | restore는 ack 전 old save/close/swap 순서를 건너뛰지 않음. queue·위치 복원 회귀 |
| T08 | playlist onDestroy/saveImmediate·debounce 쓰기 보류 | view destroy 완료로 오판하지 않음; 확인된 old snapshot 유실/새 DB 오염 없음 |
| T09 | Feed buffered DatabaseConsumer transaction 보류 후 cancel | cancel 반환 후에도 진행 중인 작업을 관측; drain 전 swap 0회 |
| T10 | SubscriptionImportWorker constructor/doWork 및 chunk transaction 보류 | gate pending에서 DB owner 획득 0회, old batch가 new epoch에 쓰기 0회 |
| T11 | export reader/active cursor/Room Flowable query 보류 | cancel/상태 terminal만으로 drain 판정 안 함; 실제 쿼리/커서 종료 확인 |
| T12 | media browser bound·media button/binder 재진입 | player stop 후 새 reader/prepare/save가 gate를 통과하지 못함 |
| T13 | OfflineThumbnails queued fallback·SparseItemUtil 늦은 upsert | 복원 사이 old generation의 read/write 없음; 오프라인 media/이미지 원본 변경 없음 |
| T14 | 기존 PID 종료 전 새 coordinator 시작 | 종료/ownership 확인 전 파일 변경 0회; journal로 재진입 차단 |
| T15 | provider→WorkManager 초기화가 Application.onCreate보다 먼저 접근 | App 훅만 있는 결함을 RED로 재현; factory/worker bootstrap 차단 후 GREEN 필요 |
| T16 | 기본 프로세스 재시작/보조 프로세스/WorkManager 자동 재스케줄 경합 | 모든 DB opener가 동일 restore ownership 준수. `:acra/:phoenix` 무접근을 추정으로 통과시키지 않음 |
| T17 | WAL에만 committed row 존재, checkpoint busy/read error/close 지연 | committed row 보존; busy/error이면 swap 0회. checkpoint cursor 누수 없음 |
| T18 | old main/WAL/SHM/journal 조합별 부분 rename 실패 | 재시작 후 완전 old set으로 복구 또는 gate closed. old WAL이 new DB에 결합되지 않음 |
| T19 | 후보 install/move/fsync/공간 오류를 FileOps로 주입 | live-copy fallback 없음, old snapshot 재오픈 성공, 설정 불변 |
| T20 | 새 Room reopen/migration/schema/integrity 검증 실패 | 새 handle close→old set rollback→old 논리 내용·설정 확인 |
| T21 | rollback rename/reopen 자체 실패 | 원복본 삭제 없음, 정상 앱 DB 진입 금지, 오류 상태 durable |
| T22 | journal 각 write·fsync·파일 이동 전후 강제 프로세스 종료 | 새 cold start의 복구 결과가 old 또는 검증된 new 하나로 수렴. JVM 예외 주입만으로 crash 시험 PASS 주장 금지 |
| T23 | VERIFIED_DB_COMMIT 뒤 RELEASED 전/뒤 crash와 cleanup 반복 | release 전 admission0; release 뒤 새로 쓴 row가 old rollback으로 사라지지 않음 |
| T24 | 설정 가져오기 취소·JSON 손상·commit false | DB commit 전 설정/URI edit0. DB 성공 뒤 설정 실패를 별도 보고; 원래 설정의 메모리/디스크 결과를 실제 Android에서 확인 |
| T25 | 재생/듣기모드/첫 선택A·retryA·완료B/영상·오디오 저장/썸네일/중복 Toast | 기존1036 의미 유지. 테스트 미디어만 사용; 전체 기존 검증의 자동 재사용 금지 |
| T26 | 진행 중 다운로드/대기열·저장 목록·기존 named preferences 후 restart/rollback | 생성한 fixture만 대상으로 데이터·큐순서·위치·미디어 해시 보존; 실제 다운로드 파일은 접근하지 않음 |

첫 구현 실증은 T07/T08/T09/T10/T12/T15/T17/T18을 통과시키는 ownership/startup 경계부터 진행해야 합니다. file swap 프로토콜을 먼저 live 경로에 붙이고 나중에 경계를 추측하는 순서는 허용하지 않습니다. 최소 API23 및 한 최신 API에서 Room/WorkManager/프로세스 시험을 나눠 실행해야 하며, 실기기·다른 파일 시스템의 durability 결과가 없으면 그 범위를 명시해야 합니다.

## 8. 구현 착수/완료를 가르는 증거

- 모든 관측된 DB owner/late callback에 대해 admission과 실제 종료 확인의 담당자가 명확해야 합니다. 빠진 경로가 있으면 독점 완료를 주장하지 않습니다.
- 재시작 방식에서는 이전 프로세스의 종료와 새 process startup fencing이 실제 Android에서 입증돼야 합니다. ProcessPhoenix 메서드 반환이나 새 PID 존재만으로는 부족합니다.
- stage validation과 rollback은 실제 SQLite/Room/migrations 및 synthetic WAL 데이터로 확인해야 합니다. main DB 스키마 변경은 필요하지 않습니다.
- 원본 파일 교체 이전/중간/재오픈/원복/cleanup의 실패와 crash마다 상태 및 보존 oracle이 있어야 합니다.
- 원복이 실패하면 서비스/일반 DB를 열지 않고 사본을 보존해야 합니다. 파일 copy fallback이나 접근 제한 우회는 없습니다.
- 이번 문서는 설계/증거 보존만 완료했습니다. R1은 계속 미해결이고, 이전 R2 테스트는 이 새 프로토콜의 성공 증거가 아닙니다.
