# backtube v7 검증

2026-09-30. v6 위에 네트워크 전환 즉시 음질 적용과 저장소 CI·빌드 이름을 수정했습니다.

## 변경

- 절약 모드에서 요금제 네트워크로 바뀌면 현재 오디오를 저음질로 다시 선택하고, 해제되면 저장한 선호 음질로 복원합니다. 현재 항목·재생 위치·일시정지를 유지합니다.
- Android 기본 네트워크 콜백이 전달한 요금제 상태를 음질·이미지 선택에 공유합니다. 같은 유효 품질이면 재로드하지 않으며, 절약 모드가 꺼져 있으면 네트워크 변경으로 재로드하지 않습니다. 서비스 종료 시 콜백과 공유 상태를 정리합니다.
- API 23에서는 기존 연결 브로드캐스트로 이미 연결된 네트워크 사이의 기본 경로 전환을 보완합니다.
- `main` push/PR CI, `main` 수동 릴리스 빌드, backtube 릴리스 아티팩트·Continuous 표시명을 적용했습니다. 계측 테스트의 서비스 시작은 API 23에서도 동작하는 ContextCompat 경로로 변경했습니다.

## 확인

- 최종 Debug·테스트 APK, Continuous·Release 빌드 및 릴리스 필수 lint 통과. Checkstyle 오류 0, Kotlin lint 통과.
- 관련 단위 테스트 62개, 실패·오류 0. 오디오·이미지·대기열·추천·세션·타이머·홈 탭 포함.
- Android 15 전용 에뮬레이터: `OK (30 tests)`. 별도 프로세스 단계 전용 테스트 1개 제외, 실제 실행 29개·실패 0. 생성 WAV·합성 메타데이터만 사용했습니다.
- 실제 Wi-Fi 요금제 정책을 세 차례 왕복 전환해 320→48→320 kbit/s 소스 선택, 1,200ms 위치·일시정지·고음질 선호값 유지 확인. 중복 이벤트와 절약 OFF+기존 모바일 제한 설정에서도 불필요한 재로드가 없었습니다. 정책은 원래 상태로 복원했습니다.
- Android 15에서 오래된 콜백 스냅샷을 넣고 API 23 브로드캐스트 처리 경로의 복구를 확인했습니다. 실제 Android 6 Wi-Fi→셀룰러 전환은 실행하지 않았습니다.
- 독립 검토의 상태 조회 경합과 API 23 경로 누락을 수정하고 재검토 완료. 세로·가로 재생 화면 확인.
- 같은 개인 키의 APK v1/v2/v3 서명·zipalign 확인. v6/1020→v7/1021 업데이트 성공 후, 최종 APK를 다시 설치하고 MainActivity `Status: ok` 확인. 실제 휴대폰은 변경하지 않았습니다.

`lintContinuous`는 기존 설정에 따라 명령이 성공하지만 AboutActivity의 MissingClass 1개와 기존 KeyboardUtil·PlayerService의 WrongConstant 2개를 보고합니다. 이번 변경 파일에서 새 lint 오류는 없습니다. 해당 원본 경로는 이번 수정 범위에서 변경하지 않았습니다.

## APK

- 파일: `dist/backtube-v7.apk`
- 패키지: `org.schabi.newpipe.personal`
- 버전: `0.29.1-backtube.7` / 1021
- SHA256: `731C74F0F15E17CEE701475F5F7777B7BAB2FCCF26B9740556AC577405AC841F`
- 인증서 SHA256: `78fa3e7d3dd06d25f32f42f0409c5fc9738448060f2cf4be7a6693ae37a5e5e4`

## 증거와 범위

워크스페이스의 `build-v7-final-debug.log`, `build-v7-legacy-final.log`, `build-v7-final-release.log` 및 `verification/v7-*`에 빌드·런타임·서명·소스 해시를 보관했습니다. 실제 YouTube 추출·갤럭시 화면 꺼짐·데이터 절감량은 실측하지 않았습니다. 초기 상태 조회 실패 로그도 보존했습니다.

네트워크 콜백은 [Android 공식 문서](https://developer.android.com/develop/connectivity/network-ops/reading-network-state)의 기본 네트워크·capability 처리 계약을 사용합니다. GitHub 원격 CI 실행 결과는 로컬 검증과 별도로 확인합니다.
