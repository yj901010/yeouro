# 여유로 Native Android — 첫 수직 기능

두 앱 모두 **Kotlin + Jetpack Compose**입니다. WebView나 `prototype/` 웹 번들은 넣지 않습니다.

- `vehicle-app`: 차량용 큰 글씨·큰 터치 영역, 정차/주행 DEMO 잠금, 이동 생성/공유/제안 확인.
- `phone-app`: 이동별 **운전자 / 동행자** 역할. 초대 코드의 역할을 서버가 부여하며 휴대폰이 임의로 권한을 고르지 않습니다. 휴대폰에서 새 이동을 만들면 이동 관리자(`OWNER`)입니다.
- `core-model`: 직렬화 DTO/상태.
- `core-domain`: 세션/공유/제안 상태 처리, 권한 검사, 차량 게이트웨이 경계, 순수 JVM 테스트.
- `core-network`: 실제 HTTP, 취소 가능한 요청, 토큰 헤더, 계약 테스트.
- `core-ui`: 생명주기 연동, ViewModel, 시스템 밝음/어두움 테마, 공통 Compose 화면.

## 빌드

고정 버전: Gradle **8.11.1**, Android Gradle Plugin **8.9.2**, Kotlin/Compose compiler **2.1.20**, Compose BOM **2025.04.01**, compile/target SDK **35**, min SDK **28**. JDK 17 이상과 SDK Platform 35 / Build-Tools 35.0.0이 필요합니다. Gradle wrapper 배포 SHA-256을 고정했습니다. 근거·다운로드 검증은 [PROVENANCE.md](gradle/wrapper/PROVENANCE.md).

```sh
cd apps/android
# Android SDK 없이도 계약, HTTP, 상태/안전 경계 테스트 가능
./gradlew -PjvmOnly=true :core-domain:test :core-network:test

# SDK 설치/약관 동의는 실행 환경 소유자가 직접 승인해야 합니다.
# local.properties의 sdk.dir 또는 ANDROID_HOME을 설정합니다.
./gradlew :vehicle-app:assembleDebug :phone-app:assembleDebug
./gradlew :vehicle-app:lintDebug :phone-app:lintDebug
```

출력: `<module>/build/outputs/apk/debug/<module>-debug.apk`. 두 앱의 applicationId가 달라 같은 Android 에뮬레이터에 함께 설치할 수 있습니다. 실제 차/휴대폰 실행 검증과 APK 컴파일은 별도입니다.

이 저장소를 만든 임시 클라우드 환경에서는 쓰기 가능한 캐시/프록시를 위한 `python3 tools/gradle-env.py <Gradle 인자>`가 있습니다. 일반 개발 환경에서는 표준 `./gradlew`를 쓰면 됩니다. 도구는 공급된 환경 프록시만 사용하며 주소/인증 정보를 저장하지 않습니다.

기본 빌드는 작은 개발 환경에서도 재현되도록 1GB heap / worker 1 / 직렬 Kotlin 컴파일을 씁니다. 충분한 메모리의 개발 PC에서는 Gradle 속성으로 병렬도/heap을 올릴 수 있습니다.

## 로컬 백엔드에 연결

1. 저장소 `services/backend/README.md`에 따라 서버를 **호스트의 8080 포트**에 실행합니다.
2. Android 에뮬레이터의 debug 앱은 `http://10.0.2.2:8080/`에 연결합니다. 계약 경로는 `/api/v1/…`입니다. 물리 휴대폰의 `10.0.2.2`는 개발 PC가 아닙니다.
3. vehicle 앱 → **명시적으로 DEMO 테스트 켜기** → 목적지 이름 → **이번 이동 만들기**.
4. 이번 이동 공유에 체크 → `동행자` 또는 `운전자 휴대폰` 선택 → 코드 생성. 초대 코드는 15분·1회용입니다.
5. phone 앱에서 코드·표시 이름 입력 → 이번 이동 공유에 명시적으로 동의 → 연결.
6. 동행자는 **준비 완료** 또는 **장소 제안**. 차량/운전자는 정차 DEMO에서 **확인 · DEMO 적용**. 서버에서 `PENDING → APPLY_REQUESTED → DEMO_APPLIED`가 구분됩니다.
7. **SIMULATED 위치 한 번 전송**은 사용자가 누를 때만 합성 좌표 1개를 서버에 보냅니다. 실제 위치 권한/센서/백그라운드 전송은 없습니다.
8. **공유 끄기**는 참여자 토큰·초대를 철회합니다. 참여자 앱은 다음 서버 확인에서 401을 받으면 위치·참여자·세션 캐시도 즉시 비웁니다. **이동 끝내기**는 서버 이동을 종료합니다.

둘 이상의 동행자를 함께 테스트하려면 별도 앱 인스턴스/에뮬레이터가 필요합니다. 한 phone 앱에서 역할을 바꾸려면 연결 정보를 지운 뒤 새 역할의 초대로 들어갑니다.

## 연결·수명주기·실패

- 화면이 `STARTED` 이상일 때만 5초 foreground polling. 백그라운드에서 조회 루프 중단 및 진행 중 조회 HTTP 취소. 푸시/포그라운드 서비스/SSE는 첫 단계에 없습니다.
- 연결 확인·최종 수신 시각·오류·재시도·요청 대기 취소가 표시됩니다. 잠금 중에는 상세 조작을 숨깁니다.
- **재시도/새로고침은 읽기만** 수행합니다. 이동 생성/초대 참여 등 변경 요청을 자동 재전송하지 않습니다. OkHttp 연결 복구 자동 재시도와 리다이렉트도 껐습니다.
- 요청 대기 취소는 서버에 이미 도달한 변경을 되돌리지 않습니다. 새로고침으로 결과를 확인합니다. 생성/참여 응답을 잃으면 자격증명을 복구하지 못할 수 있습니다. 계정/안전한 복구 기능은 후속 단계입니다.
- 토큰은 `Authorization: Bearer` 헤더로만 전송하며 화면·로그·URL·SavedState·백업에 저장하지 않습니다. 첫 단계는 **메모리 전용**이므로 프로세스 종료 후 다시 연결해야 합니다. 화면 회전은 ViewModel이 유지합니다.
- 네트워크 일시 오류는 마지막 수신 정보임을 명시합니다. 확인된 401/만료는 공유 데이터 캐시를 유지하지 않습니다.
- 서버는 로컬 MVP입니다. 인터넷 공개 전 계정/장치 신뢰, rate limit, 운영 저장소, TLS, 안전한 세션 복구 검토가 필요합니다.

## 실제 차량과 DEMO의 경계

`UnavailablePleosGateway`는 기본값이며 `UNKNOWN` 차량 상태로 조작을 잠그고 실제 적용 요청에 `FAILED/SDK_NOT_CONNECTED`를 반환합니다. `DemoVehicleGateway`는 **사용자의 명시적인 DEMO 선택 후**에만 생성됩니다. `DEMO_APPLIED`는 실제 차량/내비 경로 성공이 아닙니다. `APPLY_REQUESTED` 역시 승인만 뜻하며 적용 완료가 아닙니다.

DEMO 주행/정차 전환 버튼은 별도 테스트 도구입니다. 실차 속도/기어/UX 제한 API가 아니며 Pleos 적합성·차량 탑재·심사 통과를 주장하지 않습니다. 실제 SDK 연동 전까지 위치, 차량 제어, 알림, 마이크, 계정 권한은 요청하지 않습니다. 직접 선언한 플랫폼 권한은 일반 네트워크 `INTERNET`뿐입니다. AndroidX가 생성하는 앱 내부 signature 권한은 런타임 위치/차량 권한이 아닙니다.

## Release 구성

```sh
./gradlew :vehicle-app:assembleRelease :phone-app:assembleRelease \
  -PYEOURO_API_BASE_URL=https://your-validated-backend.example/
```

Release는 HTTPS 주소 설정 없이는 빌드하지 않습니다. 개발용 cleartext 허용은 debug Manifest의 정확한 `10.0.2.2` 도메인에만 있습니다. Release 서명키·배포 설정은 포함하지 않습니다. 비밀/토큰을 URL이나 Gradle 속성에 넣지 마세요.

## 실제 백엔드와 클라이언트 계약 재검증

로컬 테스트 서버를 먼저 실행한 같은 네트워크 환경에서:

```sh
YEOURO_TEST_BASE_URL=http://127.0.0.1:8080/ \
  ./gradlew -PjvmOnly=true :core-network:test --rerun-tasks
```

`LiveBackendTest`는 위 환경 변수 없이는 건너뜁니다. URL은 localhost/127.0.0.1만 허용하며, 테스트 이동을 생성·종료하고 실제 외부 서버를 호출하지 않습니다. Kotlin 앱이 쓰는 동일한 HTTP 클라이언트로 역할별 초대·참여·준비·제안·DEMO 적용·위치·철회를 검사합니다.

## 검증 범위

자동화: 역할/동의 검사, 중복 클릭 단일 처리, 버전 역행 방지, 주행 잠금, DEMO/실제 적용 분리, 명시적 위치 전송, 생명주기 polling 취소, 세션 삭제/철회, HTTP DTO/인증/오류/취소/재전송 방지. 기기/에뮬레이터 실제 Compose 상호작용, 접근성 및 Pleos 실기 동작은 별도 QA가 필요합니다.

## Additive v2 phone API adapter

`core-network/kr.yeouro.network.v2` contains separate v2 wire models and `V2PhoneRepository` for account-authenticated trip creation/read/plan replacement, sharing/invite/join, readiness, proposal discovery/decision and lifecycle requests. A `PhoneAccessTokenProvider` supplies a current access JWT; this code does not create an account, persist secrets, implement a Supabase login UI or invent a development token.

The existing phone and vehicle screens remain on the v1 capability-session repository. Do not swap repositories without implementing sign-in, restoring memberships, role/consent projections, cancellation, cache clearing on 401/403 and lifecycle behavior. The v2 join response is a membership, not a v1 SessionResponse or a newly issued phone token. Vehicle pairing is a separate credential flow, not implemented by this phone adapter.

The v2 transport refuses unsafe release endpoints, does not follow redirects or automatically retry mutations, carries explicit idempotency keys and plan ETags, and propagates auth/conflict errors. Nine focused MockWebServer tests validate the boundary. They do not establish a working login screen, a native vehicle UI, or live Supabase/Pleos integration.

The opt-in `LiveV2BackendTest` is a separate real Kotlin→HTTP→PostgreSQL check with temporary signed JWTs. From the repository root after packaging the backend, run `PG_BIN=/path/to/postgresql/bin scripts/run-postgres-tests.sh -- python3 /absolute/path/to/yeouro/scripts/smoke-native-v2.py`. The Python harness requires `cryptography`, starts only loopback test servers and an in-memory signing-key fixture, and runs both v1/v2 native tests. It never connects to a Supabase account and does not install or launch an APK.
