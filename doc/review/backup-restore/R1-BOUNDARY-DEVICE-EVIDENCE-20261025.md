# R1 복원 경계: 실제 소스·더미 DB 검증

기준: `6095d418bb36e6bcc3a38d51a40fe99ac17a12f3`.
웹 검수 자료 `libfile_b8b65f28f7e4819188fe6140b0271201`
(`backtube-6095d418-runtime-review.zip`, SHA-256
`29eae59af9eee5fda98cc87529b423a3932aaab46aa081681afb964959e5877c`)
를 이 실행기에 가져와 305,776바이트·23개 항목·CRC·해시를 확인했다.
자료의 제품 패치는 비어 있다. 현재 소스의 R2 사전 검사와 1036 history holder 변경도
유지되어 있다. 웹 자료의 Python 테스트 6개는 직접 실행해 통과했지만, SQLite 파일 단계
**모델**일 뿐 Android Room·프로세스 검증이 아니다.

## 실제 원인 재확인

- `BackupRestoreSettingsFragment.importDatabase`의 ZIP 사전 검사는 IO에서 실행되지만,
  성공 콜백은 main thread에서 `importValidatedDatabase`로 들어간다.
- 그 함수는 `ImportExportManager.extractDb`를 호출한다. 현재 `ZipHelper`의 대상 경로
  overload는 `Files.copy(..., REPLACE_EXISTING)`으로 현재 DB를 직접 덮어쓴다. Room
  `NewPipeDatabase.close()`와 ProcessPhoenix 재시작은 성공 후 `finishImport`에서 호출된다.
- singleton의 생성·닫기 lock은 반환된 DAO, 늦은 callback, cursor, Worker 및 최종 저장을
  통제하지 않는다. `Player.destroy`는 저장을 요청한 다음 disposable을 clear한다.
  `SubscriptionImportWorker`는 `doWork`에 들어가기 전에 필드에서 manager를 만든다.
  병합 manifest에는 `InitializationProvider`의 WorkManager 초기화가 있으며,
  `App.onCreate`의 Phoenix 분기만으로 provider 이전 진입을 막을 수 없다.

## 이번 실행의 RED 진단과 안전한 부분 경계

사용자 배포 앱과 패키지가 다른 전용 debug 테스트 앱의 cache 아래에서만 임의 이름의
Room/SQLite DB·ZIP·임시파일을 만들었다. 테스트 코드는 기본 `newpipe.db`, 기본
설정과 사용자 백업을 직접 열거나 수정하지 않았다. 다만 AndroidJUnitRunner가 대상
debug `App`을 시작하므로 그 debug 패키지의 기본 설정을 앱 초기화가 읽거나
마이그레이션할 수 있다. 해당 debug 기본 설정의 불변성은 격리·검증하지 않았으며,
사용자 배포 패키지의 자료 보존 증거로 이 사례를 사용하지 않는다.

| 검사 | 실제 결과 | 의미의 한계 |
| --- | --- | --- |
| JVM `RestoreCopyRiskTest` | 1/1 통과: 사전 검사 후 두 번째 ZIP 읽기 실패에서 대상 파일 일부 변경 재현 | 임시 파일 진단. 통과는 **위험 재현**이며 안전성 통과가 아님 |
| API23 열린 더미 Room DB + 직접 복사 실패 | 통과: 기존 Room을 연 상태에서 DB 파일 경로의 바이트 변경 관측 | 실제 사용자 DB 손상이나 모든 기기의 Room 동작을 단정하지 않음 |
| API23 같은 읽기 실패를 별도 stage로 추출 | 통과: 부분 stage가 생겨도 기존 더미 Room DB의 메인 파일·읽기 결과 유지 | 아직 commit·교체·원복 단계가 아님 |
| API23 닫힌 Room 후보 DB를 stage로 추출 후 재열기 | 통과: 후보 marker 확인, 기존 Room marker와 파일 유지 | 현재 스키마 한 사례. 기존 migration·무결성 전 범위는 미검증 |
| API23 독립 SQLite WAL 사례 | 통과: 열린 WAL에만 커밋된 marker는 메인 파일 단독 복사본에 없음 | 원본 DB의 소유권·checkpoint·실제 앱 백업의 데이터 손실 여부는 별도 검증 필요 |

최종 API23 테스트는 `RestoreBoundaryDeviceDiagnosticTest` 4/4 통과했다.
첫 Android 실행은 Gradle UTP의 앱 설치 commit 응답 지연으로 검사 0개였으며,
기기를 재부팅한 뒤 동일 빌드의 앱·테스트 APK를 공식 adb 경로로 설치해 검사했다.
첫 4개 시도 중 WAL fixture가 결과를 반환하는 PRAGMA를 잘못 호출해 1개 실패했다.
fixture 호출만 고쳐 최종 4/4를 확인했다. 제품 소스는 이 과정에서 바꾸지 않았다.

## 구현 차단과 안전한 다음 경계

별도 stage로 받아 검증 실패 시 기존 DB를 건드리지 않는 단계는 구현 가능한 것으로
보인다. 그러나 **안전한 live DB 교체는 아직 입증되지 않았다**. 아래 조건을
하나라도 추정으로 통과시키면 이전 DB/WAL 또는 복원 후보가 손상될 수 있다.

1. player·playlist·feed·subscription·thumbnail·media browser의 기존/대기 DB 작업을
   중지하고 실제 SQL/cursor 종료 및 마지막 저장 성공을 확인하는 공통 계약이 없다.
2. 이전 프로세스 종료와 새 프로세스의 일반 DB 진입 차단이 없다. 특히 provider,
   WorkManager worker 생성, 바인더/서비스 재진입을 `App.onCreate`만으로 막을 수 없다.
3. 실제 Room migrations/SQLite integrity·foreign-key 검사, WAL checkpoint/닫기,
   DB와 sidecar의 보존, durable 단계 journal, 새 Room 재열기, 실패 시 원복·재검증이
   제품 경로에 존재하지 않는다. Python 모델은 이 항목들을 입증하지 않는다.
4. 취소·중복 요청·공간 부족·각 파일 이동/동기화 지점 강제종료·원복 자체 실패의
   Android 실패주입은 미실행이다. API35·실기기 파일 시스템도 미실행이다.

이 작업에서는 `close()` 한 번 또는 atomic move 한 번을 안전한 R1 수정으로 적용하지
않았다. 현재 구조에서 실제 복원을 유지하려면 [R1 설계](R1-RESTORE-BOUNDARY-DESIGN.md)의
재시작 유지보수 소유자·초기 진입 차단·journal을 먼저 **더미 DB에서** 단계별로
실증해야 한다. 좁은 임시 보호책은 독점성이 증명될 때까지 파괴적인 DB 가져오기를
명시적으로 거부하는 것이지만, 이는 복원 기능을 일시 중단하므로 이번 진단 브랜치에
적용하지 않았다. 새로운 DB 교체 제품 코드, APK, main 병합, 출시는 없다.
