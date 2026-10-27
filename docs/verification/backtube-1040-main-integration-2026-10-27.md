# Backtube 개인용 1040 소스 통합

2026-10-27. 이 문서는 개인용 정식 APK의 제품 소스를 main에 반영할 때의 빌드 식별과 검증 경계를 기록합니다. 공개 GitHub 릴리스 게시를 뜻하지 않습니다.

## 제품 소스와 빌드

- 제품 소스: `a706d20d406216cbe23d2bf17716d5bfef5c09e2`. 이후 `574b69267cc47c99a8ce3a939b1116b490eeaf87`까지 변경된 파일은 검증 문서·근거뿐이며 앱 제품 소스는 같습니다.
- JDK 21·Android SDK에서 `./gradlew :app:assembleRelease '-DpackageSuffix=.personal' '-DversionCodeOverride=1040' '-DskipFormatKtlint' '-Pandroid.overridePathCheck=true' --console=plain`으로 서명 전 입력물을 만들 수 있습니다. 이 명령은 키를 다루지 않으며 설치 가능한 APK를 만들지 않습니다.
- 별도 보존된 기존 서명 경로에서 만든 개인용 APK `backtube-v1040-personal-release.apk`의 SHA-256은 `176a119654b79f9498c002cd25589fa2daf71a1eb814d0b48b11d8673f32a76e`, 크기는 11,511,507바이트입니다. 이 APK를 문서 이후 커밋에서 다시 빌드했다고 주장하지 않습니다.
- 실제 APK 메타데이터는 패키지 `org.schabi.newpipe.personal`, 버전코드 `1040`, 버전 이름 `0.29.1`, min SDK 23, target SDK 35, debuggable 꺼짐입니다. v9는 같은 패키지의 버전코드 1023입니다.
- 실제 APK의 인증서 SHA-256은 v9와 같은 `78fa3e7d3dd06d25f32f42f0409c5fc9738448060f2cf4be7a6693ae37a5e5e4`입니다. v1·v2·v3 서명 검증, 16 KiB ZIP 정렬, v9와 동일한 선언 권한 11개를 확인했습니다. APK 바이너리와 서명 키·암호는 Git에 넣지 않습니다.

## 검증과 한계

- 전달된 Windows 검증 기록은 v9→1040 설치, UID·설치 시각·Dark 설정 보존, 실행을 통과로 보고했습니다. Windows JVM은 682/683 통과였고 1건은 `setReadable(false)` 테스트 준비 실패입니다. 전체 미디어 DB 이전은 검증되지 않았습니다.
- 동일 제품 소스의 Linux JVM 결과는 683/683 통과이며, API 23 합성 네트워크·메뉴 관련 계측 9개가 통과했습니다. 실제 공급자 다운로드, 휴대폰의 네트워크 전환, 전체 미디어 DB 이전은 이 결과로 입증되지 않습니다.
- 전달된 1039 테스트 APK는 다른 패키지·디버그 인증서를 사용하므로 개인용 v9/1040과 자동으로 앱 데이터가 이전되지 않습니다.
- 앱 내 업데이트 조회는 NewPipe의 별도 릴리스 인증서를 확인하고 NewPipe 데이터 API를 사용합니다. 개인용 서명 APK가 GitHub Backtube 릴리스를 자동 조회한다고 주장하지 않습니다.
- 기존 `v0.29.1-backtube.1039` 태그와 미공개 릴리스 초안은 테스트 APK용 기록으로 유지합니다. 이 통합 작업은 새 공개 릴리스나 APK 업로드를 수행하지 않습니다.
