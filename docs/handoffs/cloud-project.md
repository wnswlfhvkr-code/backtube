---
artifact_contract: "ce-handoff/v1"
created_at: "2026-09-30T15:46:30Z"
title: "backtube 클라우드 프로젝트 핸드오프"
summary: "v9 메인 반영·APK 배포, 최종 추천 정책, 로컬 검증과 API 23 CI 실패를 인계하는 스냅샷"
keywords: ["backtube", "NewPipe", "cloud", "autoplay", "recommendations", "API23", "APK"]
cwd: "C:/Users/박준영/Documents/ChatGPT/백그라운드 유튭/backtube"
resume_focus: "클라우드에서 v9 상태를 확인하고, 다음 작업이 요청되면 API 23 CI 실패부터 재현·진단"
repository: "wnswlfhvkr-code/backtube"
repo_root_sha: "73d61f17b55f23951dc60dc9fbd469c99fb0adaa"
branch: "codex/data-saver"
head: "a50711c1a937d1197f9aad2c0fe00108bca819a7"
---

# backtube 클라우드 프로젝트 인계

한국 시간 2026-10-01 작성. 사용자는 클라우드 프로젝트로 이동했다고 알리고 핸드오프 문서를 요청했습니다. 이 스냅샷은 기존 Windows 작업 공간에서 작성했으며, 클라우드 체크아웃·SDK·에뮬레이터·인증 상태는 확인하지 않았습니다. 아래 내용은 이전 작업의 상태와 근거이고, 새 작업이나 배포를 자동으로 시작하라는 지시가 아닙니다.

## 현재 상태와 최종 사용자 의도

저장소는 [wnswlfhvkr-code/backtube](https://github.com/wnswlfhvkr-code/backtube), 앱은 NewPipe 0.29.1 기반 Android 포크입니다. 재생 추출기·엔진은 기존 NewPipe/ExoPlayer를 사용하며 별도 캐시 엔진을 추가하지 않았습니다.

확인 시점 로컬 HEAD와 원격 `main`은 모두 `a50711c1a937d1197f9aad2c0fe00108bca819a7`입니다. 로컬 작업 브랜치는 `codex/data-saver`이고 작업 트리는 깨끗했습니다. 기능 수정은 PR 머지 없이 `main`에 직접 push되었습니다. 이 핸드오프를 저장하는 문서 커밋은 기능 커밋보다 이후에 생성됩니다.

사용자의 이전 요청과 최종 교정은 다음과 같습니다. 이전 메시지의 정확한 시각은 이 문서에서 새로 부여하지 않습니다.

- 노래를 직접 선택하면 추가 재생 버튼 없이 재생되어야 합니다. 화면만 여는 상세 보기·플레이어 전환은 기존 일시정지를 보존합니다.
- 곡이 자연 종료될 때 다음곡으로 넘어가야 합니다. 이미 대기열에 다음곡이 있으면 자동 대기열 OFF에서도 넘어갑니다. 마지막 곡 이후 추천을 계속 추가하려면 자동 대기열 ON이 필요합니다.
- ‘다음’ 목록 첫 항목과 실제 다음 재생곡이 같아야 합니다. 대기열에 남은 곡을 추천으로 다시 추가해 A→B→A처럼 반복하지 않아야 합니다. 후보가 소진되면 정지합니다.
- **최종 교정:** “삭제하면 다시 추천해도 되지 … 추천안함 보기싫음 … 그거하면 안뜨면 될듯”. 삭제한 곡은 재추천할 수 있습니다. 명시적으로 제외한 영상·채널은 추천 목록과 자동 선택에서 숨깁니다. 삭제한 곡을 영구 기억하는 ledger·새 세션 저장 필드는 최종 구현에 없습니다.
- 수동 대기열 우선순위와 사용자가 선택한 반복 모드는 유지됩니다. 중복 방지는 자동 추천에 적용됩니다.
- GitHub에서 APK를 쉽게 받을 수 있도록 README 맨 위에 다운로드 바로가기를 둡니다.

이전 사용자가 첨부한 화면 녹화는 구버전이라고 설명한 자료입니다. 현재 v9의 동작 증거로 취급하지 않습니다.

## 배포된 결과

- 소스: [v9 기능 커밋](https://github.com/wnswlfhvkr-code/backtube/commit/a50711c1a937d1197f9aad2c0fe00108bca819a7).
- APK: [backtube-v9.apk](https://github.com/wnswlfhvkr-code/backtube/releases/download/v0.29.1-backtube.9/backtube-v9.apk), [릴리스 페이지](https://github.com/wnswlfhvkr-code/backtube/releases/tag/v0.29.1-backtube.9). 공개된 릴리스이며 초안이 아닙니다.
- 패키지 `org.schabi.newpipe.personal`, 버전 `0.29.1-backtube.9`, 코드 `1023`, 크기 `11543930` bytes.
- APK SHA256: `82cc5ed8f1efc0777b7c876973ca6c400fb39f93080d813eccdbc571eefb6961`.
- 서명 인증서 SHA256: `78fa3e7d3dd06d25f32f42f0409c5fc9738448060f2cf4be7a6693ae37a5e5e4`.
- GitHub에서 APK를 실제로 다시 내려받아 로컬 배포 파일과 해시가 일치함을 확인했습니다. v1/v2/v3 서명·zipalign 검사, 에뮬레이터 v8/1022→v9/1023 업데이트 설치 및 릴리스 MainActivity 실행을 확인했습니다.

v9 문서와 배포는 완료됐지만, 아래 원격 API 23 CI 실패는 해결되지 않았습니다. 로컬 검증과 원격 CI 상태를 구분해야 합니다.

## 코드와 문서의 읽기 지점

모든 상대 경로는 위 저장소 기준입니다.

| 참조 | 이어받을 때 중요한 내용 |
| --- | --- |
| `README.md` | 최신 APK 링크, 사용자 기능 설명, 개인용 release 빌드 옵션, 삭제와 제외의 차이 |
| `docs/verification/backtube-v9.md` | 최신 동작, 테스트 범위·제외 단계, 서명·업데이트·초기 실패 기록 |
| `docs/verification/backtube-v8.md` | 직접 선택 시 즉시 재생과 수동 상세 보기 구분, 자연 종료 테스트, Windows 전체 JVM 테스트의 한글 경로 문제 |
| `docs/verification/backtube-v7.md` | 네트워크 전환 시 현재 오디오 품질·위치·일시정지 보존, 기존 lint 보고의 범위 |
| `app/src/main/java/org/schabi/newpipe/player/Player.java` | `getRelatedItemsForPlayback()`이 실제 next/preview를 첫 항목으로 구성. `requestRecommendation()`/`maybeAutoQueueNextStream()`은 기존 순서와 queue/exclusion 필터를 사용. 제외 및 EMPTY 상태는 `triggerProgressUpdate()`로 일시정지 화면에도 즉시 전달 |
| `app/src/main/java/org/schabi/newpipe/fragments/detail/VideoDetailFragment.java` | phone/tablet 관련 목록 생성, `refreshRelatedItems()`, 같은 URL의 metadata 조기 반환 전 갱신, progress 갱신. 종료 시 listener 해제·지연 callback binding 검사. `playOnSelection`이 직접 선택의 즉시 재생 요청을 소비 |
| `app/src/main/java/org/schabi/newpipe/fragments/list/videos/RelatedItemsFragment.java` | 관련 목록을 복사해 사용, URL/service 키로 변경 여부 비교, 복원 adapter 재설정, 초기 빈 목록에서 `currentInfo != null` 가드 |
| `app/src/main/java/org/schabi/newpipe/player/helper/PlayerHelper.java` | `autoQueueOf()`는 원래부터 첫 번째 적격 추천을 순서대로 선택. 이전 불일치의 핵심은 화면이 원본 목록을 그대로 보여주던 것 |
| `app/src/main/java/org/schabi/newpipe/player/helper/RecommendationExclusions.java` | 기존 영상·채널 제외 저장과 취소. 자동 추천에서 사용하며 수동 선택까지 영구 차단하는 기능은 아님 |
| `app/src/androidTest/java/org/schabi/newpipe/player/PersonalPlaybackTest.java` | 실제 서비스·ExoPlayer와 생성 WAV/합성 StreamInfo 사용. `relatedListStartsWithActualNextAndHidesUsedSongs`, `removedSongCanReturnUnlessExplicitlyExcluded`, `naturalEndActuallyPlaysRecommendedAudioThroughTheMediaSourceManager`가 핵심 회귀 |
| `app/build.gradle.kts` | debug applicationId가 Git 브랜치에 따라 달라짐. release의 `.personal` suffix와 버전은 빌드 옵션으로 지정 |
| `.github/workflows/ci.yml` | JDK 21 JVM 단계 및 API 23/x86·API 35/x86_64 Android matrix. 현재 원격 실패의 진입점 |
| `.github/workflows/build-release-apk.yml` | 수동 실행하는 **unsigned** release 빌드. 현재 명령은 개인 suffix/버전 override/개인 서명을 자동 제공하지 않으므로 배포 APK와 동일한 결과라고 가정하면 안 됨 |

## 검증 범위와 현재 CI 문제

### 로컬 완료 검증

v9 최종 소스에서 관련 단위 테스트 56개 실패·오류 0, Checkstyle 보고 0, Kotlin lint, Debug·계측·Release 빌드 및 release 필수 lint가 통과했습니다.

Android 15 전용 에뮬레이터에서 `OK (34 tests)`를 확인했습니다. 실제 실행 32개·실패 0이고, 프로세스 재시작 단계 및 별도 네트워크 정책 단계 2개는 필요한 실행 인자가 없어 제외됐습니다. 실제 곡 종료를 기다린 A→B→C→정지, 목록 첫 곡 일치, 추천 교체, 회전 복원, 마지막 후보 제외 후 빈 목록, 삭제 후 재추천, 타이머·직접 선택 재생 회귀를 포함합니다. 세로·가로 화면도 캡처해 확인했습니다.

실제 YouTube 추천 추출·휴대폰 재생·갤럭시 화면 꺼짐·실제 데이터 절감량은 v9에서 실측하지 않았습니다. 전체 JVM 테스트의 Windows 실행은 앞선 v8에서 한글 리소스 경로가 percent-encoded 상태로 남는 백업 테스트 13개 실패가 있었으므로, 로컬 전체 JVM 통과로 표현하지 않습니다. 이번에는 관련 56개만 실행했습니다.

### 핸드오프 작성 중 새로 확인한 원격 상태

[v9 CI 실행 36737001578](https://github.com/wnswlfhvkr-code/backtube/actions/runs/36737001578), 대상 SHA `a50711c1a...`:

- `build-and-test-jvm`: 성공. Linux CI의 `assembleContinuous lintContinuous testDebugUnitTest` 단계는 통과했습니다. 다만 `lintContinuous`는 기존 설정상 오류를 보고해도 종료 코드를 성공으로 낼 수 있습니다.
- `test-android (23, default, x86)`: 실패. 59개 시작, 21개까지 진행 후 실행 중단.
- `relatedListStartsWithActualNextAndHidesUsedSongs`: `startActivitySync(MainActivity)`가 45,000ms 안에 launch되지 않아 RuntimeException. 보고된 테스트 위치는 777행입니다.
- `removedSongCanReturnUnlessExplicitlyExcluded`도 실패로 표시됐으나 이 로그에는 구체적인 assertion/stack이 없습니다. 마지막에는 `Instrumentation run failed due to Process crashed`가 기록됐습니다.
- API 35 job: 취소. API 23 실패 후 matrix fail-fast로 취소된 것으로 보이지만 이 인과는 설정과 상태에서의 추론입니다. API 35 원격 통과를 주장하지 않습니다.

근본 원인은 아직 진단하지 않았습니다. 단순 flaky test나 제품 재생 버그로 단정하지 않습니다. 다음 디버깅이 요청된다면 해당 CI의 Android 보고서 artifact·threaddump/logcat 확보와 API 23의 실패 테스트 재현이 가장 구체적인 출발점입니다. 이번 핸드오프 요청에서는 앱 코드나 CI를 수정하지 않았습니다.

## 클라우드 빌드·배포 경계

JDK 21, 프로젝트가 요구하는 Android SDK 및 라이선스가 필요합니다. 클라우드 경로를 먼저 확인해야 하며 Windows의 SDK/JDK/임시 디렉터리를 복사해서 가정할 수 없습니다. 로컬 전용 `local.properties`는 Git 저장소에 포함되지 않을 수 있습니다.

Linux shell의 v9 unsigned 개인용 빌드 예시는 다음과 같습니다. 빌드가 성공해도 **같은 개인 키로 서명하기 전에는 기존 개인용 앱의 업데이트 APK가 아닙니다.**

```bash
./gradlew :app:assembleRelease \
  '-DpackageSuffix=.personal' \
  '-DversionNameSuffix=-backtube.9' \
  '-DversionCodeOverride=1023' \
  '-DskipFormatKtlint' --console=plain
```

관련 단위 테스트를 다시 확인할 때의 기존 선택 범위:

```bash
./gradlew :app:testDebugUnitTest :app:runCheckstyle :app:runKtlint \
  --tests 'org.schabi.newpipe.player.helper.*' \
  --tests 'org.schabi.newpipe.player.playqueue.*' \
  --tests 'org.schabi.newpipe.util.ListHelperTest' \
  --tests 'org.schabi.newpipe.util.image.ImageStrategyTest' \
  '-DskipFormatKtlint' --console=plain
```

Windows에서는 `gradlew.bat`를 쓰고 `-D`/`-P` 인자를 따옴표로 감쌌습니다. 한글 경로 때문에 `'-Pandroid.overridePathCheck=true'`도 사용했습니다. 클라우드에서는 같은 문제가 있을 때만 해당 옵션이 필요합니다.

Debug 테스트 APK의 패키지를 로컬 이름으로 하드코딩하면 안 됩니다. 이전 로컬 브랜치는 `org.schabi.newpipe.debug.codexdatasaver`였고, 이번 CI main은 `org.schabi.newpipe.debug.main`입니다. 실제 빌드 metadata와 adb package 목록을 기준으로 runner를 선택합니다. 클라우드 에뮬레이터 사용 가능 여부는 미확인입니다.

개인 서명키와 암호는 저장소·이 문서·클라우드로 옮기지 않았습니다. 기존 APK 업데이트용 새 릴리스는 동일 키에 대한 별도 접근이 필요합니다. 버전 코드 1023은 이미 배포됐으므로 다음 실제 배포 버전은 코드 증가가 필요합니다. 다음 버전명은 아직 사용자와 확정하지 않았습니다.

## 이 호스트에만 남아 있는 자료

아래는 **Windows 로컬 자료**이며 GitHub checkout만으로는 보이지 않습니다.

- 작업 공간: `C:/Users/박준영/Documents/ChatGPT/백그라운드 유튭/`.
- 위 작업 공간의 `verification/v9-runtime-delivery.txt`, `v9-logcat-delivery.txt`, `v9-source-build-inputs.json`, `v9-delivery-manifest.json`, release·서명·업데이트 로그와 화면 캡처. 핸드오프 작성 시 CI 실패 로그도 `verification/v9-ci-handoff-failed.txt`에 보존했습니다.
- 서명 자료는 위 작업 공간의 `.private/`에만 있습니다. 파일 내용이나 암호는 문서에 포함하지 않습니다.
- 최종 APK의 로컬 복사본은 위 작업 공간의 `dist/backtube-v9.apk`입니다. 클라우드에서는 검증된 GitHub 릴리스 asset을 받을 수 있습니다.
- 이전 테스트 전용 에뮬레이터 AVD는 `CodexYouTubeTest`, 로컬 adb serial은 `emulator-5554`였습니다. 실제 휴대폰을 대상으로 시험하지 않았습니다. 이 이름·serial은 클라우드 장치를 식별하는 근거가 아닙니다.
- 병렬 검토 agent들은 완료됐습니다. 이어받아 실행할 미완성 앱 수정·목표·자동화는 이 문서 작성 범위에서 없습니다. 클라우드에서 이미 진행 중인 별도 작업이 있다면 그 최신 상태가 우선합니다.

사용자의 작업 선호는 한국어 응답, 깊게 묶어서 판단·실행, 기존 구조 보존, 필요한 범위의 검증, 승인 질문 최소화였습니다. 실제 클라우드의 권한·정책과 현재 사용자 지시는 별도로 적용됩니다. 이 문서 자체는 새로운 실행 권한을 부여하지 않습니다.
## 2026-10-05 플레이 상세 화면 타이머 작업

별도 `feat/detail-sleep-timer` 브랜치에서 영상 상세 액션 행의 백그라운드 오른쪽에 기존 취침 타이머 진입/상태/변경/취소 UI를 연결했다. 백그라운드 버튼의 오디오 재생 기능은 유지한다. [검증 및 제한 기록](../verification/detail-sleep-timer-2026-10-05.md)에 재현 명령, JVM/Android/실기기 범위와 원격 CI를 구분한다. main 및 v9 배포본에 반영됐다고 가정하지 말 것.
