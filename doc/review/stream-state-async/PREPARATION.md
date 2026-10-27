# StreamMiniInfoItemHolder 비동기화 후보 검토 준비

상태: **후보 패치 대기**. 제품 코드·테스트 소스 수정, 최적화, 삭제는 아직 적용하지 않음.

## 입력과 기준선

- 리뷰 Library ID: `libfile_21cff49cecf081918af2c69b90501de3`
- file ID: `file_00000000787c823098bba78845fa1b03`; version_id는 서버 미제공(null).
- 입력 `review-v2.zip` SHA-256: `0960cfa1c636cf460d73f254b7d7d20b0d48af7461d263b257f2fe3204a6308e`.
- 소비 실행기에서 직접 materialize, 존재·가독성·SHA·CRC 및 13개 구성 파일 확인.
- 원격 main: `36c37ab5fc77d7a5293a44f25f33f80fc8f6faa4`.
- 최신 보존 원격/로컬 기준: `e7cf13eb22043d67e465af7ca804f763e280b62b`.
- 실제 APK1035 제품 기준: `486fe10f24c2ed1a1e38d3b8769fd924cc25cac5`.
- 이 작업은 위 기준에서 분리한 `perf/stream-state-bind-async-review-20261024` 로컬 브랜치.

## 독립 소스 확인

1. InfoListAdapter.java의 onBindViewHolder → InfoItemHolder.updateFromItem 호출이 존재.
2. StreamMiniInfoItemHolder.java의 updateFromItem 및 updateState에
   loadStreamState(infoItem).blockingGet()[0] 두 군데 존재.
3. HistoryRecordManager.loadStreamState(InfoItem)는 Single.fromCallable 안에서
   DAO Flowable의 blockingFirst를 사용하고 subscribeOn(Schedulers.io())로 예약.
   SQL 실행 스레드와 별개로 blockingGet 호출자의 대기는 남는다.
4. RecyclerView bind가 UI 호출 경로라는 정적 분석이다. 실제 StrictMode/frame-time 측정,
   실기기 지연·성능 개선 폭을 입증한 결과가 아니다.
5. updateState의 이 InfoItemHolder 계열에 대한 현재 직접 호출은 전역 검색에서 찾지 못했다.
   LocalItemListAdapter의 동명 호출은 다른 LocalItemHolder 계열이다. 이것을 근거로
   메서드 삭제를 제안하지 않으며 후보가 제공할 API/직접 호출 테스트를 별도로 검토한다.
6. StreamInfoItemHolder와 grid/card 계열의 상속 및 공유 부모 holder 영향까지 확인 필요.

## 재사용할 기존 패턴과 주의

- VideoDetailFragment.checkUpdateProgressInfo: 이전 Disposable dispose,
  IO 예약, observeOn(AndroidSchedulers.mainThread()), 오류/빈 상태 처리.
  이 패턴은 비동기 전달 참고이며 RecyclerView 재사용 보호를 자동 제공하지 않는다.
- OfflineThumbnails.bind: 취소 플래그 + current identity 확인 + main Handler 전달,
  queued callback 제거/Coil Disposable dispose. 그대로 복사하지 말고 소유권 계약 참고.
- 현재 InfoListAdapter에는 onViewRecycled 정리 hook이 없고 InfoItemHolder에도
  비동기 구독 소유권이 없다. 후보의 취소/재사용 수명 설계를 검토해야 한다.
- 단순 observeOn 추가나 다른 동기 API로 치환해서는 호출자 blocking이 사라지지 않는다.
- 후보는 같은 URL/서로 다른 serviceId, 같은 항목의 연속 update 요청까지 구분해야 한다.
- full bind와 updateState의 duration/live 표시 조건은 현재 서로 다르다.
  최적화에 섞어 기존 표시 규칙을 임의 변경하지 않는다.

## 후보 수신 후 RED→GREEN 검증 명세

후보가 제공할 테스트를 우선 검토하고 기존 코드에서도 실행한다. 미존재 테스트를
실행했다고 주장하지 않는다. DB 지연 fixture는 SingleSubject/수동 scheduler 등으로
제어하며 테스트 종료 시 반드시 해제하여 main thread를 무기한 묶지 않는다.

| 사례 | 확인해야 할 계약 |
|---|---|
| bind/update + DB 미완료 | DB 결과를 풀기 전에 bind가 반환하고 main queue sentinel이 실행됨; 단순 FPS/몇 ms 개선 주장과 구분 |
| A bind → B rebind, B 후 A 완료 | B의 indicator를 A가 덮어쓰지 않음; 현재 key와 세대 확인 |
| 같은 A의 request 1 → 2 역순 완료 | 이전 요청이 최신 progress를 되돌리지 않음 |
| recycle 후 늦은 결과/이미 main에 예약된 결과 | 재사용/해제된 행의 뷰에 접근하거나 stale progress를 표시하지 않음 |
| preference off 전환/새 bind | 기존 progress 즉시 정리, 늦은 결과가 다시 표시하지 않음 |
| empty/null state, DAO 오류 | 기존 행의 bar가 남지 않고 전역 미처리 Rx 오류가 발생하지 않음 |
| duration 0/live/일반 영상 | 기존 duration/visibility/animation 규칙 및 단위(ms→sec) 유지 |
| mini/full/grid/card, adapter recycle 경로 | 공통 holder 변경이 각 레이아웃과 실제 RecyclerView 수명에서 안전 |
| stop/destroy/recreate/빠른 스크롤 | 구독 수명과 늦은 callback 정리; DB 작업의 완료가 화면을 붙잡지 않음 |

현재 HistoryRecordManagerTest는 주로 search-history DB 동작을 검사한다.
StreamItemAdapterTest는 미디어 품질 Spinner adapter라 이 행의 비동기 회귀 테스트로
오인하지 않는다. 기존 1035의 574 JVM/Android 기록은 과거 기준선 참고일 뿐
향후 패치의 결과로 재사용하지 않는다.

## 이번 준비 단계의 실행 결과

- Library 소비자 로컬 읽기/SHA/CRC: PASS.
- 리뷰 manifest 4,637개 파일 SHA 대조: PASS, mismatch 0.
- 원본 ZIP 4,638번째 항목 values-iw는 디렉터리를 가리키는 Git symlink.
- Java/Kotlin 전체 663개, 지정 source-set 아래 661개: 경로/목록 집계만 확인.
- coverage CSV: targeted-deep 23, deep-reviewed-prior 10, reference-scan 1211,
  inventory-only 3315, config-or-script-scan 78. 이 분류를 전체 심층 검수로 확대하지 않음.
- 비동기 후보 테스트/RED→GREEN/Gradle 컴파일/Android 런타임/프레임 측정: NOT RUN.
  후보 미수신이며 기존 클라우드 emulator는 종료 상태; 실행기는 명령·네트워크 사용 가능.
- asSequence 최적화 철회 유지. Compose Preview 9개 유지. 미사용 제품 코드 삭제 없음.
- Windows, 사용자 PC, main 병합, 공개 출시, 외부 비밀정보 전송 없음.
