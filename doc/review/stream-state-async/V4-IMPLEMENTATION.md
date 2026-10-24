# v4 검토 및 실제 프로젝트 구현

## 변경 범위

제품 Java 두 파일의 history progress 비동기화 및 수명 관리만 변경합니다.
후보의 문자열/token 상태모델 8개는 Android 검증으로 집계하지 않았습니다.
전체 Java/Kotlin 663개가 심층 검수됐다는 주장은 하지 않습니다.

## 후보 보완

- 같은 manager라도 updateState는 최신 history를 다시 읽도록 기존 요청을 취소·교체.
- generation, request 객체, serviceId+URL, manager, 실제 attach 상태로 현재 요청 확인.
- 콜백은 현재 bound item의 duration을 사용하며 잘못된 서비스/이전 바인딩은 무시.
- bind 전후 adapter attach 중복 요청 방지; 분리 중 업데이트는 조회를 보류하고 재부착 때 최신 항목 사용.
- 실제 RecyclerView 전체가 창에서 제거되면 adapter row callback만으로 취소되지 않는 경우를
  실제 Android에서 재현. itemView의 실제 attach/detach 리스너도 연결해 해결.
- ViewUtils property animator의 listener를 제거하고 cancel; 별도의 progress Animation도 취소.
  재설정 시 alpha 1 / GONE / progress 0. 동일 행 정상 업데이트는 이전 표시를 결과까지 유지.
- 양수 duration LIVE에서 기존 full bind와 updateState의 표시 규칙이 다름을 유지.
  재부착의 무애니메이션 표시가 마지막 updateState 자격을 뒤집지 않도록 논리 모드와 애니메이션 분리.

## RED → GREEN 실제 근거

- 기존 1035 소스에서 결과를 보류한 DB Single 때문에 main의 bind가 반환하지 않아 RED.
- 첫 비동기 보완본은 Android 10/11 PASS, 실제 창 detach 후 observer 잔존 1 FAIL.
- 실제 View 수명 리스너 후 해당 창 분리 검사 PASS.
- 추가 legacy LIVE 재부착 검사에서 불필요한 재조회(expected 1 / actual 2) RED.
- 마지막 표시 규칙을 보존한 최종 보완본: Android 23 실제 View/Rx/RecyclerView 11/11 PASS.
- 최종 제품 코드로 JVM 앱 560 + 공유 14 PASS, Checkstyle PASS, lint 오류 0 / 경고 346.
- 독립 소스 검토에서 추가 차단 사유 없음.

테스트는 bind 반환, 이미 큐에 올라간 결과, 서비스 identity, 최신 duration,
같은 manager의 연속 갱신, 실제 RecyclerView 재활용과 창 분리/재부착,
진행 애니메이션, 빈 결과·오류·설정 OFF, duration/live 표시 규칙을 검증합니다.
ViewRoot와 실제 Android UI thread를 사용하며 Room 읽기 결과만 제어된 Single로 대체합니다.

## 검증 실행 방법

JDK 21과 프로젝트 Android SDK 구성이 필요합니다.

```sh
./gradlew :app:testDebugUnitTest :shared:jvmTest :app:lintDebug :app:runCheckstyle
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.schabi.newpipe.info_list.holder.StreamProgressBindingTest
```

실제 시행은 cloud API23, 네트워크 OFF, 원본 Gradle wrapper를 사용했습니다.
성능 수치·실기기·API35·전체 Android suite 검증은 하지 않았습니다.
이 시점에는 관련 재생/저장 회귀와 APK 제작이 진행 예정입니다. 이전 1035 결과를
새 제품 코드의 테스트 결과로 재사용하지 않습니다.
