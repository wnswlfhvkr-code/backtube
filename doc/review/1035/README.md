# Backtube APK 1035 — 웹 Pro 전체 검수용 소스

## 기준과 범위

실제 APK 빌드 소스는 `486fe10f24c2ed1a1e38d3b8769fd924cc25cac5`입니다.
이 보존 브랜치는 해당 커밋의 후손이며 이후 제품 코드·리소스·빌드 설정·테스트 변경 없이
작업 일지와 이 검증 자료만 추가했습니다. 웹 리뷰 전 최적화는 적용하지 않았습니다.

전체 소스, 리소스, Gradle wrapper/설정, 테스트, LICENSE 및 작업 일지를 포함합니다.
비밀키·인증정보·개인 캡처·로컬 캐시·빌드 결과 APK는 검수 ZIP에 포함하지 않습니다.

## APK 연결

- 파일: `backtube-first-selection-486fe10-1035-20261024.apk`
- versionCode: `1035`
- versionName: `0.29.1-backtube-first-selection-20261024`
- 패키지: `org.schabi.newpipe.continuous.testofflinelibrary20261011`
- SHA-256: `36a8c8984eb4809f93b818cb86d7693dc87e2a532be25d989fbd8bab2c6e0151`
- Library: `libfile_91ded02391a48191b9b8b09623062e44`
- 기존 debug 서명 유지. 서명 키는 포함하지 않습니다.

## 빌드 맥락

JDK 21과 저장소에 정의된 Android SDK/Gradle 의존성을 사용했습니다.
실제 빌드는 `:app:assembleContinuous`, `-DversionCodeOverride=1035`,
`-DversionNameSuffix=-backtube-first-selection-20261024`, `-PappFaultEndpoint=`로 실행했습니다.
기존 서명 입력을 검증하는 실행기 전용 init script는 키 경로를 포함하므로 제외했습니다.

패키지 suffix는 브랜치 이름을 정규화하므로 보존 브랜치에서 바로 빌드하면
실제 전달 APK와 패키지가 달라집니다. 원래 빌드 브랜치는
`test.offline_library.20261011`이며 정규화 값은 `testofflinelibrary20261011`입니다.
키와 빌드 캐시는 이 자료에 없으므로 동일 서명 재빌드를 주장하지 않습니다.

## 검수 우선순위

1. 앱 완전 종료 뒤 명시적으로 선택한 A의 큐·미디어·화면 정보 일치.
2. 첫 A 소스 오류 시 관련 B로 건너뛰지 않고 A 오류/재시도 유지.
3. 수동 retry/새 선택/늦은 콜백, 정상 완료 B, 수동 Next 및 빠른 Next→Play.
4. 화면 재생성 시 이미 적용된 MAIN 요청을 중복 발송하거나 엔진을 stop하지 않는지.
5. 팝업·백그라운드·듣기 모드, 저장 영상/오디오, 위치·큐·음량·타이머 보존.
6. 기존 저장본 썸네일·종류별 문구·중복 저장 Toast의 회귀 가능성.

리뷰는 원인·재현 조건·영향·최소 수정 제안으로 작성하세요.
이 자료 자체에는 새 최적화나 미검증 수정이 적용되지 않았습니다.

## 실제 수행 검증과 한계

- JVM 앱 560 + 공유 14 PASS, Checkstyle PASS, lint 오류 0 / 경고 346.
- Android 23 집중 56 PASS / 1 NOT RUN, 강제 종료 13개 시나리오 PASS.
- 1033→1035 업데이트의 저장 메타데이터·미디어 보존 및 설치 APK 해시 일치 PASS.
- 최종 후보의 움직이는 영상·시간 증가·선택 제목은 직접 픽셀 확인 PASS.
- 최종 UI XML 자동 수집 FAIL은 유지하며 직접 화면 검수로 보완했습니다.
- Android 35는 시작 전 ANR로 0개 실행, API23 H.264는 시스템 decoder SIGILL로 미검증.
- 실기기·실제 공급자 추출/다운로드·전체 Android suite·새 원격 CI는 NOT RUN.

세부 수치는 `verification.json`, 빌드 근거는 `build-receipt.json`,
요청·재현·수정·실패와 해결 이력은 `../../work-log/2026-10-23-backtube-final-release.md`에 있습니다.
이 보존 작업에서는 테스트 재실행이나 릴리스 발행을 하지 않습니다.
