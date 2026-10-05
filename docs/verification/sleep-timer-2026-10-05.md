# 기존 취침 타이머 확인 — 2026-10-05

## 결론과 변경 범위

**취침 타이머는 이미 구현되어 있다.** 시간 선택·남은 시간·취소/변경 UI부터 공통 Player의 재생 일시정지까지 연결되어 있어 중복 구현하지 않았다. 이번 변경은 README 사용 안내, 이 검증 기록과 실행 근거뿐이다. 앱 코드·테스트·의존성 선언·권한·워크플로·서명·배포는 변경하지 않았다.

확인한 main은 [`84b4e0e85`](https://github.com/wnswlfhvkr-code/backtube/commit/84b4e0e85f02f29a8523486345db5fc5df9e395d)이며, 점검 시작 시 열린/닫힌 PR은 없었다. [공개 v9 릴리스](https://github.com/wnswlfhvkr-code/backtube/releases/tag/v0.29.1-backtube.9)의 대상 소스 `a50711c1a...`에도 같은 타이머가 있다. 사용자의 현재 설치 APK 버전은 확인하지 않았다. 저장소와 상위 경로의 `AGENTS.md` 및 `.agents/skills`는 발견되지 않았다. 기존 클라우드·잠자리 인계와 요청된 brainstorming/TDD/검증 지침을 읽었다. 기능 추가 경로가 아닌 기존 기능 확인으로 결론 냈으며, 새 제품 코드나 red/green 구현 주기는 없다.

## 사용 경로

1. 곡이나 영상을 재생하고 **홈 → 이어듣기**를 연다. 화면 폭에 따라 이어듣기는 홈의 ⋮ 메뉴에 있다. 백그라운드 오디오/팝업 재생 알림 또는 상세 화면의 **재생 대기열 열기**도 같은 화면으로 연결된다.
2. 화면 하단의 **타이머: 끔**을 누른다. 15/30/60/90/120분 또는 **직접 설정 (분)**의 1~1,440분을 고른다.
3. 버튼은 `타이머: N분`으로 남은 시간을 올림해 표시한다. 다시 열어 시간을 교체하거나 **15분 추가**, **타이머 취소**를 사용한다.
4. **현재 항목 끝나면 종료**는 현재 곡의 끝에서 멈추며 라이브에서는 선택할 수 없다. **종료 전 음량 줄이기**는 마지막 10초 동안 음량을 낮추는 기존 옵션이다.

만료 시 영상/오디오를 일시정지한다. 앱 종료나 휴대폰 전원 종료는 수행하지 않는다. 프로세스 종료 후에는 타이머를 복원하지 않으며, 저장된 대기열은 일시정지 상태로 복원한다. 사용자가 다시 재생하려면 재생 버튼을 누르고 필요한 타이머를 새로 설정한다.

## 연결·수명주기·경계 검토

아래는 **소스와 기존 테스트의 검토 결과**다. Android 실행을 새로 통과했다는 뜻이 아니다.

| 확인 항목 | 기존 구현과 근거 |
| --- | --- |
| UI에서 Player까지 연결 | `MainFragment.onOptionsItemSelected()` / `NavigationHelper.openPlayQueue()` → `PlayQueueActivity` → `showSleepTimerDialog()` → `Player.setSleepTimer()` → `SleepTimer.start()` |
| 남은 시간·취소·변경 | `PlayQueueActivity.updatePlaybackOptions()`는 남은 분을 표시. UI 갱신은 화면 활성 중 1초 주기. 다이얼로그가 시작·교체·연장·취소 메서드에 연결됨. 세로/가로 레이아웃 모두 버튼 존재 |
| UI를 닫거나 화면을 꺼도 타이머 유지 | 타이머는 Activity가 아니라 `PlayerService`가 소유한 `Player`에 있음. Activity `onPause/onDestroy`는 화면 갱신만 중지하며 타이머를 취소하지 않음. Player는 기존 foreground 재생 서비스와 `C.WAKE_MODE_NETWORK` 사용 |
| 만료 동작 | `Player`의 `onExpired`가 만료 플래그를 세우고 `pause()` 호출. 오디오 포커스와 재생을 중지하고 지연 추천 요청도 해제 |
| 중복·취소·재설정 경합 | main-thread 한정 타이머와 `SerialDisposable` 하나를 사용. `start()`는 이전 예약을 취소한 뒤 교체. 취소·만료는 deadline을 0으로 비우며 재검사로 중복 만료하지 않음 |
| 만료 후 뜻하지 않은 재시작 | `onPlayWhenReadyChanged()`가 만료 및 expired 플래그를 검사해 ExoPlayer의 수동 우회 재생을 다시 pause. 자동 다음곡·지연 추천도 만료 플래그 확인. 명시적 사용자 `Player.play()`는 기한을 먼저 검사한 뒤 플래그를 해제해 다시 듣기를 허용 |
| 곡·영상 전환 | 시간 기반 타이머는 Player에 남고 `initPlayer()`가 이미 지난 기한을 검사. 현재 항목 종료 모드는 명시적 다음/이전/다른 항목 선택 시 취소. 일반 시간 기반 타이머를 트랙마다 새로 만들지 않음 |
| 기기 수면 뒤 지난 기한 | deadline은 `SystemClock.elapsedRealtime()` 기반. 타이머 tick, 화면 켜짐, 플레이어 초기화/재생 상태 변화/남은 시간 조회에서 `expireIfDue()` 확인 |
| 프로세스 재생성 | `PlayerService`는 `START_NOT_STICKY`. 타이머 deadline은 세션 저장에 포함하지 않음. `Player.destroy()`는 타이머 취소. 세션 복원은 `PLAY_WHEN_READY=false`, `RESUME_PLAYBACK=false`이며 타이머 0. 기존 계측 테스트가 이 정책을 명시 |
| 권한 | 기존 WAKE_LOCK/foreground-media-playback 권한 사용. 타이머용 정확 알람이나 휴대폰 종료 권한 없음. 이번 변경에서 새 권한 추가 없음 |

Android 공식 문서상 [Handler 지연 콜백](https://developer.android.com/reference/android/os/Handler#postDelayed(java.lang.Runnable,%20long))은 deep sleep 때문에 늦어질 수 있다. 따라서 elapsed-time 재검사와 기존 wake mode를 확인한 것만으로 제조사별 화면 꺼짐/절전 동작의 정시 만료를 보장하지 않는다. [서비스의 START_NOT_STICKY 계약](https://developer.android.com/reference/android/app/Service#START_NOT_STICKY)도 새 요청 없는 자동 재시작을 하지 않는 기존 정책과 부합한다. [백그라운드 재생 공식 가이드](https://developer.android.com/media/media3/session/background-playback)의 서비스 소유 원칙을 참고했으며, 기존 ExoPlayer 2 구조를 Media3로 바꾸지는 않았다.

주요 소스: `app/src/main/java/org/schabi/newpipe/player/helper/SleepTimer.java`, `Player.java`, `PlayerService.java`, `PlayQueueActivity.java`, `helper/LastPlaybackSessionStore.java`, `app/src/main/res/values-ko/personal_playback_strings.xml`.

## 이번 실행 결과

2026-10-05 UTC, 기존 JDK 21.0.12.1/Gradle 9.6.1/Android SDK 환경 사용. 이전 offline 검증의 Kotlin 캐시 부족은 이번 일반 의존성 해석에서 해소돼 `buildSrc` 컴파일까지 진행했다. SDK 설치·라이선스·인증 설정은 변경하지 않았다.

| 검증 | 결과 | 범위 |
| --- | --- | --- |
| 기존 `SleepTimerTest` 순수 JVM 실행 | **PASS — 7 tests / 0 failures / 0 errors / 0 skipped** | 아래 분리한 Java harness에서 기존 생산 소스·기존 테스트와 실제 RxJava/JUnit을 그대로 사용. Android 통합 또는 화면 꺼짐 실측이 아님 |
| `:app:runCheckstyle :app:runKtlint` | **PASS** | 종료 0. Checkstyle XML 위반 0. Gradle deprecation 및 읽기 전용 analytics 경로 경고는 남음 |
| 앱 타이머 단위 테스트 + Debug 빌드 통합 명령 | **FAIL — 환경** | task 의존성 계산에서 `Failed to find Build Tools revision 36.0.0`, 종료 1. 설치된 build-tools는 37.0.0뿐. 앱 테스트·Debug APK 빌드 task는 실행되지 않음 |
| 전체 앱 JVM suite / release / 계측 APK 빌드 | **SKIP** | 같은 build-tools 누락으로 막혀 중복 빌드하지 않음 |
| Android 계측 / 화면 꺼짐 / 실제 휴대폰 | **미실행** | emulator/system-images가 설치되지 않음. 이전 adb read-only 경로 문제는 권한·인증 변경 없이 미해결로 유지. adb 연결·설치·장치 조작 없음 |
| 문서·증거 검사 | **PASS** | 상대 링크, JSON, 변경 범위, 공백·비밀값 패턴 검사 |

앱 명령:

```bash
. /workspace/.backtube-environment/env.sh
./gradlew :app:testDebugUnitTest \
  --tests org.schabi.newpipe.player.helper.SleepTimerTest \
  :app:runCheckstyle :app:runKtlint :app:assembleDebug \
  -DskipFormatKtlint -Pandroid.builder.sdkDownload=false --no-daemon --console=plain
# 위 명령은 build-tools 누락으로 종료 1. 독립적으로 가능한 style task는 따로 실행:
./gradlew :app:runCheckstyle :app:runKtlint \
  -DskipFormatKtlint -Pandroid.builder.sdkDownload=false --no-daemon --console=plain
```

빌드에서 막힌 Android 경로와 독립된 **기존 7개 타이머 단위 테스트**를 실행하기 위해 임시 Java Gradle 프로젝트를 사용했다. 의존성 버전은 저장소 `gradle/libs.versions.toml`과 동일하다. mock Android/가짜 RxJava 구현이나 제품 코드 복제는 사용하지 않았다.

`/workspace/scratch/backtube-timer-20261005/jvm-check/build.gradle`의 실행 내용:

```groovy
plugins { id 'java' }
repositories { mavenCentral() }
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
sourceSets {
    main.java {
        srcDir '/workspace/backtube/app/src/main/java'
        include 'org/schabi/newpipe/player/helper/SleepTimer.java'
    }
    test.java {
        srcDir '/workspace/backtube/app/src/test/java'
        include 'org/schabi/newpipe/player/helper/SleepTimerTest.java'
    }
}
dependencies {
    implementation 'io.reactivex.rxjava3:rxjava:3.1.12'
    testImplementation 'junit:junit:4.13.2'
}
test {
    useJUnit()
    testLogging { events 'passed', 'skipped', 'failed' }
}
```

같은 디렉터리의 `settings.gradle`은 `rootProject.name = 'backtube-existing-sleep-timer-verification'` 한 줄이다. 저장소 wrapper로 `./gradlew -p /workspace/scratch/backtube-timer-20261005/jvm-check test --no-daemon --console=plain`을 실행했다. 다른 경로에서 재현할 때는 두 `srcDir`을 해당 checkout으로 바꾼다.

증거: [테스트 결과·소스 해시](evidence/2026-10-05-sleep-timer/results.json), [순수 JVM 로그](evidence/2026-10-05-sleep-timer/standalone-unit.txt), [앱 빌드 실패 로그](evidence/2026-10-05-sleep-timer/focused-build.txt), [스타일 검사 로그](evidence/2026-10-05-sleep-timer/style.txt). 환경 변수 출력은 제거했으며 토큰·사용자 재생 데이터는 보존하지 않았다.

## Android 검증 인계

`PersonalPlaybackTest`에 만료 후 예상치 못한 재생 차단과 명시적 사용자 재생, 재생 중 취소/재설정, 반복곡 끝에서 정지, 음량·mute, UI 세로/가로, 저장 세션 복원 및 별도 프로세스 재시작 단계가 이미 있다. **테스트 존재와 이번 통과는 다르며 위 계측 테스트는 이번에 실행하지 않았다.**

SDK가 갖춰진 승인 환경에서 기존 계측 테스트를 실행하고, 실제 Android에서 1분 설정 후 화면을 끄거나 다른 앱으로 이동했을 때 정지하는지 확인해야 한다. 곡 전환 중 기한 유지, 취소 후 계속 재생, 만료 후 지연 콜백 재생 방지, 명시적 재생, 프로세스 종료/복원도 확인한다. 프로세스 재시작 테스트는 seed/restore 단계를 분리해야 한다.

최신 main의 기존 [CI 36739430876](https://github.com/wnswlfhvkr-code/backtube/actions/runs/36739430876)은 전체 실패 상태다. 과거 JVM 성공 또는 이전 v9의 생성 WAV 회귀 기록을 이번 실기기 검증으로 표현하지 않는다. 이번 결과는 문서 Draft PR로 보존하며 main 병합·새 APK 배포는 하지 않는다.
