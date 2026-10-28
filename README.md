# 여유로 (가칭)

차량 앱과 운전자·동행자 공용 Android 앱을 연결하는 공모전 MVP입니다.

## 구성

| 경로 | 역할 |
| --- | --- |
| `apps/android/vehicle-app` | 차량용 Kotlin/Compose 앱 |
| `apps/android/phone-app` | 운전자·동행자 공용 Android 앱 |
| `apps/android/core-*` | 공통 모델·계약·통신·도메인·UI 모듈 |
| `services/backend` | Java 21 / Spring Boot 모듈형 단일 서버 |
| `contracts/openapi.yaml` | 서버·앱 API 규격 |
| `prototype` | 기존 브라우저 UI 검토용 프로토타입 (제품 웹 앱 아님) |
| `docs/ARCHITECTURE.md` | 구조와 기능 경계 |
| `scripts/smoke-local.py` | 로컬 HTTP 연동 점검 |

## 첫 구현 범위

이동 생성, 역할별 초대·참여, 준비 상태, 장소 제안과 운전자 승인, 내비 적용 결과의 구분, 명시적인 가상 위치 전송, 공유 철회·이동 종료를 연결합니다.

- 앱은 실제 서버 API를 사용합니다. 서버가 없으면 오류를 표시하며 모의 서버 데이터로 몰래 전환하지 않습니다.
- 차량 SDK는 아직 연결하지 않았습니다. DEMO 처리와 실제 적용 성공은 구분합니다.
- 위치·내비·공조·음성·외부 지도·푸시가 실제로 연결되었다고 해석하면 안 됩니다.
- 스마트폰 MVP는 Android 전용입니다. iOS·웹 제품은 이번 범위에 없습니다.
- 최초 연결은 앱이 화면에 있는 동안의 HTTP polling입니다. SSE/푸시는 후속 범위입니다.

## 로컬 서버

Java 21이 필요합니다. 상세 설정과 프로필은 `services/backend/README.md`를 확인하세요.

```sh
cd services/backend
sh ./mvnw test
sh ./mvnw spring-boot:run
```

다른 터미널에서 저장소 루트를 기준으로:

```sh
python3 scripts/smoke-local.py
```

스크립트는 loopback 주소만 대상으로 하고 가상의 이동 데이터를 생성합니다. 인증 토큰과 초대 코드는 출력하지 않습니다.

## Android

`apps/android`를 Android Studio에서 여세요. 필요한 SDK 및 빌드 설정은 해당 디렉터리 문서를 확인하세요. Google Android SDK 약관은 개발 환경에서 적법하게 동의해야 합니다.

```sh
cd apps/android
sh ./gradlew -PjvmOnly=true test
sh ./gradlew :vehicle-app:assembleDebug :phone-app:assembleDebug
```

에뮬레이터의 개발용 서버 주소는 호스트의 loopback과 다릅니다. 로컬 호스트 서버를 사용하는 에뮬레이터는 통상 `10.0.2.2`로 접근합니다. 실제 폰은 별도 접근 가능한 HTTPS 서버 또는 승인된 개발 연결을 설정해야 합니다. 휴대폰에 APK를 설치하는 것만으로 클라우드 작업 환경의 로컬 서버에 연결되지 않습니다.

## UI 프로토타입

```sh
cd prototype
npm test
python3 -m http.server 8081 --directory dist
```

기존 프로토타입은 디자인·흐름 참고용으로 보존합니다. 이 코드를 Android WebView로 감싼 제품이 아닙니다.

## 보안 및 배포 경계

서버의 최초 capability-token 세션은 로컬 개발용입니다. 사용자 계정 인증, 인터넷 공개 운영, 장기 세션 보관, 운영 제한 정책을 완성한 서비스가 아닙니다. API 키·서명키·토큰·실제 위치 기록·개인정보는 저장소에 넣지 마세요. 디버그 앱과 개발 서버를 실제 차량 제어나 공개 서비스에 사용하지 마세요.

## 검증

최신 실제 실행 결과와 미검증 항목은 `docs/VERIFICATION.md`에 기록합니다. 코드 작성, 컴파일, APK 생성, 에뮬레이터 실행, 실차 검증은 각각 다른 단계입니다.
