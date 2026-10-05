# 플레이 상세 화면의 취침 타이머 — 2026-10-05

## 요청과 결과

영상 아래 `이곳에 추가 / 백그라운드 / 팝업 / 다운로드` 액션 행에 취침 타이머를 추가했다. `백그라운드`는 현재 위치를 보존하고 오디오 전용 foreground service로 전환하는 기존 동작이다. 외부 오디오 플레이어 설정이 켜진 경우 외부 앱 선택 경로를 사용한다. 해당 버튼과 기존 long-press 대기열 추가 동작을 보존했다.

`백그라운드` 오른쪽의 시계 버튼은 `타이머: 끔`, 남은 분 또는 `끝나면 정지`를 표시한다. 눌러 15/30/60/90/120분, 직접 설정 1–1440분, 현재 항목 끝, +15분, 타이머 취소, fade 설정을 사용할 수 있다. 일반 대화상자의 `취소`는 타이머를 끄지 않는다. 이 설정은 현재 재생 세션 전체에 적용되며, 상세 화면에서 보고 있는 영상이 다른 경우에도 기존 Player의 타이머를 사용한다.

새 타이머 엔진은 만들지 않았다. `SleepTimerDialog`는 기존 큐 화면 대화상자를 공유하며, `Player`/`SleepTimer`의 마감시각·페이드·만료·wake mode·서비스 복구 로직은 변경하지 않았다. 화면에서 재생 또는 일시정지를 직접 호출하지 않는다. 상세 화면 표시 갱신은 RESUMED 동안만 수행하고, view 파괴·플레이어 disconnect 때 대화상자와 callback을 정리한다. 큐 화면도 같은 helper를 사용하며, 서비스 disconnect 시 기존 `onServiceStopped()` 경로로 화면을 종료해 연결이 끊긴 Player를 다시 조작하지 않도록 한다.

일반/큰 가로 화면 레이아웃 모두 기존 네 버튼을 유지한다. 5개 액션은 가로로 스크롤하며 각 기본 폭 80dp를 유지한다. 타이머 텍스트는 줄바꿈할 수 있고 최소 높이는 55dp다. 실제 320dp/큰 글꼴/RTL 렌더링은 이 실행 환경에서 확인하지 못했다.

첨부 이미지의 Library 공식 materialize는 두 번 모두 download failed였다. 이미지 픽셀을 직접 확인했다고 주장하지 않는다. 부모 담당이 확인한 위 액션 행 설명과 기존 XML을 기준으로 구현했다. 첨부 이미지, 사용자 데이터, 토큰 또는 서명 다운로드 URL을 저장하지 않았다.

## 검증

| 결과 | 범위와 근거 |
| --- | --- |
| PASS | 독립 JVM harness: 기존 `SleepTimerTest` 7개 + 새 XML 계약 `SleepTimerLayoutTest` 2개, 총 9개. 실제 RxJava/JUnit 및 수정하지 않은 SleepTimer 소스를 사용. [green 로그](evidence/detail-sleep-timer-2026-10-05/green-layout.log), 같은 폴더 JUnit XML |
| RED 확인 | 구현 전 기존 타이머 7개 PASS, 신규 XML 2개가 타이머 액션/스크롤 부재로 FAIL. [로그](evidence/detail-sleep-timer-2026-10-05/red-layout.log) |
| FAIL / 환경 차단 | 기본 `:app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest`: Build Tools 36.0.0 누락으로 task dependency 계산 중 종료. 앱 컴파일·테스트 실행 실패로 오인하지 말 것. [로그](evidence/detail-sleep-timer-2026-10-05/baseline-build.log) |
| FAIL / 진단 한정 | 저장소 변경 없이 임시 Gradle init script로 app에 설치된 Build Tools 37.0.0을 지정해 같은 작업 재시도. shared 모듈의 36.0.0 요구로 여전히 차단. 대체 SDK로 앱을 빌드했다는 증거가 아님. [로그](evidence/detail-sleep-timer-2026-10-05/installed-buildtools37.log) |
| 미실행 | 신규 `detailTimerSharesStateAndDialogCancellationPreservesPlayback` 및 기존 Android instrumentation. 로컬 emulator/system image/KVM 없음. 새 테스트는 생성 WAV와 합성 StreamInfo를 이용하며 실기기·실제 영상 검증이 아님 |
| 미실행 | 실제 Android 기기, 화면 끔/Doze, Bluetooth, 제조사 절전, 외부 오디오 앱, 실제 네트워크 재생 |

Checkstyle XML 오류 0개 및 ktlint PASS ([최종 스타일 로그](evidence/detail-sleep-timer-2026-10-05/style-final.log)). `git diff --check` PASS. 최종 독립 리뷰의 disconnect 처리 지적도 기존 화면 종료 경로를 재사용하여 해소했다. 원격 CI 결과는 아래 최종 기록에 추가한다. 현재 변경을 실기기 검증 완료 또는 배포 완료로 읽으면 안 된다.

### 재현 명령

JDK 21, 프로젝트 Gradle wrapper 사용. 기본 Android 검사는 설치된 36.0.0 SDK build tools가 필요하다.

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest \
  -DskipFormatKtlint -Pandroid.builder.sdkDownload=false --no-daemon --console=plain
./gradlew :app:runCheckstyle :app:runKtlint \
  -DskipFormatKtlint -Pandroid.builder.sdkDownload=false --no-daemon --console=plain
```

분리 JVM harness는 Java plugin/JDK21, Maven Central의 RxJava 3.1.12/JUnit4 4.13.2를 사용한다. main source는 `app/src/main/java/org/schabi/newpipe/player/helper/SleepTimer.java`만, test source는 `SleepTimerTest.java`와 `SleepTimerLayoutTest.java`만 포함한다. `test.workingDir`는 저장소의 `app` 디렉터리다. Android 전체 suite나 UI helper의 런타임 검증으로 대체하지 않는다.

## 독립 리뷰

읽기 전용 별도 담당이 실제 diff를 검토했다. 명확한 앱 컴파일 오류나 재생 제어 회귀를 찾지 못했으며, 짧은 WAV의 자연 종료 오탐 방지와 회전 후 대화상자 소멸 assertion 보완을 권고했다. 테스트에서 repeat-one을 지정하고 재클릭 전에 기존 창이 사라졌는지 확인하도록 반영했다. disconnect 후 이미 전달 대기 중인 클릭을 막는 dialog identity guard도 추가했다. 이 경합은 재현된 실패가 아닌 방어적 수명주기 처리다.

## 통합 범위

`feat/detail-sleep-timer`는 main `84b4e0e85f02f29a8523486345db5fc5df9e395d`에서 만든 격리 worktree다. 기존 문서 Draft PR #1과 원래 checkout을 보존한다. 새 Draft PR만 만들며 merge, 새 릴리스, 배포, SDK 설치, 인증/권한 변경은 하지 않는다.
