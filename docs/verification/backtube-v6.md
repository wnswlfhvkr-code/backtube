# backtube v6 검증

APK: `backtube-v6.apk`, `org.schabi.newpipe.personal`, `0.29.1-backtube.6` / 1020.
크기: 11,543,930바이트. SHA256: `D4AC8230D8DC72A43E16A99EC909EF627F39FC3C18B87B4A5FEC6CECA28E4670`.

## 적용

- 앱 이름을 backtube로 변경. 기존 패키지·개인 서명키를 유지해 업데이트 설치 가능.
- 재생 화면 ⋮의 데이터 절약 스위치, 오디오 품질 선택 및 상태 표시. 설정의 영상 및 오디오에서도 변경 가능.
- 절약 활성 시 별도 오디오만 선택. 오디오 소스가 없거나 일반 라이브 영상 매니페스트만 있으면 안내 후 건너뜀. 사용자가 영상을 직접 열면 영상 재생을 허용하며, 이후 백그라운드 전환에서는 다시 오디오 소스를 선택.
- 음질은 알려진 비트레이트를 우선하여 절약=최저, 균형=128 kbit/s에 가장 가까운 값(동률이면 낮은 값), 고음질=최고. 언어·오디오 트랙 우선순위 유지.
- 요금제 네트워크에서 절약 활성 시 저음질·작은 썸네일을 선택. 사용자의 고음질·이미지 끄기 설정 및 DB용 이미지 주소를 덮어쓰지 않음. 네트워크 변경은 다음 소스·이미지 선택에 적용.
- 현재 항목·위치·일시정지를 유지한 품질 변경. 기존 캐시 사용.

## 확인 결과

- Debug APK, Android 테스트 APK, Release APK 및 릴리스 필수 lint 빌드 통과. Checkstyle 위반 0, Kotlin lint 통과.
- 단위 테스트 62개, 실패·오류 0. 오디오 순위·알 수 없는 비트레이트·동률, 이미지 품질·NONE·DB 주소, 추천·제외·세션·타이머·대기열·홈 탭 회귀 포함.
- Android 15 전용 에뮬레이터: `OK (29 tests)`. 별도 프로세스 단계 실행용 테스트 하나는 기본 실행에서 제외되어 실제 실행 28개, 실패 0.
- 생성 WAV와 합성 메타데이터로 실제 MediaSourceManager/ExoPlayer 재생 및 다음곡·자연 종료·반복·타이머·음소거 회귀 확인.
- strict 소스 차단, 숨겨진 MAIN 경로, 영상 재표시 허용, 재로딩 판단을 확인. MAIN 일시정지에서 절약을 켜도 오디오 모드·1,200ms 위치·일시정지를 유지.
- 실제 ⋮ 메뉴에서 절약 토글 및 오디오 품질 대화상자에서 고음질 선택·저장 확인. 360×800dp 세로 / 800×360dp 가로 화면, 메뉴·대화상자 캡처 확인.
- 에뮬레이터 Wi-Fi를 요금제 네트워크로 지정한 추가 검사 1개와 해제 후 검사 1개 각각 통과. 실제 앱 컨텍스트로 저음질·작은 이미지 선택, 선호값·NONE·DB 주소 유지, 해제 후 복원 확인. 네트워크 설정은 원래 상태로 복원.
- 독립 검토에서 발견한 숨김 전환·일시정지·오류 안내 경로를 수정하고 재검토 통과.
- APK v1/v2/v3 서명 및 zipalign 통과. 인증서 SHA256은 기존 키와 같은 `78fa3e7d3dd06d25f32f42f0409c5fc9738448060f2cf4be7a6693ae37a5e5e4`.
- 기존 v5 / 1019에 삭제 없이 `install -r` 성공. 설치 버전 1020, debuggable 없음, MainActivity `Status: ok`. 릴리스 홈 렌더링 확인.

## 범위

실제 YouTube 링크·네트워크 스트림 자동 검사는 기존 실행 정책 차단으로 재시도하지 않았습니다. 생성 WAV 검사는 실제 YouTube 추출·데이터 절감량·갤럭시 화면 꺼짐 검증을 대신하지 않습니다. 실기기는 변경하지 않았습니다.

별도 캐시·다운로드 엔진을 추가하지 않았습니다. 단일 저장 이미지 주소는 더 작은 대체 이미지가 없으면 선택만으로 축소할 수 없습니다. 추천·세션 복원 범위는 v5와 같습니다.

## 증거 파일

- `build-v6-final.log`, `debug-v6-regression.log`
- `verification/v6-unit-test-summary.json`, `v6-runtime-final.log`
- `verification/v6-metered-on.log`, `v6-metered-off.log`
- `verification/v6-apk-signature.txt`, `v6-zipalign.txt`, `v6-apk-badging.txt`
- `verification/v6-before-update.txt`, `v6-install.txt`, `v6-installed-package.txt`, `v6-launch.txt`
- `verification/v6-ui/`, `v6-home-release.xml`, `v6-source-manifest.json`
