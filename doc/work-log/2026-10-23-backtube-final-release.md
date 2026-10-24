# 백튜브 최종 배포 준비 작업 일지 — 2026-10-23

상태: **수정·검증·APK1032 Library 전달 완료. GitHub 공개는 첨부 인증 문제로 차단**.
제품 수정과 PR의 JVM·Android 23·35 검증을 완료하여 PR #24를 병합했다.
병합 후 main CI도 모두 통과했다. GitHub APK 첨부의 기존 HTTP 401은 복구 근거가 없어
재시도하지 않았다. 첨부·다운로드 해시 검증 전에는 공개 완료로 보지 않는다.
시간은 UTC 기준이다. 사용자 PC와 Windows 검사는 사용하지 않았다.

## 요청과 범위

- 사용자는 APK1030을 배포 버전으로 선택한 뒤, 남은 재생 위치 문제와 검증 실패를
  해결하여 최종 배포본을 가져오도록 요청했다.
- 기존 오프라인 영상 재생, 오디오 백그라운드 재생, 종류별 저장본 문구,
  로컬 썸네일, 중복 저장 Toast, 큐 순서와 이어듣기를 보존한다.
- 같은 패키지와 기존 서명으로 업데이트 가능한 APK를 준비한다. 저장소 공개범위,
  권한, 인증 및 추가 과금 설정을 바꾸지 않는다.
- 이 일지는 저장소 기록이다. 서버 옵시디언 통합은 별도 담당 범위다.

## 재현과 원인

### 일시정지 위치 1200 ms → 0 ms

기존 테스트의 플레이어 종류 직접 변경을 실제 MAIN 진입과 메뉴 동작으로 바꾸었다.
생성한 테스트 미디어만 사용하고 외부 미디어 요청이 0회인지 검사한다. 가짜 주소의
댓글 요청이 실패하면서 테스트 정보 캐시가 지워지는 문제는 해당 테스트에서 댓글을
비활성화하고 설정을 복원하도록 수정했다.

실제 MAIN 재생을 일시정지하고 데이터 절약 모드로 바꾸면, 먼저 1200 ms로 복원한 뒤
추가 소스 재로딩에서 0 ms로 돌아가는 실패를 재현했다. `Player.handleIntentPost()`의
준비된 플레이어 재로딩 직전, 대기 중인 명시적 복원 위치가 없을 때 현재 위치를
보관하도록 최소 수정했다. 새 큐와 명시적 타임스탬프의 복원 위치는 덮어쓰지 않는다.
같은 실제 경로에서 위치·일시정지·품질 UI·화면 회전 검사가 통과했다.

### Android 계측 테스트 결함

- 별도 테스트 APK에서 사용할 수 없는 수신기 등록 의존성을 Android 버전에 맞는
  프레임워크 API로 교체했다. 기존 토큰 검사와 수신 동작은 유지했다.
- 서로 다른 큐 항목이 같은 파일 주소로 중복 처리되던 테스트에 별도 생성 파일을
  사용하고, 테스트 소유 파일만 정리하도록 했다.
- 썸네일 검사에서 전환 Drawable의 최종 이미지를 확인하고, 하드웨어 Bitmap은
  픽셀 검사용 복사본으로 읽는다. 표시된 이미지의 색상 검증은 유지한다.
- 파일 READY와 목록 행의 클릭 가능 시점 차이를 구분하도록 대기 조건을 보강했다.
- 짧은 중복 안내 Toast는 실제 접근성 알림 이벤트의 정확한 문구로 확인한다.

### 저장 목록 드래그와 뒤따르는 용량 안내 실패

Android 23에서 드래그 미리보기 검사가 실패하면 입력 종료 이벤트가 생략되는 결함을
확인했다. 실패 경로에서도 CANCEL을 보내도록 고쳤다. 용량 안내는 기존 코드 그대로
단독 통과했고, 드래그 실패 후 입력 취소를 보장한 연속 검사에서도 통과했다.

드래그 취소 후 어댑터 순서 복원과 화면 복구 애니메이션 종료는 동시에 일어나지
않았다. 두 행의 이동·레이아웃·애니메이션과 조작 가능 상태가 안정된 뒤 그려진
프레임을 기다리도록 테스트를 보강했다. 실제 손잡이 입력, 미리보기 중 디스크 미변경,
취소 후 순서 복원, 놓은 후 저장 및 다시 연 저장소의 순서 검사는 그대로 유지한다.
용량 안내의 정확한 대화상자 문구 검사도 삭제하거나 완화하지 않았다.

### 다음 CI의 Android 35 오디오 검사 간 상태 영향

CI `39922473926`에서는 저장 목록 수정 후 Android 23이 통과했지만 Android 35의
자연 종료와 수동 포커스 복귀 검사에서 각각 실패했다. 로그에서 두 경우 모두 실제
PLAYING 이후 `com.android.systemui`가 STOP을 보내 IDLE/빈 트랙 상태로 바뀐 것을
확인했다. 포커스 LOSS_TRANSIENT와 GAIN은 정상 도착했다. 자연 종료 사례에서는
직전 테스트 종료 후 같은 서비스가 재사용됐고, 포커스 사례에서는 새 서비스에도
늦은 시스템 STOP이 도착했다. 알림 enqueue 제한 경고도 있었지만 이것이 직접 원인인지는
확정하지 않았다.

제품 STOP 처리나 시스템 설정을 바꾸지 않고, 두 테스트 클래스의 정리 단계에서
앱 소유 재생 서비스와 transport 알림이 500ms 동안 연속으로 사라졌는지 확인한 뒤
접근성 이벤트 대기를 추가했다. 서비스가 남으면 제한 시간 내 실패로 보고한다.
자연 종료와 실제 포커스 7종의 로컬 집중 검사는 **8/8** 통과했다. Android 35 전체
재검증 `39925405526`은 통과했다. 같은 실행의 Android 23 드래그 1개는 실패했다.

### Android 23 드래그 입력 대기 보강

`39925405526`의 남은 실패는 첫 손잡이 드래그가 시작된 뒤 미리보기 순서 대기였다.
AndroidX `ItemTouchHelper`는 레이아웃 요청 중 이동 판정을 건너뛰며, 다음 MOVE가
와야 다시 판정할 수 있다. 기존 검사는 12회 MOVE 후 입력 없이 순서만 기다렸다.
같은 끝점에서 실제 MOVE를 같은 10초 제한 내 계속 보내도록 바꾸었다. 두 행 전체가
보이는지 확인하고, 손잡이 중심과 실제 행 윗변 차이로 이동량을 계산한다.
이는 확인 가능한 테스트 약점의 보강이며, CI 실패 당시 레이아웃 상태를 직접 확보한
것은 아니므로 제품 결함 원인으로 단정하지 않는다. 미리보기·디스크 미변경·취소·저장
조건과 실패 시 CANCEL은 유지한다. 독립 검토와 로컬 저장 목록 전체 **9/9**
검사(70.63초)가 통과했으며, 전체 CI 결과는 별도로 확인한다.

로컬 전체 저장 목록 재검증에서는 드래그에 도달하기 전에 기존 회전 테스트가
종료됐다. 생성한 `file:` WAV의 크기를 미설정하여 HTTP HEAD에 전달했고,
`IllegalArgumentException`이 회전 중 취소된 Rx 작업에서 앱 오류 처리로 전달되어
프로세스가 종료된 로그를 확인했다. 테스트 전용 생성 파일의 실제 크기를 대화상자
메타데이터에 채워 해당 HTTP 조회를 없앴다. 제품 HTTP 처리나 회전 검사 조건은
바꾸지 않았다. 이 중단된 실행은 통과 수에 포함하지 않는다.

### 화면 재생성 중 가져오기 결과와 안내 유실

`39929180574`의 Android 23 잔여 실패는 용량 안내였다. 해당 CI의 초기 로그가
순환되어 직접 원인은 확정하지 못했다. 별도로 실제 제품 경로에서 IO 실행을 화면
재생성 이후로 지연시키자 용량 안내가 유실됐고, 이미 표시된 안내도 재생성 뒤 새 창에서
사라졌다. 두 집중 검사가 수정 전 실패했다. 안내 검사는 정확한 문구·실제 가시성과
재생성 전후 서로 다른 창 ID를 요구하여 오래된 접근성 창을 통과로 오인하지 않게 했다.

Activity가 파괴될 때 가져오기 구독을 취소하고 입력 URI도 이미 지운 상태가 원인이었다.
진행 중 작업과 미전달 결과를 ViewModel이 소유하도록 변경해 재생성 중 한 번의 작업을
유지한다. 작업은 Application context만 참조한다. 결과는 화면이 다시 안전하게 재개된
뒤 전달하고, 용량 안내는 고정 태그의 DialogFragment로 복원한다. 요청 재실행 방식은
기존에 복사한 바이트를 용량에 이중 계산할 수 있어 사용하지 않았다.

이 수정은 화면 구성 변경에 대한 작업·안내 보존이다. 가져오기 중 프로세스 종료 후
작업 복구까지 새로 구현하거나 검증했다는 뜻은 아니다. 정상 생성 파일의 실제 복사와
재생성 전후 작업 실행 1회, 바이트 일치도 집중 검사에 추가했다. 로컬 저장 목록 전체 **12/12**(86.10초)가 통과하여 두 RED 검사의 GREEN을
확인했다. 이 수정 직후에는 최종 CI·APK1032 완료 전이었으며, 이후 결과는 아래에 기록한다.

## 실패와 검증 기록

| 단계 | 실제 결과 |
| --- | --- |
| 기존 main CI `39790865246` | JVM 성공, Android 23·35 실패. 최종 배포 보류 |
| 첫 PR24 CI `39916624743` | JVM 성공. Android 23 4개, Android 35 3개 실패 |
| 제품 수정 후 CI `39919229504` | JVM·Android 35 성공, Android 23 2개 실패. 양쪽 기존 조건부 미실행 4개 |
| 위 Android 23 잔여 실패 | 저장 목록 드래그와 용량 안내 2개. 테스트 정리·화면 준비 문제 조사 |
| 목록 수정 후 CI `39922473926` | JVM·Android 23 성공. Android 35 2개 실패, 별도 런타임 검사 13개 통과. 조건부 미실행은 23에서 4개, 35에서 3개 |
| 정리 대기 후 CI `39925405526` | JVM·Android 35 성공. Android 23 보고서: 실행 161개 중 160개 통과·드래그 1개 실패, 별도 미실행 4개 |
| 로컬 위치 재현 | 수정 전 실패 → 동일 실제 MAIN 경로 수정 후 통과 |
| 로컬 JVM·lint | 앱 **560/560**, 공유 **14/14**, lint **0 errors / 346 warnings** |
| 로컬 생성 WebM 영상 검사 | 영상·음성 동시 출력, 오디오 전용, 혼합 큐 **3/3** 통과 |
| 로컬 용량 안내 단독 | **1/1** 통과 |
| 드래그 준비 조건 보강 후 | 드래그·용량 안내 연속 **2/2** 통과 |
| 최종 화면 프레임 대기 포함 저장 목록 전체 | **9/9** 통과 (62.21초) |
| 실제 MOVE·생성 파일 메타데이터 보강 후 저장 목록 전체 | **9/9** 통과 (70.63초), 회전·드래그·용량 안내 포함 |
| 서비스·알림 정리 후 자연 종료/실제 포커스 | **8/8** 통과 (48.25초) |
| 실제 1030→1031 업데이트 | 생성한 READY 메타데이터·WebM 해시 유지, 새 버전·저장 목록 실행 확인 |
| 입력 보강 후 CI `39929180574` | JVM·Android 35 성공(161개 통과, 조건부 미실행 4개, 별도 런타임 13개). Android 23 드래그 통과·용량 안내 1개 실패 |
| 가져오기 화면 재생성 RED | 진행 중 결과 유실과 표시된 안내 유실 **2개 모두 수정 전 실패** |
| 가져오기 생명주기 수정 후 Android 집중 검사 | 저장 목록 **12/12** 통과. 두 RED→GREEN 및 실제 복사 1회·바이트 보존 포함 |
| 추가 제품 수정 JVM·lint | 앱 560 + 공유 14 통과, lint 0 errors / 346 warnings |
| 최종 PR CI `39937505205` | JVM·Android 23·35 성공. 23: 164 통과·4 미실행 + 별도 13 통과. 35: 164 통과·3 미실행 + 별도 13 통과 |

Gradle의 `Finished 165 tests`에는 조건부 미실행도 포함된다. 이전 진행 기록의
165개 전부 통과 표기를 정정하여 실행·실패·미실행을 구분한다. `39929180574`의 Android 35는
161개 통과와 기존 조건부 미실행 4개였다. 각 실행의 수치와 조건부 미실행을 따로 기록한다.

일반 회귀 실행에서 재시작 전용 두 메서드는 인자 조건으로 제외된다. 오프라인 재시작은
별도 seed/restore 단계에서 검사한다. `39922473926`의 Android 35에서는 사용 가능한
Wi-Fi 식별자 조건이 맞지 않아 네트워크 정책 메서드도 기존 조건에 따라 미실행이었다.
이 조건들은 이번 수정에서 추가하거나 완화하지 않았다. 실행 수와 미실행 범위를 구분한다.

로컬 소프트웨어 Android 23의 H.264 시스템 디코더에서 네이티브 SIGILL이 발생했다.
이후 멈춘 계측을 성공으로 집계하지 않고, 해당 클라우드 기기만 재시작했다.
이 환경 문제 때문에 제품 동작이나 실패 검증을 삭제하지 않았다. 실제 기기 검증과
실제 공급자 다운로드/DASH 결합은 **NOTRUN**이다.

독립 검토에서 전환 Drawable 처리, 용량 안내가 Toast가 아닌 대화상자라는 점,
드래그 실패 시 입력 정리 및 복구 후 draw-over 경계가 확인되어 반영했다.

## 소스와 APK1031 보존 기록

- PR: [#24](https://github.com/wnswlfhvkr-code/backtube/pull/24)
- 초기 테스트 수정: `34573de8177e3680332eb293bea6c3b6a51bfb76`
- 위치 수정 및 **APK1031 정확한 빌드 소스**:
  [`eec9d8951fac845a1702f71898d93b5163666989`](https://github.com/wnswlfhvkr-code/backtube/tree/eec9d8951fac845a1702f71898d93b5163666989)
- `a123d0e96`까지의 후속 변경은 테스트·문서만 바꿨다. 이후 가져오기 생명주기
  제품 수정은 새 APK1032의 별도 빌드 소스로 기록한다.

| APK 항목 | 확인 값 |
| --- | --- |
| 파일 | `backtube-final-eec9d89-1031-20261023.apk` |
| versionName / code | `0.29.1-backtube-final-20261023` / `1031` |
| 패키지 | `org.schabi.newpipe.continuous.testofflinelibrary20261011` |
| 크기 | `11548122` bytes |
| SHA256 | `19e8ea31d765c4fe154670da3f4459e71471b047613e0857e9030bd5488d6f44` |
| 서명 인증서 SHA256 | `4fc26294d54b6c9adb276b0b3aab38592077fe28c9f3c64d93da5d9ff6bb5bbc` |

기존 **debug 서명**을 그대로 사용했다. APK1030과 패키지·서명·권한이 같고 버전 코드는
증가했다. 서명 및 16 KiB 정렬 검사, GPL 전문·구성요소 고지 포함을 확인했다.
정밀 패턴 기반 비밀정보 지표 검사에서 발견 사항은 없었으며, 완전한 보안 감사는 아니다.
기존 앱을 삭제하거나 데이터를 지우지 않고 같은 패키지에 업데이트한다.
APK1027 설치 금지는 유지한다. Library 식별자는 사용자 전달 기록에서 관리한다.

## 릴리스 상태와 남은 일

APK1030의 GitHub 릴리스는 초안이며 첨부가 없는 상태로 재확인했다. 기존 실패 작업은
실행기의 GitHub CLI `gh release upload`이며 HTTP 401이었다. 기존 환경 인증의
공식 상태 조회 및 저장소 읽기는 성공했지만, 이것만으로 첨부 업로드 인증이 복구됐다고
볼 수 없다. 실패 응답의 내부 단계나 호스트는 확인되지 않았다. 플러그인의 PR 쓰기와
릴리스 첨부 인증을 구분하며, 새 인증 발급·권한 확대·거부 우회는 하지 않았다.

APK1032의 업데이트 보존 및 PR CI를 확인하여 PR #24를 병합했다.
코드 병합은 `456ec389540dfbf3ae0a589341e2ba53ab1cd745`이며 main CI도 통과했다. APK 첨부 인증이 계속 막히면 공개하지 않고 정확한 차단과 Library 후보를
보고한다. 공개된 최종 릴리스나 다운로드 검증 완료를 아직 주장하지 않는다.

## 10:05 UTC 검증 갱신 — 진행 중

제품 소스 `9a53ca59651d6735aadf1c648771259e276e7055`로 APK1032를 빌드했다.
파일 `backtube-final-9a53ca5-1032-20261023.apk`는 11,548,118 bytes이며 SHA256은
`249496f24f9a1bdf21e5eda92a495c3ce131b87046cbe1f5aa0f2f0c876d9571`이다.
기존 패키지·debug 서명·권한을 유지했고, 실제 클라우드 Android 23에서 1031→1032
업데이트 후 원래 1030에서 생성한 저장본 메타데이터와 영상 바이트가 보존됐다.

CI `39933628076`에서 JVM 및 Android 35 작업은 성공했다. Android 23과 35의 전체
connected 검사 각각 164개 통과·기존 조건부 미실행 4개를 확인했다. Android 35의 별도
런타임 13개도 통과했다. 그러나 Android 23의 별도 첫 타이머 화면 검사에서
`missing accessibility text: 15 minutes`가 발생해 해당 작업은 실패했다. 이후 별도
단계는 실행되지 않았다. 전체 검사를 성공으로 합산하지 않는다.

로그에는 타이머 선택 전에 제품 화면 재생성이나 서비스 연결 해제 증거가 없다.
같은 로컬 검사 1개는 68.85초에 통과해 정확한 CI 원인은 확정하지 않았다. 독립 검토에서
API 23 최초 실행은 터치 후 접근성을 초기화하며, 텍스트 조회 실패마다 어떤 창이든
앞으로 스크롤하는 테스트 결함을 확인했다. 접근성을 먼저 연결하고 실제 터치 후
별도의 타이머 제목 창을 기다리며, ListView만 양방향 탐색하도록 보강했다. 정확한 텍스트,
실제 터치, 타이머 값·취소·재생 상태 검증은 유지한다. 제품 변경은 추가하지 않았다.
독립 검토 및 형식 검사는 통과했고, 변경 후 Android 검증은 진행 중이다.

APK1032 자체의 최적화 빌드에서도 용량 안내가 세로·가로 회전 후 유지되고 닫은 뒤
다시 나타나지 않는 것을 접근성 검사와 실제 화면 두 장으로 확인했다. 초기 검사 중단은
검사기가 만든 임시 파일의 소유권·SELinux 레이블과 미지정 회전 설정 처리 오류였다.
생성 파일에 앱 캐시의 소유권·전체 레이블을 적용하여 수정했으며, SELinux 정책이나
기존 사용자 저장본은 변경하지 않았다. 검사가 끝나면 생성 임시 파일을 제거했다.
Library 보존 및 메타데이터 재조회가 완료됐고, APK 해시는 업로드 후에도 동일하다.
이 APK는 아직 CI 최종 확인 중인 후보이며 정식 릴리스 공개 완료는 아니다.

화면 조작 집중 4개 중 위치 유지·추천 메뉴·재생목록 타이머 3개는 통과했다. 상세 타이머는
새 보조 함수가 Activity 생명주기 조회를 검사 스레드에서 호출해 IllegalStateException이
발생했다. 제품 실패가 아닌 이 테스트 스레드 오류를 기존 main-thread 조회 함수로
수정하고 상세 타이머만 다시 확인한다. 실패 실행은 4개 통과로 기록하지 않는다.

스레드 수정 후 상세 타이머 검사가 **1/1 통과(69.65초)**했다. 앞서 통과한 나머지
3개와 함께 공통 보조 함수 사용 경로를 확인했다. 형식·컴파일 검사를 통과했고, 후속
커밋은 Android 테스트·일지만 변경한다. APK1032 제품 소스·바이트는 그대로 유지한다.

## 최종 후보 APK1032와 원격 코드 보존

| 항목 | 확인 값 |
| --- | --- |
| 정확한 빌드 소스 | `9a53ca59651d6735aadf1c648771259e276e7055` |
| 최종 테스트 HEAD | `95aada611b96463cc8e39bdafa83744be2f57567` (`9a53` 이후 변경은 테스트·일지만) |
| 코드 병합 SHA | `456ec389540dfbf3ae0a589341e2ba53ab1cd745` |
| 파일 | `backtube-final-9a53ca5-1032-20261023.apk` |
| versionName / code | `0.29.1-backtube-final-20261023` / `1032` |
| 패키지 | `org.schabi.newpipe.continuous.testofflinelibrary20261011` |
| 크기 | `11548118` bytes |
| SHA256 | `249496f24f9a1bdf21e5eda92a495c3ce131b87046cbe1f5aa0f2f0c876d9571` |
| 기존 debug 인증서 SHA256 | `4fc26294d54b6c9adb276b0b3aab38592077fe28c9f3c64d93da5d9ff6bb5bbc` |

서명·16 KiB 정렬, 패키지·권한·최소 SDK 호환과 버전 증가를 확인했다. 실제
1030→1031→1032 업데이트 후 생성 저장본의 메타데이터·영상 바이트가 보존됐고,
최적화 APK의 용량 안내 세로·가로 회전 유지 및 닫기 후 재표시 방지를 확인했다.
Library 보존과 메타데이터 재조회 후에도 로컬 APK 해시가 동일하다. 서명키·개인 자료·
로컬 실행 경로는 릴리스에 포함하지 않는다. 기존 앱 삭제나 데이터 삭제 없이 업데이트한다.

[최종 PR CI](https://github.com/wnswlfhvkr-code/backtube/actions/runs/39937505205)는
JVM 및 두 Android 작업 모두 성공했다. Android 23 전체 168개 중 164 통과·4 조건부
미실행, Android 35 전체 167개 중 164 통과·3 조건부 미실행이며, 각각 별도 런타임
13개도 통과했다. 조건부 미실행은 일반 실행에서 인자가 필요한 재시작 두 메서드,
Wi-Fi 식별자가 필요한 네트워크 정책 검사, Android 23의 화면 방향 조건 검사다.
재시작 저장·복원은 별도 런타임 단계에서 실행했고, 네트워크 의존 구독 검사의 기존
제외도 유지했다. 실기기·실제 공급자 다운로드/DASH 결합은 NOTRUN이다.

[main CI](https://github.com/wnswlfhvkr-code/backtube/actions/runs/39939157710)는
JVM·Android 23·35 모두 성공했다. 두 Android 실행 각각 전체 168개 중 164 통과·
4 조건부 미실행, 별도 런타임 13개 통과다. Android 35에서도 이번 실행은 화면 방향
조건 검사가 미실행이었다. 기존 Sonar 작업은 워크플로에 비활성화돼 있어 실행하지 않았다.
남은 테스트 실패는 0개다. 병합 파일 내용은 검증한 PR HEAD와 같으며, APK 빌드 소스와의
차이는 테스트와 일지뿐이다.

## GitHub 보존 및 남은 차단

`v0.29.1-backtube.1032` 태그를 정확한 빌드 소스 `9a53ca59651d6735aadf1c648771259e276e7055`에
연결하고 원격 재조회했다. [1032 릴리스 초안](https://github.com/wnswlfhvkr-code/backtube/releases/tag/untagged-9f690d6a210f3ab06fc0)
(ID `421721985`)에 변경사항·해시·설치/업데이트·검증·미실행·대응 소스와 라이선스를
기록하고 본문 일치를 확인했다. 초안은 권한 있는 계정에서 확인하며 공개 배포 URL이 아니다.
`draft=true`, `published_at=null`, 첨부 0개를 확인했다. 기존 1030 초안·태그와 이전 공개
릴리스는 보존했다. 저장소 공개범위는 public 그대로다.

메타데이터 쓰기와 읽기는 정상이나, 이전 실행기 CLI의 `gh release upload` APK 첨부는
HTTP 401이었다. 응답 내부 단계·호스트나 추가 권한 필요성까지 확정된 것은 아니다.
지원되는 실행기 첨부 인증 경로의 복구가 필요하며, 새 자격 증명·권한 확대·보안 변경이나
다른 인증 경로를 통한 우회를 하지 않았다. 복구 근거가 없어 첨부 업로드를 재시도하지
않았고 공개하지 않았다. 따라서 **GitHub APK 다운로드와 원격 파일 해시 검증은 미완료**다.
Library 후보는 별도로 전달했으며 기존 앱과 패키지·서명이 호환된다.

최종 문서 변경은 별도 브랜치의 문서 PR로 보존한다. 문서만 변경하므로 기존 CI의
`doc/**` 제외 규칙이 적용되며 제품 검사를 추가 실행하지 않는다. 서버 옵시디언 직접
수정이나 사용자 PC/Windows 작업은 하지 않았다.

## 1032 전달 후 첫 선택 오류 — 조사 중

사용자가 앱을 완전히 종료한 뒤 첫 재생을 누르면 선택한 항목 대신 다른 항목이
재생된다고 보고했다. 온라인/저장본 경로는 아직 확인되지 않았다. **1032를 최종
안정판으로 확정하지 않으며 공개 발행을 보류한다.** 위 통과 결과는 이 사용자 보고
이전 검증 범위이며 새 증상이 해결됐다는 근거가 아니다.

독립 코드 조사에서 상세 화면의 초기 플레이어 연결 콜백이 선택 A의 큐를 기존 B 큐로
덮어쓰고, 뒤따르는 메타데이터 콜백도 B를 수용할 수 있는 경로를 발견했다. 이전 B의
지연 화면 갱신이 A 선택 후 실행되는 경로도 있다. 실제 사용자 상황의 원인으로
단정하지 않고 Android RED→GREEN 재현을 진행한다. 온라인 상세 진입, 저장 영상,
저장 오디오를 이전 세션 없음/저장됨/복원됨으로 나누고 실제 프로세스 재시작 후
선택 ID·재생 미디어·화면 제목 일치를 검사한다.

수정 전 제품의 Android 23 재현에서 초기 리스너 연결과 이전 지연 렌더 두 검사 모두
`navigation-A` 기대에 `navigation-B`가 관측됐다. 첫 지연 검사의 200ms 순서에서는
실패하지 않아 이를 성공적인 재현으로 기록하지 않았고, A 시작 뒤 이전 작업이
도착하는 순서를 따로 검사했다. 일반 강제 종료 후 온라인 첫 선택의 세션 저장/
복원 두 시나리오는 수정 전에도 통과했다. 따라서 매번 발생하는 복원 실패가 아니라
확인된 콜백 순서 경합을 수정하며, 사용자 기기에서의 정확한 발생 순서는 미확인이다.

수정은 새 선택 ID와 요청/확인 URL에 맞는 현재 큐 항목·메타데이터를 받을 때까지
이전 콜백을 거부하고, 새 선택 또는 뷰 폐기 이전의 예약 렌더를 무효화한다.
서비스 인계 전 회전에서는 요청 큐와 인덱스를 보존한다. 독립 검토에서 자동 재생 없는
현재 항목의 콜백 수용 조건과 지연 테스트 순서 확인을 보완했다. 현재 JVM 앱 560개+
공유 14개 통과, lint 오류 0·경고 346이며 Android 수정 후 검증은 진행 중이다.

재시작 테스트의 첫 실행은 비어 있을 수 있는 ExoPlayer `requestMetadata`를 읽다가
테스트 코드가 실패했다. 실제 미디어 태그와 로컬 소스 URI·디코더 출력으로 검사하도록
고쳐 수정 전 제품에서 다시 통과했으며, 이를 제품 결함 재현 수치에 넣지 않는다.

추가 인증 진단에서는 기존 실행기 GitHub CLI의 메타데이터 조회 및 저장소 쓰기
권한 상태가 정상임을 확인했다. 이 새 근거로 같은 공식 `gh release upload` 경로의
1032 첨부를 한 번 시도했으나 `uploads.github.com`에서 HTTP 401 `Bad credentials`로
실패했다. 원격 재조회 결과 초안 첨부는 0개이고 공개하지 않았다. 전체 GitHub 연결
단절로 단정하지 않으며, 업로드 호스트의 인증 전달/인증 출처 문제 중 어느 것인지
응답만으로 확정할 수 없다. 새 자격 증명·권한 확대·인증 경로 우회는 하지 않았고
추가 복구 근거 없이 재시도하지 않는다.

### 첫 선택 수정 검증 중간 결과

소스 `c030049d42efa2d086da3a4d9d1c56419903cd64`의 Android 23 집중 검사 4개는
모두 통과했다. 초기 큐/메타데이터 덮어쓰기, 늦은 이전 렌더, 인계 전 화면 재생성 및
다음 곡 전환, 자동 재생 없는 상세 탐색을 포함한다. 수정 전 결과는 실패 3개·통과 1개였다.
강제 종료로 실제 PID를 바꾼 온라인 경로의 이전 세션 없음/저장/복원 3개도 통과했다.

저장 영상의 이전 세션 없음 검사는 선택 A의 큐·미디어·상세 화면이 모두 일치했으나
MAIN 플레이어가 0초에서 일시정지돼 실패했다. 진단 화면에는 A의 실제 영상 첫 프레임과
제목이 표시됐다. 이를 다른 항목 재생 재현이나 해결 완료로 계산하지 않는다.
재생 의도가 꺼지는 경로를 추가 조사 중이며 나머지 저장 영상·오디오 조합은 아직 미실행이다.

1033 후보는 위 소스로 빌드하고 패키지·기존 서명·권한·정렬과 기본 산출물 검사를
완료했으나 전체 런타임 및 업데이트 보존 검증과 전달은 보류한다. 진단용 임시 로그는
후보 APK에 들어 있지 않으며 제품 변경으로 커밋하지 않는다.

GitHub 초안 PR 생성은 자동 승인 검토에서 두 번 거부됐다. 검토가 최초 연결 검증 전용
범위를 적용하고 이후 위임된 구현·검증 승인을 수용하지 않아 직접 범위 확인을 요청했다.
새 근거 없이 재시도하거나 다른 경로로 우회하지 않는다. 따라서 이번 수정의 원격
Android 23/35 CI는 아직 실행하지 못했으며, 기존 1032 CI 통과와 구분한다.

추가 비교에서는 관찰용 바인딩을 서비스 생성 없는 방식으로 바꿔도 동일하게 실패했다.
실시간 진단은 두 재생 요청 모두 재생=true, 포커스 승인=true였음을 확인했고, 실제
최초 정지 호출은 `Player.onCompleted()`였다. 아직 인계 중인 MAIN 선택의 중복 발송을
막자 동일 강제 종료/영상 선택 검사가 통과했다(25.53초). 선택 ID·실제 파일 URI·제목·
영상 및 오디오 디코더 출력 검사는 그대로 유지했다. 임시 진단 로그는 제품에서 제거했다.

독립 검토 후 중복 방어를 현재 Player에 보낸 MAIN 요청에 한정했다. 팝업 발송·새 선택·
연결 해제·메타데이터 확인 시 참조를 초기화하고, 프로세스/화면 복원 상태에 이 참조를
저장하지 않는다. 따라서 소실된 요청이나 팝업→MAIN 전환을 과거 발송 상태만으로 막지
않는다. 최종 조건의 전체 강제 종료 조합 및 관련 회귀 검증은 진행 중이다.

### 1033 후보 검증 — 진행 중

최종 제품 수정 소스는 `7534b877b715919ad28a36f21fc43b9f7797ba63`이다. Android 23에서
강제 종료 후 온라인 진입/저장 영상/저장 오디오 × 이전 세션 없음/저장/복원의 9개 조합과
관련 회귀 16개가 모두 통과했다. 관련 회귀에는 초기 콜백·지연 렌더·회전/다음 곡,
오프라인 영상 및 오디오 출력·듣기 모드·썸네일·종류별 문구·중복 Toast·1200ms 위치 보존이
포함된다. 온라인 진입도 합법적 생성 미디어와 캐시된 합성 정보로 검사했으며 실제 공급자
다운로드나 사용자 폰의 상황을 재현했다고 주장하지 않는다.

중간에 가상 기기 프로세스가 종료되어 결과가 없는 실행 1개가 있었다. 컨테이너에 OOM
기록은 있었으나 해당 종료의 직접 원인으로 확정하지 않았다. 가상 기기를 복구했을 때
검사 앱이 없어 재설치하고 나머지 조합을 완료했다. 그 결과 없는 실행은 통과나 제품
assertion 실패로 합산하지 않는다. 오디오 첫 검사에는 Activity 대신 앱 Context를
넘긴 테스트 결함도 있었다. 실제 제품과 같은 Activity를 넘기도록 고친 뒤 3개 조합이
통과했으며 제품 코드나 재생 ID/미디어/화면 검증 조건은 바꾸지 않았다.

APK는 `backtube-first-playback-7534b87-1033-20261023.apk`, 버전 코드 1033,
버전 이름 `0.29.1-backtube-first-playback-20261023`, 11,548,123바이트다.
SHA-256은 `de32b8707e0682930de9e70015be135af999afaf645fef0605549812406afbda`이다.
패키지 `org.schabi.newpipe.continuous.testofflinelibrary20261011`와 기존 debug 서명,
권한 목록, 최소 SDK를 유지했고 16KiB 정렬·라이선스/NOTICE 포함을 확인했다.
제한적 비밀 유입 탐지에서 고정밀 표식/credential 파일명 표식은 없었다. 완전한 보안
감사를 뜻하지 않는다. 설치 후 APK를 다시 읽어 위 해시와 같은지도 확인했다.

실제 1032→1033 덮어 설치에서 생성한 READY 저장 항목의 메타데이터와 WebM 파일이
바이트 단위로 보존됐으며 저장 목록이 열렸다. 이는 가상 기기 소실 후 1032에 다시
구성한 생성 자료로 수행한 검사다. 이전 기기의 1030→1032 자료가 재부팅 뒤 그대로
살아 있었다고 주장하지 않는다. 후보 APK 자체에서도 이전 저장 세션이 있는 상태로
새 저장 영상을 선택한 뒤 해당 제목과 실제 생성 영상 프레임을 시각 확인했다.
짧은 클립의 종료 뒤 화면만으로 영상 출력 판정을 하지 않고, 같은 합법적 생성 영상을
길게 구성해 프레임을 확인했다. 후속 UI 계층 덤프 파일이 생성되지 않은 도구 한계는
별도로 남기고 스크린샷을 독립 확인했다.

Library에 위 해시의 APK 1033을 저장하고 파일명·크기를 재조회했다. Library ID는
`libfile_ce1f7b6533d08191b51ffecec756df7c`이다. 후보 전달은 완료했으며 최종 안정판이나
공개 배포 완료를 뜻하지 않는다. Android 35의 동일 집중 검증과 원격 PR/CI는 아직
완료되지 않았다. Android 35는 최초 600초 부팅 감시 제한을 넘겼고, 이후 같은 가상
기기에서 부팅 완료를 확인했다. 설치한 앱과 테스트 APK는 성공했으며 실제 검사
결과는 아래 후속 기록과 구분한다.
GitHub 초안 PR은 두 번 거부됐고 원격 모든 상태 조회에서도 해당 브랜치 PR은 0개였다.
동일한 승인 증거로 세 번째 생성 요청을 하지 않았다. 공개 발행은 계속 보류한다.


### 추가 사용자 단서 — 자동 다음 곡 경로 검증 중

사용자는 첫 선택 대신 이전 곡보다 해당 항목의 관련/자동 다음 곡이 시작되는 느낌이라고
설명했다. 이전 세션 복원 문제로 단정하지 않는다. 제품의 완료 처리는 온라인 큐에
다음 항목이 있으면 그 항목으로 이동하므로, 앞서 확인한 중복 MAIN 요청과 조기 완료가
온라인에서도 발생하면 이 증상과 연결될 가능성이 있다. 아직 사용자 환경의 원인으로
확정하지 않았으며 자동 다음 곡 ON의 별도 회귀를 추가한다.

새 검사는 강제 종료 후 A(60초 생성 WAV), 관련 B(30초 별도 생성 WAV)를 고정한다.
A의 실제 ID·URI·제목을 먼저 확인하고 3초 동안 A를 유지한 뒤 종료 직전으로 이동한다.
실제 AUTO_TRANSITION 및 A 끝 위치, B의 ID·URI·제목·길이를 확인하여 조기 건너뛰기와
정상 자동 전환을 구분한다. 세션 없음/저장/복원 각각 seed와 verify에 옵션을 넘기는
CI 게이트를 추가했다. 독립 검토에서 이 게이트 누락을 지적해 보완했으며 제품 코드는
추가 변경하지 않았다. APK 1033은 이 추가 검사가 아직 끝나지 않은 후보 상태다.

Android 35의 첫 실행은 테스트 결과 없이 Process crashed로 끝났다. 앱 종료 기록은
시작 단계 ANR이며 제품 assertion 실패가 아니다. 초기 시스템 재시작도 관측돼 동일
캐시 이미지·RAM·보안 설정·제한 시간을 유지하고 가상 CPU만 2개에서 1개로 바꾼
소프트웨어 에뮬레이션 비교를 진행한다. 실패한 실행을 통과로 계산하지 않는다.


실기기 캡처 4장을 공식 Library 전송으로 소비 실행기 로컬에 가져와 실제 픽셀을 확인했다.
검색 결과 A 강조 → A 제목/검정 영상 영역/빨간 원형 화살표 → 관련 B 제목/이미지 전환을
확인했다. 자동 대기열 추가는 켜져 있었다. 중앙 흰 재생 버튼과 아래 녹화 재생 시간 막대는
외부 녹화 플레이어 표시로 구분했다. 캡처만으로 오류 종류나 실행 APK 버전은 확정하지
않으며 원본·개인 화면은 저장소나 릴리스에 업로드하지 않는다.

읽기 전용 독립 검토에서 별도 원인 후보를 발견했다. AudioOnlyUnavailableException
안내 분기가 공통 FailedMediaSource 정지를 건너뛰어 2초 무음 소스 완료 후 다음 큐 항목으로
이동할 수 있다. 이는 데이터 절약/오디오 전용 조건의 코드 경로이며 사용자 원인 확정은
아니다. 기존 소스 타입 검사는 이 시간 경과를 확인하지 않았다. 실제 manager/ExoPlayer로
초기 실패 A와 정상 B를 준비하고 3.5초 뒤 A 유지·정지 및 수동 재시도를 검사하는 별도
InitialSourceFailureTest를 추가했다. 일반 추출 오류와 오디오 전용 오류를 나눠 검증한다.
제품 수정 전 RED 검증은 아직 진행 중이다.

Android 35는 1 가상 CPU에서도 앱 시작 전 ANR로 테스트가 실행되지 않았다. 공식
패키지 사전 컴파일로 시작 비용을 줄일 수 있는지 비교 중이다. Android 23 재기동은 남은
잠금 파일 때문에 거부됐으나 해당 AVD 프로세스·OS 잠금 소유자·열린 파일 참조가 모두
없음을 확인했다. 잠금 파일을 별도 보존 위치로 옮긴 뒤 동일 AVD를 다시 시작했다.
활성 잠금이나 권한 거부를 우회하지 않았다.


### 초기 오디오 소스 실패 RED 및 수정 — 검증 중

Android 23에서 일반 초기 추출 실패는 A 유지/수동 재시도 검사를 통과했다. 반면 오디오
전용 실패는 미리 준비된 B 큐와 실패 후 자동 생성한 관련 B 두 경우 모두 기대
`initial-failed-A` 대신 `initial-related-B`가 관측됐다. 오류 타입도 각각 확인했다.
2초 무음 실패 소스가 완료되는 실제 엔진 경로의 RED이며, 사용자 캡처의 동일 원인
확정과는 구분한다. 정상 자동 다음 곡 ON의 세션 없음/저장/복원 3조합은 수정 전에도
A 유지와 실제 종료 후 B 전환을 모두 통과했다.

AudioOnlyUnavailableException도 공통 재생 실패 정지를 거치도록 최소 수정했다.
선택 A를 유지하고 재시도하도록 하며 정상 완료/다음 곡 코드는 변경하지 않았다.
한국어/영어 안내의 ‘건너뜀’을 ‘선택 항목 정지, 데이터 절약 해제 후 재시도’로 맞췄다.
독립 검토에서 차단 결함은 발견하지 않았다. 엔진 직접 재시도 검사는 실제 설정 화면과
화면 재생 버튼을 누르는 경로까지 검증한 것은 아니다.

수정 소스 JVM 앱 560개·공유 14개 통과, lint 오류 0·경고 346, 스타일 검사 통과다.
초기 실행 명령에서 잘못 지정한 Gradle 작업 이름은 실제 등록 이름으로 고쳤으며 이를
테스트 실패나 통과에 합산하지 않는다. Android 23 cleanup에서 시스템 활성 알림 응답이
null인 별도 테스트 문제도 관측했다. null을 알림 없음으로 간주하지 않고 기존 10초
기한 안에서 다시 확인하도록 보완했다. 실제 제품 실패 assertion은 그대로 유지한다.
수정 APK 런타임 GREEN과 1034 후보 생성은 아직 진행 중이다.


### 1034 후보 및 최종 집중 검사 — 진행 중

빌드 소스 `51b99b4d055d166aaec4495d61095efbec90dcd0`으로 1034 후보를 생성했다.
파일은 `backtube-first-source-51b99b4-1034-20261023.apk`, 11,548,227 bytes이며 SHA256은
`d948473a298d2deae5943ca6566edd8c21cb0b15a658a83c0e8aeec9dc681bdb`이다.
패키지·기존 debug 서명·권한·최소 SDK 호환과 서명/정렬, 라이선스/NOTICE/아이콘 보존을
확인했다. 아직 업데이트·후보 화면·Library 전달 검증 완료를 뜻하지 않는다.

수정 후 초기 소스 실패 3개는 통과했다. 일반 첫 선택 9조합과 자동 다음 곡 3조합도
통과했다. 온라인 MAIN 누락 파일 검사는 처음에 오류 정지 상태에 도달하지 않았다.
독립 검토에서 정상 A와 실패 A가 동일 스트림 캐시 키를 사용함을 찾았다. 실패 fixture에
실행별 고유 ID를 부여해 기존 미디어 캐시가 오류를 숨기지 않게 한 뒤, A 오류 유지 →
소유 생성 파일 복구 → 수동 재시도 A → 정상 종료 B 전환까지 통과했다. 제품 오류
정지 조건이나 기대 assertion을 완화하지 않았다.

관련 회귀 16개 중 15개가 통과했고 화면 재생성 후 A를 기다리는 1개가 10초 시간
초과였다. 화면 교체/선택/엔진 상태 진단을 추가해 원인을 확인 중이며 통과로 세지 않는다.
제품 소스 변경 없이 테스트 진단만 추가했다. Android 35는 공식 사전 컴파일 뒤에도
시작 ANR로 시험 전 종료하여 NOTRUN이다. Android 23 성공으로 대체 표기하지 않는다.


화면 재생성 검사는 단독 실행에서도 실패했다. 새 세로 Activity가 생성됐지만
playing=false, selectedA=false, playWhenReady=true, Exo state=IDLE이었다.
독립 검토에서 정보 로딩 완료가 먼저 엔진을 정지시킨 뒤 중복 MAIN 요청 guard에서
반환할 수 있음을 찾았다. 같은 Player에 이미 전달한 pending 선택은 앞선 로딩 처리에서도
정지시키지 않도록 5줄 guard를 추가했다. 새 선택은 상태/참조를 초기화하며 메타데이터
승인 뒤 정상 reload에는 적용되지 않는다. 수정 후 런타임 비교는 아직 진행 중이다.
새 코드 JVM 560+14, lint 0 errors/346 warnings, 스타일 검사를 통과했다.
1034는 이 추가 변경을 포함하지 않아 전달을 보류하고 최종 후보는 별도 증가 버전으로
만들 예정이다. 기존 파일을 덮어쓰거나 최종 안정판으로 표시하지 않는다.


## 2026-10-24 승인된 재개 — 진행 중

사용자가 백튜브 작업 재개를 요청했다. 이전 비용 중단은 해제됐으며 이전 12시간 기한을
복원하지 않는다. Windows 및 다른 프로젝트 작업은 제외한다. 재개 시 미커밋 4개 파일을
별도 패치로 보존하고 현재 체크아웃의 HEAD 51b99b4를 확인했다.

참고 자료 `backtube-chat-review-v2.zip`을 공식 Library 경로로 현재 실행기에 받아 읽었다.
공개 main 36c37ab5 대상의 미컴파일 패치이며 그대로 적용하지 않았다. 오디오 전용 오류에서
실패 정리를 먼저 수행하는 핵심 수정은 현재 소스에 이미 있다. 추가 완료/추천 guard는
실제 경로 근거 없이 도입하지 않았다.

재개한 클라우드 Android 23에서 마지막 화면 재생성 검사는 통과했다. 이는 이전 정지 상태
RED 2회에 대한 5줄 guard의 첫 런타임 GREEN이다. 제품 전체 검증 완료를 뜻하지 않는다.
독립 리뷰가 초기 실패 후 수동 Next 및 동일 큐 새 선택의 누락 가능성을 지적했다.
실제 서비스·ExoPlayer 경로의 테스트를 추가했다. 첫 수동 Next 검사는 실패했으며 큐·엔진·
상세 화면을 분리해 원인을 확인 중이다. 새 선택 검사의 첫 fixture는 익명 테스트 항목을
직렬화하지 못해 정상 진입하지 않았으므로 제품 RED로 집계하지 않는다. 일반 직렬화
가능한 큐로 바로잡아 다시 실행 중이다. 기대 조건을 완화하지 않았다.


직렬화 가능한 새 큐를 사용하는 재선택 시험에서도 정상 A StreamInfo/미디어 재생에
도달하지 못했다. 원인은 실패 플래그를 초기화한 뒤 동일 큐 빠른 경로가 기존 실패
무음 소스에 prepare만 호출하는 것이다. 플래그 초기화 전에 동일 실패 큐의 새 선택을
식별하고 이 경우 incoming queue로 initPlayback을 거치도록 최소 수정했다. 새 선택
시작 위치 처리는 먼저 적용하며 일반 팝업/백그라운드 전환은 새 선택이 아니므로
해당 분기에 들어가지 않는다. 독립 검토 및 컴파일/스타일은 통과했고 런타임 GREEN은
아직 진행 중이다.

수동 Next 초기 시험은 Next와 Play를 즉시 연속 호출해 최종 큐 A/IDLE을 관측했다.
단순 제목 고정으로 단정하지 않는다. 기존 제품 규칙인 오류 후 일시정지 의도 유지에
맞춰 Next → B 준비/상세 B → 사용자의 Play 단계를 나누고, 큐 크기·즉시 선택·엔진·
상세 상태를 각각 검증하도록 보강했다. 원인을 확인하기 전에 UI 제품 코드는 바꾸지 않았다.


동일 큐 재선택의 실제 Android 검사는 수정 후 GREEN이다. 수동 Next를 별도 동작으로
검사하면 selectedB=true, queueB=true, Exo READY, playWhenReady=false지만 detailB=false로
실패했다. 초기 실패 A에는 정상 StreamInfo 콜백이 없어 선택 보호 상태가 끝나지 않는
원인이 확인됐다. 실패 상태를 PAUSED 알림보다 먼저 확정하고, 상세 화면이 현재 요청한
A의 실패를 받으면 pending 보호를 해제하도록 수정했다. A 자체는 계속 정지/재시도 상태로
유지하며 이후 명시적 Next를 허용한다. 즉시 Next+Play 경로도 별도 회귀로 유지한다.
이 변경의 런타임은 아직 진행 중이다. Android 35는 새 실행기에서 244초에 부팅 완료했으며
이전 실패 결과를 새 코드의 결과로 재사용하지 않는다.

### 2026-10-24 — resumed error/navigation runtime evidence (in progress)

- Current-source API 23 generated-media checks: fresh explicit reselection of failed A PASS; ordinary manual Next after failed A PASS; failed A remains selected and paused across Activity recreation PASS. The initial pending-playlist recreation regression also passed earlier in this resumed run.
- Rapid Next then Play remained RED: queue B (index 1), engine A (index 0), then a REMOVE discontinuity reported index 0 and reset the queue to A. Trace: `beforePlay=1:0:126`, `transition=0:0:4:0`. This establishes a source-replacement race independently of the failed-detail acknowledgement bug.
- Minimal correction under validation: keep STATE_BLOCKED through setMediaSource, then change to BUFFERING and prepare, preserving the existing discontinuity guard during synchronous replacement. Read-only independent review found no additional code blocker; GREEN is still required.
- API 35 installed both packages but instrumentation executed zero tests and exited with startup ANR (exit reason 6). VM stopped; API 35 validation is NOT RUN, not PASS. No timeout/security setting changes, Windows work, or user-device use.

- Fresh full JVM execution initially reported 559/560 app tests passing, with `ActiveSessionResumeTest.freshSelectionRestartsRetryBudgetAndClearsPriorFailureFlags` failing (expected 0ms, actual 45000ms). Its mocked UI allocation boundary threw during destroyPlayer, and dispatch swallowed that boundary before the required new engine was allocated. The subsequent synthetic error was incorrectly delivered to the unchanged old mock engine. The test now executes real handleIntent/initPlayback through mocked Android allocation, verifies one new engine and the incoming queue, then retains the original retry-budget/zero-position assertions. Focused class plus Checkstyle PASS; full final rerun remains pending.
- Guard app update installed successfully in 280.41 seconds. During the install, adb remained online and dex2oat was active; no duplicate install was started.

- Rapid Next→Play now GREEN on API 23 after the source-replacement ordering correction (same failing device test, 28.48s). Independent review found no remaining code blocker and confirmed the corrected JVM allocation fixture preserves its retry-budget assertions.
- Broader navigation run: first back-navigation assertion read `snackbar_text` before the asynchronously attached Snackbar existed (test NPE). The instrumentation then crashed; sanitized system diagnostics identified SIGILL in `libstagefright_soft_avcdec.so`, matching the cloud API 23 H.264 decoder issue. This run is FAIL, not a pass and not a complete 18-test run.
- Preparing test-only synchronization for Snackbar appearance and a selectable 30-second black/silent VP8/Vorbis fixture (160×90, same duration as the MP4). Default H.264 remains available; WebM results will not be claimed as H.264 coverage. No protected/downloaded media is included.

- Final product code: fresh JVM app 560/560 and shared 14/14 PASS; lint 0 errors / 346 warnings; Checkstyle PASS. Test-only portable media/await changes subsequently compiled and passed Checkstyle/lint.
- Portable API 23 navigation suite: 17 PASS, 0 FAIL, 1 NOT RUN (existing system rotation-lock prerequisite absent). Includes explicit A versus old callbacks, late metadata, pending recreation, failed-A recreation, manual Next, immediate Next→Play, fullscreen/back and popup orientation/pause paths. Full suite took 360.07s. Previous failed MP4 run is retained separately.

- Core portable run completed 55 PASS / 1 FAIL / 1 NOT RUN. The only remaining failure was `pauseAfterMetadataRequestStartedCannotResumeFromLateResult`: static expectation 42000ms versus observed 42402ms. This test starts real playback before injecting a stop/error, so setup's seek position is not the position that the error necessarily stopped. Independent source review confirmed this mismatch in the test contract; the original result alone does not establish when those 402ms elapsed.
- Test-only correction records the exact stopped position immediately after error injection, bounds it to [42000,45000), then requires exact equality before pause and after the late callback plus 500ms, preserving IDLE/not-playing/selected-A and explicit-resume assertions. No tolerance was added to the no-drift check. Focused rerun pending; product source unchanged.

- Follow-up measurement: the extra pre-pause diagnostic assertion failed at error-stop capture 42000ms versus 42019ms **before** the user pause and before delivery of the late metadata result. This rules out the late callback as the cause of that difference; asynchronous engine stop settlement remains an inference, not a proven internal cause.
- Final test contract captures position immediately after the user's pause, still requires [42000,45000), exact position equality after late success + 500ms, IDLE and play intent false, and selected A with resume position at least the captured pause position. The diagnostic pre-pause equality was not part of the original contract and was removed. Independent review accepted this correction; runtime result remains pending.

- Corrected user-pause invariant GREEN (9.36s): exact position retained across late metadata result and 500ms; explicit resume retained A and its position bounds. No product change was made for this test issue. Final unique focused Android results: 56 PASS / 0 remaining FAIL / 1 NOT RUN, across navigation, source errors/retries, listening, saved media/thumbnails/duplicates, active-session restoration and recovery. Earlier RED runs remain in the evidence ledger.
- Candidate plan: versionCode 1035, versionName `0.29.1-backtube-first-selection-20261024`; continuous variant on a branch normalizing to `testofflinelibrary20261011`, existing package/signing input and unchanged permissions. Build and force-stop matrices are pending at this source checkpoint; not a published release.

### 2026-10-24 — candidate 1035 validation and delivery (in progress)

- Exact product/build source: `486fe10f24c2ed1a1e38d3b8769fd924cc25cac5`; no remote branch, PR, release or tag mutation in this resumed work.
- Final current-code JVM: app 560 PASS, shared 14 PASS. Checkstyle PASS. Lint: 0 errors, 346 warnings.
- API 23 focused runtime: 56 unique PASS, 1 NOT RUN (system rotation-lock prerequisite absent). Earlier failed runs and test-fixture corrections are retained above; no remaining focused failure.
- Force-stop/process restart: 13/13 scenarios PASS (26 seed/verify invocations): online-entry/saved-video/saved-audio × no/saved/restored previous session; automatic next ON with three previous-session states; initial source failure retains A, manual retry A, then actual completion B. Online-entry fixtures use cached synthetic metadata and local generated media, not real provider downloads.
- API 35 NOT RUN: instrumentation startup ANR with zero executed tests. API 23 H.264 runtime blocked by system decoder SIGILL; explicit VP8/Vorbis fixtures were used. Physical-phone/provider-download/entire Android suite/new remote CI are NOT RUN.
- APK built successfully with original signing-input identity retained; no key generated or copied. `backtube-first-selection-486fe10-1035-20261024.apk`, 11,548,214 bytes, SHA-256 `36a8c8984eb4809f93b818cb86d7693dc87e2a532be25d989fbd8bab2c6e0151`.
- VersionCode `1035`, versionName `0.29.1-backtube-first-selection-20261024`, package `org.schabi.newpipe.continuous.testofflinelibrary20261011`. Existing debug certificate SHA-256 `4fc26294d54b6c9adb276b0b3aab38592077fe28c9f3c64d93da5d9ff6bb5bbc`. Signature/16KiB zip alignment/package/permission parity/minSDK checks PASS; code increased from 1033.
- GPL and component notices packaged; 12 source icon assets unchanged. Targeted APK secret indicators: 0. Source archive retains LICENSE and 4634 files, no forbidden file indicators; one PEM parser regex marker was reviewed as static code, zero unresolved indicators.
- Exact source archive: `backtube-source-486fe10-1035.tar.gz`, 12,333,635 bytes, SHA-256 `8b4301287ade52bfcade0e808c59aa9ca21e04a91780f16f46b305607d8bc10e`.
- The known 1033 baseline was installed and its owned generated READY fixture seeded. Candidate update, final rendered UI/read-back and Library delivery are still pending at this entry. Not a final public release.

### 2026-10-24 — verified 1035 artifact; Library delivery blocked

- Actual 1033→1035 `install -r` upgrade PASS (124.37s): generated READY saved-entry metadata unchanged, saved media SHA-256 unchanged, installed version 1035, shelf launch successful. No new runtime permission grants.
- Read-back of installed base APK exactly matches deliverable SHA-256 `36a8c8984eb4809f93b818cb86d7693dc87e2a532be25d989fbd8bab2c6e0151`.
- Candidate video rendered on the offline cloud device (Wi-Fi/mobile data disabled). Direct pixel review confirmed moving generated frames, playback time 00:05→00:09 of 00:30, and the selected title `Generated candidate long video` with `Play saved video`. Automated post-playback UI XML collection failed with ParseError; this was retained as a collection failure and the final candidate visual check was performed directly on screenshots, not falsely counted as an automated pass. No screenshots were uploaded.
- Library upload attempted once using the current official prepared batch helper for the exact APK, source archive and verification JSON. It exited 1 without a parseable result or Library IDs. Exact-name lookup and the latest 100 Library metadata entries showed no matching new items; no second write was attempted.
- Read-only diagnosis through the same official hosted-app helper path, using the already authorized review ZIP, returned: `hosted apps tools/list request failed with HTTP status 401`. Direct connected Library metadata reads succeeded. This identifies an execution-side Library authentication path problem; it does not establish that the whole Library connection is disconnected or that broader scopes are needed. No credentials, tokens or headers were read/exposed; no new credentials or permission changes were attempted.
- Required recovery: restore the existing selected cloud execution's official Library hosted-app authentication, then establish the prior write outcome before any retry. Do not switch to a different upload path to bypass the 401. Library prepared-uploads guidance: “Never switch a started helper write to a direct action.”
- Library IDs: NOT AVAILABLE. Local installable APK, exact source archive, verification JSON, R8 mapping, sanitized receipts and handoff remain preserved. Product build source remains `486fe10f24c2ed1a1e38d3b8769fd924cc25cac5`; this journal update is documentation only.
- GitHub publication was not attempted in this resumed work. The earlier draft PR auto-review denial (authorization evidence/scope not accepted) and official GitHub release asset-upload HTTP401 remain unresolved; no bypass or new authentication attempt occurred. No Windows or user-PC work was used.

### Follow-up — APK 1035 attached successfully

- User explicitly requested resolving the remaining problem and attaching the APK. Exact-name Library lookup found no existing APK item; the verified 11,548,214-byte APK was saved as the single requested deliverable using the official connected Library single-file create route. No new credential, permission change or APK rebuild was involved.
- Library ID: `libfile_91ded02391a48191b9b8b09623062e44`; filename: `backtube-first-selection-486fe10-1035-20261024.apk`.
- Local Library identity metadata applied successfully. Official Library materialization/read-back succeeded; downloaded SHA-256 exactly matched `36a8c8984eb4809f93b818cb86d7693dc87e2a532be25d989fbd8bab2c6e0151`.
- APK attachment is now complete. The earlier execution-side batch-helper HTTP401 remains historical; this does not claim that its authentication was repaired. Source archive/verification records remain preserved locally. GitHub publication was not attempted.
- Build source remains `486fe10f24c2ed1a1e38d3b8769fd924cc25cac5`; previous validation and physical-device/provider limitations remain unchanged.

### Follow-up — web Pro v2 reviewed; async-holder candidate preparation only

- review-v2.zip Library libfile_21cff49cecf081918af2c69b90501de3 materialized and verified in this consumer; file_id file_00000000787c823098bba78845fa1b03, version_id unavailable. SHA matches user-provided 0960cfa1c636cf460d73f254b7d7d20b0d48af7461d263b257f2fe3204a6308e.
- Independently confirmed both holder blockingGet paths and IO-scheduled history source; UI wait is a code-path finding, not measured jank or performance gain. No current direct InfoItemHolder.updateState caller found; unrelated LocalItemHolder calls distinguished.
- All 4637 manifest file hashes match; 4638-versus-4637 inventory difference is the values-iw directory symlink. Java/Kotlin counts and review-depth coverage are not whole-program semantic review.
- Isolated perf/stream-state-bind-async-review-20261024 local worktree prepared. Existing async ownership patterns and RED→GREEN criteria recorded in doc/review/stream-state-async/PREPARATION.md.
- No actual candidate patch received/applied, no product/test change or new runtime/performance validation. No deletion, Sequence change, Preview removal, Windows work, main merge or release. Waiting for the pending web Pro async patch and reuse-row tests.

### Follow-up — web Pro v4 async history implemented and focused validation passed

- Exact input Library libfile_1dc1e3d3a1b48191a25c9cc0f5165b3d, SHA fb9163d0d7be87cb0ba72db1e9905dbb04d348f89df9a357caf5318bd151a207, 267969 bytes/19 ZIP members, locally materialized and CRC/read verified.
- Two product Java files only: InfoListAdapter and StreamMiniInfoItemHolder. No unused-code/Preview deletion, Sequence conversion, player engine/schema/build identity change.
- Original1035 bind-on-main with unresolved history Single reproduced RED. Corrected candidate initially passed10/11 Android tests; real whole-RecyclerView window detach kept an observer. Actual View attach/detach listener resolved it. A final LIVE display-rule reload regression was measured and corrected independently of visual animation mode.
- Final Android holder11/11 PASS; final-source JVM560+shared14 PASS, Checkstyle PASS, lint0errors/346warnings. Abstract web model8 not counted. Independent source reviews accepted final changes.
- Code is pinned for related main-player/offline regression and compatible APK1036 build; those stages are not complete at this checkpoint. Physical phone/API35/benchmark/entire Android suite NOT RUN. No Windows/main merge/release.

### Follow-up — v4 final validation and APK1036 delivery

- Product/build source: `e3f506218aca6ec4c9c15c9e449b8ac1bf3c19fb`. Documentation after that commit does not change the built product. Two product Java files changed; the player/queue engine, offline implementation, schema, permissions and build identity were preserved.
- Fresh final-code JVM app560 + shared14 PASS, Checkstyle PASS, lint0 errors/346 warnings. Actual API23 holder11/11 and related playback/offline25/25 PASS. Force-stop/process restart4/4 scenarios PASS (8 seed/verify invocations), including selected-A source error/retry/normal completion and offline video/audio. Generated local media, network OFF; not real provider coverage.
- Earlier RED results remain above and in V4-VALIDATION.json. Whole-window detach cancellation and legacy LIVE display policy failures were fixed before the final11-test run; no failed assertion was deleted to obtain a pass.
- APK `backtube-history-async-e3f5062-1036-20261024.apk`, versionCode1036, versionName `0.29.1-backtube-history-async-20261024`, 11548213 bytes, SHA-256 `d163993b1231c9af18d75f5ef8b959e37614746f19cc683660ac8d37bd6a51d5`. Package `org.schabi.newpipe.continuous.testofflinelibrary20261011`; existing debug certificate SHA-256 `4fc26294d54b6c9adb276b0b3aab38592077fe28c9f3c64d93da5d9ff6bb5bbc`. Signature and16KiB zip alignment PASS; no new permission, key generation or credential inspection/copy.
- Actual1035→1036 update PASS with generated saved-entry metadata and media hash unchanged. Installed base APK read-back matches the deliverable. Final candidate local-video UI was reviewed separately from debug instrumentation; see V4-VALIDATION.json for the collection result.
- GPL/component notices retained; targeted accidental-secret checks found zero indicators, not a full security audit.
- Library `libfile_854e42c8a91481919e0e0e7af5e745fe`; official materialization read-back SHA-256 exactly matches the APK. Compatible update installation does not require deleting the existing app.
- Physical phone, API35 runtime, full Android suite, real provider download, frame-time/allocation benchmark and new remote Android CI NOT RUN. No whole-project semantic-review claim; no unused-code/Preview deletion or asSequence conversion.
- Branch preservation target: `perf/stream-state-bind-async-review-20261024`. Remote write/read-back is recorded separately after the final documentation commit. No main merge, PR creation, tag/release publication or Windows/user-PC work.
