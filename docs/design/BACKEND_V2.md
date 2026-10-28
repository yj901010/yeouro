# 여유로 백엔드 DB 및 API 설계안

작성일: 2026-10-28 · 설계 버전: 0.2 초안
기준 코드: yj901010/yeouro, 088ee7ab3456991ba40240998c0c62f3c36867d7 (Draft PR #2)

## 검토 목적과 결론

차량 앱과 운전자·동행자가 함께 쓰는 Android 스마트폰 앱을 하나의 Spring Boot 서버로 연결한다. DB는 Supabase의 PostgreSQL을 제안한다. 제품용 웹앱은 만들지 않는다. 브라우저 UI 프로토타입은 디자인 참고 자료로만 유지한다.

현재 코드는 로컬 테스트용 첫 연동 흐름이다. 이 문서는 다음 개발을 위한 목표 설계이며, 여기에 적힌 테이블·API·Supabase Auth·차량 페어링은 구현 완료를 의미하지 않는다. 기존 `/api/v1` 계약은 유지하고 새 인증·권한 모델은 `/api/v2` 제안으로 분리한다. 사용자의 검토 전에 배포, 계정 생성, DB 변경 또는 기존 APK의 계약 변경을 하지 않는다.

### 서버 구성

- Spring Boot 모듈형 모놀리스 1개: 도메인별 패키지와 서비스 경계를 나누되 한 번에 배포한다.
- PostgreSQL 1개: Supabase에서 관리하고 Spring Boot만 업무 테이블에 접근한다.
- Android 앱: HTTPS API로 통신한다. PostgreSQL 비밀번호·관리자 키를 앱에 넣지 않는다.
- Supabase Auth: 재설치·재로그인 후 사용자 식별을 복구하기 위한 제안이다. 로그인 방식은 아직 결정하지 않았다. Supabase를 DB로 고른 것만으로 Auth까지 사용하기로 확정한 것은 아니다.
- 차량 앱: 폰에서 승인한 이번 이동 전용 단기 세션을 사용한다. 차량에 사용자의 장기 로그인 자격증명을 남기지 않는다.
- Render 무료 배포는 초기 검증 후보다. 무료 서버의 절전 때문에 정확한 서버 예약 실행, 항상 연결된 소켓, 즉시 푸시를 전제로 삼지 않는다.
- Redis, Kafka, 마이크로서비스, Kubernetes는 이 MVP에 도입하지 않는다.

### 도메인별 책임

| 모듈 | 책임 | 다른 모듈에 넘길 책임 |
|---|---|---|
| identity | 사용자 식별과 인증 검증 | 여행별 행동 권한은 trip/access |
| trip | 목적지, 약속 시간, 도착 기준, 경유·체류 계획과 진행 상태 | 외부 경로 계산은 integration |
| sharing | 참여, 초대, 공유 동의, 철회 | 차량 하드웨어 제어 금지 |
| location | 위치 출처·관측 시각·최신성·공유 범위 | 위치를 임의로 차량 GPS라고 간주하지 않음 |
| proposal/navigation | 장소 제안, 운전자 승인, 차량 명령과 결과 | 실제 적용 여부는 SDK 근거 필요 |
| planning | 주차·도보·차량 도착 예상과 여유 계산 | 제공자별 호출·제약은 integration |
| notification | 복귀 예정 시각과 알림 계획 | OS 표시 및 실제 전달 보장은 별도 |
| integration | Pleos/지도/경로/주차 제공자 어댑터 경계 | SDK는 차량 앱에서 호출; 서버가 직접 차량 SDK를 실행하지 않음 |

모듈 간 호출은 공개된 서비스 인터페이스를 통해서만 한다. 다른 모듈의 JPA 엔티티·Repository를 여러 곳에서 직접 조작하지 않는다. Controller는 요청 검증과 응답 변환, Application Service는 권한·트랜잭션·유스케이스 조정, Domain은 순수 상태 전이와 계산, Infrastructure는 DB·외부 API를 맡는다. 함수는 초대 생성, 초대 수락, 준비 상태 변경, 제안 승인처럼 사용자 의도 단위로 나눈다.

## 구현 순서와 범위

1. DB·API 설계 확정: 사용자 식별 방식, owner와 driver 권한, 위치 동의·보관, 상태 전이, 에러 계약을 고정한다.
2. 공유 기반 구현: PostgreSQL 마이그레이션, 인증/페어링, 여행·초대·참여·철회와 권한 테스트.
3. 실제 사용 흐름 구현: 합의한 UI와 함께 도착 마감·체류·주차 비교·준비 상태·제안을 연결한다. 서버 기능만 만들어 화면 검수를 뒤로 미루지 않는다.
4. 차량 연동 검증: Pleos 에뮬레이터에서 기능 지원·권한·주행 제한과 내비 적용 결과를 확인한다. 모의 데이터와 실제 결과를 분리한다.
5. 알림과 안정화: 폰의 로컬 알림 우선, 서버 복구·중복·만료·오프라인 테스트. 무료 환경에서 푸시 전달을 보장하지 않는다.
6. 배포 검증: PostgreSQL 및 HTTPS 운영 설정을 확인하고 두 앱을 실제로 실행한 뒤 배포용 APK를 만든다.

자동 장소 학습, 길을 헤맨 원인 분류, 다음 시동 시 공조 복원은 이후 단계다. 이번 설계에는 확장 경계만 두며 SDK 권한이나 학습 성능을 확보했다고 주장하지 않는다. 사용자가 장소 팁을 반복 수동 입력해야 하는 구조를 핵심 흐름으로 만들지 않는다.

## 공통 운영 원칙

- 사용자 소유권, 이번 여행의 운전 권한, 위치 공유 동의, 차량 연결 권한은 서로 다른 개념이다.
- 서버는 권한과 업무 상태의 기준이다. UI에서 버튼을 숨기는 것만으로 접근을 제한하지 않는다.
- 위치는 기본적으로 최신값만 저장한다. 초단위 이동 궤적의 영구 누적을 MVP 기본값으로 하지 않는다.
- 여행 계획의 planRevision과 위치 관측 순서는 분리해 빈번한 위치 업데이트 때문에 장소 승인 충돌이 계속 생기지 않게 한다.
- HTTP 재시도와 차량 SDK 명령 재실행은 다르다. 중복 API는 멱등 처리하되, 결과가 불명확한 차량 명령을 자동 재실행하지 않는다.
- 공유 철회는 즉시 서버 접근을 차단한다. 이미 수신된 화면·기억·스크린샷을 원격 삭제할 수 있다고 약속하지 않는다.
- 타임스탬프는 UTC로 저장·전송하고 여행의 IANA 시간대를 별도로 보관한다. UI가 사용자 시간대로 표시한다.
- 비용·ETA·빈자리에는 출처와 기준 시각을 둔다. 확인하지 못한 값은 null/UNKNOWN이며 0원·0분·빈자리 있음으로 대체하지 않는다.


# DB 상세 설계

작성: 2026-10-28 · 상태: 제안, 미구현 · 대상: Kotlin 차량 앱 + 운전자/동승자 공용 Kotlin 폰 앱 + Spring Boot 모듈러 모놀리스 + Supabase PostgreSQL

## 1. 결정 요약

- 앱은 Spring HTTPS API만 호출한다. 폰 앱 로그인만 Supabase Auth를 이용하는 방안을 제안한다. 도메인 DB 직접 접근/Realtime 구독은 하지 않는다.
- `Trip`은 일정과 공유의 중심이다. **사람의 목적지 도착 마감**, **계획 체류시간**, **차량 도착 예상**, **주차 후 도보 포함 사람 도착 예상**을 구분한다.
- `OWNER`는 일정 관리 권한, `DRIVER/COMPANION`은 이번 이동의 역할이다. OWNER도 DRIVER 또는 COMPANION일 수 있다.
- 장소 제안 승인과 차량 내비 적용 결과를 분리한다. `ACCEPTED`는 `APPLIED`가 아니다.
- 원시 위치 경로는 저장하지 않고 trip별 최신 한 점만 저장한다. 철회/종료 시 즉시 조회 차단·삭제하며 이전 공유 세션을 재사용하지 않는다.
- 계정 복구는 폰 앱의 지속 사용자 ID에 기반한다. 차량 앱에는 계정 refresh token을 넘기지 않고 trip 범위의 짧은 credential만 발급한다.
- Redis/Kafka/이벤트소싱/마이크로서비스/PostGIS를 첫 단계에 도입하지 않는다. 트랜잭션, PostgreSQL 인덱스, Spring 스케줄러로 비시간임계 정리 작업을 시작한다. sleep 가능한 무료 서버의 정확한 시각 알림은 보장하지 않으며 로컬 알림을 우선한다.

## 2. 현행과 구현 단계

현행 증거: `docs/ARCHITECTURE.md`, `contracts/openapi.yaml` v0.1.0, `services/backend/src/main/resources/db/migration/V1__trip_sharing.sql`.

**이미 있는 범위:** trips/participants/access_tokens/invitations/proposals 5개 테이블, 24시간 세션, 15분 일회 초대, capability bearer, 준비 상태, 장소 제안, 데모 내비 결과, 최신 위치, 공유 철회, 종료. 현행은 폰 영구 계정이나 Supabase Auth 연동이 아니다. 내비 SDK 미연결이고 real APPLIED는 허용하지 않는다. foreground polling이 기준이다.

**다음 첫 구현 묶음(A):** 계정 기반 복구, trip/참여자/초대/동의/최신 위치/제안/명령의 안전한 기본 모델 + 마감·체류 필드. 차량 pairing은 차량 연결 묶음(A2)으로 먼저 별도 구현 가능. 기존 데모는 유지하되 실 SDK 결과와 혼동하지 않는다.

**전체 기획에 필요하지만 후속(B):** 주차 후보와 경로/ETA 스냅샷, 실제 주차·도착 확인, 귀환/출발 알림 계획 및 push delivery. 외부 공급자 연결 전에는 unavailable 또는 명시적 SIMULATED로 표시한다.

**검증 뒤 확장(C):** 장소별 체류/주차 경험 학습. 현재 기능이나 정확도 보장으로 홍보하지 않는다. 별도 opt-in과 데이터 정책이 확정되기 전 학습 데이터를 수집하지 않는다.

## 3. 공통 규칙

모든 도메인 테이블은 비노출 `app` schema 권장. UUID PK, 서버 생성 `created_at timestamptz`, 변경 엔티티에는 `updated_at timestamptz`, `version bigint default 0 check(version>=0)`. 아래 표는 공통 필드를 생략한다. nullable은 `?`로 표시한다. FK 대상/UNIQUE/CHECK는 실제 migration에서 명시한다. 상태값은 PostgreSQL enum보다 `text + CHECK`로 시작해 변경 비용을 줄인다.

시간은 UTC instant 저장, 사용자 표시/알림 해석에는 `time_zone text`(예: Asia/Seoul)를 사용한다. duration은 integer 초/분이고 서로 혼용하지 않는다. 좌표는 double precision, 위도 [-90,90], 경도 [-180,180], 유한값 검사, 두 좌표 모두 null 또는 모두 non-null. 금액은 정수 최소 화폐단위 + currency CHAR(3); 부동소수 비용 금지.

동일 trip 안의 FK를 강제하려면 참조 테이블에 `UNIQUE(trip_id,id)`를 두고 `(trip_id, participant_id)` 같은 composite FK를 쓴다. id만 FK로 두면 다른 일정의 참여자/제안을 잘못 연결할 수 있다. 상태 전이·현재 시간·역할 권한처럼 타 행/시간에 의존하는 규칙은 단순 CHECK 대신 서비스 트랜잭션에서 검증한다.

## 4. A: 계정·일정·공유 테이블

### `app_users`

| 컬럼 | 타입/의미 |
|---|---|
| id | uuid PK; Supabase `auth.users(id)` FK, 삭제 RESTRICT |
| display_name | varchar(40), 비공백 |
| status | ACTIVE / DELETION_PENDING |
| default_time_zone | text, 기본 Asia/Seoul |

Supabase Auth는 **제안된 폰 앱 인증/복구 선택**이다. 로그인 제공자(이메일 OTP/소셜/전화번호 OTP)는 아직 제품 결정이 아니다. 전화번호 자체를 도메인 PK로 쓰거나 SMS 비용·복구가 해결됐다고 가정하지 않는다. 첫 외부 시연 전 하나의 지속 로그인 수단을 선택하고 재설치/재로그인 시 같은 `auth.users.id`로 trip 목록을 복구한다. 익명 계정/일회 bearer만으로는 기기 유실 복구를 보장할 수 없다. 이메일·전화번호·refresh token은 app schema에 복제하지 않는다.

삭제는 Auth 행을 먼저 cascade 삭제하지 않는다. 사용자 삭제 워크플로가 소유 일정 종료/공유 철회/민감 데이터 삭제 및 참여자 정리 → app_users 삭제 → Auth Admin 삭제 순으로 수행하고 실패를 재시도한다. 시연 첫 버전은 소유권 이전보다 소유 일정 종료가 단순하다.

### `trips`

| 컬럼 | 타입/의미 |
|---|---|
| id | uuid PK |
| owner_user_id | uuid FK app_users, 일정 관리자 |
| title | varchar(120)? |
| destination_name / destination_latitude / destination_longitude | 장소 스냅샷; 이름 필수, 좌표 쌍 nullable |
| destination_provider / destination_external_id | text?, 외부 장소 식별자 쌍 |
| deadline_at / arrival_basis | timestamptz? / PERSON 또는 VEHICLE; 도착 마감과 도착 기준(기본 PERSON) |
| planned_stay_minutes | integer?, 1..10080 제안, 계획 체류시간 |
| time_zone | text, IANA timezone 서버 검증 |
| status | PLANNED / ACTIVE / ENDED / CANCELLED / EXPIRED |
| phase | DRIVING / PARKED / WALKING / AT_DESTINATION / RETURNING, nullable(출발 전) |
| starts_at / started_at / ended_at | timestamptz? |
| expires_at | timestamptz, 일정 접근 만료; 예약·체류를 포함하는 유한 TTL 정책은 제품 결정 필요 |
| revision / plan_revision | bigint>=0; 전체 snapshot 순서 / 계획 변경만의 충돌·ETA 기준 |

`deadline_at`은 expires_at이 아니다. PERSON은 주차/도보 후 사람 도착, VEHICLE은 차량 도착이며 계산식과 UI에 기준을 함께 표시한다. API 이름은 deadlineAt/plannedStayMinutes/timeZone/arrivalBasis로 매핑한다. planned_stay_minutes는 내비 주행시간이 아니다. 늦은 일정도 만들 수 있으므로 deadline > now 강제 CHECK는 하지 않는다. 상태가 ACTIVE이고 시간이 만료되면 API가 즉시 만료 취급한다(청소 스케줄러 지연 무관). 현행 생성+24시간 TTL을 예약 운행에 그대로 적용하지 않는다. v2는 계획 시작/체류 종료를 포괄하는 expires_at과 최대 상한을 정해야 하며, 10080분 등의 범위도 확정값이 아닌 검토값이다. 첫 화면에서 마감 없는 일반 일정도 허용할지 제품적으로 선택 가능하며, null을 0으로 대체하지 않는다.

인덱스: `(owner_user_id, created_at DESC)`, `(expires_at) WHERE status IN ('PLANNED','ACTIVE')`.

### `trip_participants`

`id uuid PK`, `trip_id FK`, `user_id FK app_users`, `role DRIVER|COMPANION`, `status ACTIVE|LEFT|REMOVED`, `display_name varchar(40)`(이번 일정 표시명), `ready boolean default false`, `ready_updated_at timestamptz?`, `joined_at`, `left_at?`.

제약: `UNIQUE(trip_id,user_id)`; `UNIQUE(trip_id,id)`; **partial UNIQUE(trip_id) WHERE role='DRIVER' AND status='ACTIVE'**. owner도 반드시 참여자 행을 가진다. trip 생성과 owner 참여자 생성은 한 트랜잭션. owner는 초대/종료/일정 변경 권한, active driver는 주행 관련 결정/위치 공급 권한을 갖는다. 운전자 교체는 trip 잠금 아래 기존 driver 해제·새 driver 지정·기존 pairing 폐기. 단순 역할 문자열 OWNER로 driver를 대체하지 않는다.

인덱스 `(user_id, status, joined_at DESC)`, `(trip_id,status)`.

### `sharing_sessions`

`id uuid PK`, `trip_id FK`, `generation bigint>0`, `status ACTIVE|REVOKED|EXPIRED|ENDED`, `started_by_participant_id`, `started_at`, `expires_at`, `revoked_at?`, `revocation_reason?`.

partial UNIQUE(trip_id) WHERE status='ACTIVE'; `UNIQUE(trip_id,id)`, `UNIQUE(trip_id,generation)`. 공유 재시작은 새 id 생성이다. 과거 동의/초대/vehicle credential을 새 세션에 승계하지 않는다. sharingEnabled는 별도 boolean 원본보다 현재 session 상태의 projection으로 제공한다.

### `participant_consents`

`id uuid PK`, `trip_id`, `sharing_session_id`, `participant_id`, `scope LOCATION_PUBLISH|LOCATION_VIEW|TRIP_COLLABORATION`, `policy_version varchar(40)`, `granted_at`, `revoked_at?`.

모두 동일 trip composite FK. partial UNIQUE(sharing_session_id,participant_id,scope) WHERE revoked_at IS NULL. 문구 버전과 시각만 저장하고 과도한 IP/단말지문은 저장하지 않는다. 공유 참여 동의가 곧 위치 발신 동의는 아니다. 동승자의 개인 위치는 기본 수집하지 않는다. owner가 동승자라면 운전자의 위치 발신 동의를 대신하지 못한다. 개인 철회는 본인 scope를 중단하고, owner의 전체 sharing revoke는 전체 세션을 종료한다.

### `trip_invitations`

`id uuid PK`, `trip_id`, `sharing_session_id`, `created_by_participant_id`, `token_hash bytea UNIQUE`, `offered_role COMPANION|DRIVER`, `expires_at`, `consumed_at?`, `consumed_by_user_id?`, `revoked_at?`.

기본 15분, 한 번 사용, 만료는 trip/session 만료를 넘지 않는다. 최소 128bit 이상 난수 토큰의 digest만 저장. 낮은 엔트로피 숫자코드를 쓸 경우 별도 HMAC/attempt 제한이 필요하므로 첫 버전에서는 피한다. DRIVER 초대는 기존 driver 해제 없이 자동 driver 교체하지 않는다. 재참여/이미 참여 중인 사용자의 교환 규칙을 명시하고 토큰은 트랜잭션 안에서 한 번만 소모한다.

인덱스 `(trip_id,sharing_session_id)`, `(expires_at)`. 초대는 폰 계정 인증을 대체하지 않는다.

## 5. A2: 차량 pairing과 단기 인증

### `vehicle_pairings`

`id uuid PK`, `trip_id?`, `sharing_session_id?`, `driver_participant_id?`, `installation_id uuid`(앱 설치 식별자, 차량/VIN/소유 증명이 아님), `challenge_hash bytea UNIQUE`, `poll_secret_hash bytea UNIQUE`, `status PENDING|APPROVED|CLAIMED|REVOKED|EXPIRED`, `expires_at`, `approved_at?`, `revoked_at?`.

차량이 요청한 QR/pairing challenge를 로그인한 현재 driver가 폰에서 확인한다. challenge 5분, 일회 승인, trip/session 범위 검증. 공개 시작 PENDING은 trip/driver 미지정이며 approve 트랜잭션에서 세 FK를 함께 채우고 이후 필수(CHECK)로 강제한다. pollSecret은 차량 polling/일회 claim 전용이다. credential 수령 시 CLAIMED로 전이한다. owner이지만 companion이면 driver처럼 승인하지 못한다. 차량은 폰 계정 세션을 갖지 않는다. persistent vehicle_devices 테이블/차량 등록·소유권 관리는 요구 확정 후 추가하며 첫 버전에서는 생략한다.

### `vehicle_credentials`

`id uuid PK`, `pairing_id FK`, `trip_id`, `sharing_session_id`, `driver_participant_id`, `token_hash bytea UNIQUE`, `expires_at`, `revoked_at?`, `last_used_at?`.

credential 수명 제안: min(발급+12시간, trip/session 만료), 매 요청 DB에서 즉시 철회 여부 검사. 장기 계정 credential이 아니라 한 trip용이며 수시 재승인 부담을 피한다. 허용 scope는 `TRIP_READ`, `LOCATION_PUBLISH`, `NAV_COMMAND_CLAIM`, `NAV_RESULT_WRITE`, `PROPOSAL_DECIDE`, `TRIP_END`, `PARKING_CONFIRM`, `TRIP_PHASE` 중 승인된 scope allowlist; 초대·계정·다른 일정 권한 없음. 첫 버전 자동 갱신은 없고 만료 시 폰에서 재승인한다. 향후 갱신 도입 시 pairing+driver+동의 재검증과 credential 회전이 필요하다. 재점화 자동복구는 미구현이며 만료/기기 유실 후 폰 승인 재pairing으로 복구한다. token 원문/QR challenge/인증 헤더를 로그에 남기지 않는다. 발급 응답 유실은 새 pairing으로 해결하고 원문을 DB에 장기 보관하지 않는다.

## 6. A: 위치·제안·내비 명령

### `trip_latest_locations`

`trip_id PK/FK`, `sharing_session_id`, `publisher_participant_id`, `vehicle_pairing_id?`, `latitude`, `longitude`, `accuracy_meters numeric check 0..10000`, `observed_at`, `received_at`, `source PHONE|VEHICLE|SIMULATED`, `publisher_sequence bigint?`, `observation_id uuid`, `request_hash bytea`. 동일 observationId+동일 payload는 재생, 다른 payload는 409; 최신 한 점을 교체한 뒤 과거 ID 재전송은 stale로 거부한다.

trip당 한 점만 UPSERT. 실제 위치가 없으면 null/unavailable이고 0,0을 채우지 않는다. source는 서버가 credential/모드와 대조하며 client가 SIMULATED를 VEHICLE로 바꿔 신뢰도를 얻을 수 없다. 최대 나이 2분, 미래 skew 30초(현재 v1 정책 유지); 더 최신 observed_at만 수락. 서버/클라이언트 시간 차이와 publisher 교체는 session/driver 변경에서 기존 최신점 삭제로 처리한다.

조회 시 session ACTIVE + 만료 전 + 읽기 동의 + 활성 membership 검증. 2분 이상 된 점은 최신 위치로 노출하지 않고 stale/unavailable 표시한다. 삭제 job은 보조이며 접근 차단은 동기 처리한다. PHONE과 VEHICLE 동시 공급 시 source 우선순위를 임의 추정하지 말고 현재 driver가 선택한 publisher 하나를 사용한다.

### `place_proposals`

`id`, `trip_id`, `sharing_session_id`, `proposer_participant_id`, `request_id uuid`, `request_hash bytea`, `kind DESTINATION|STOP|PARKING`, `comparison_id?`, `place_name`, `latitude?`, `longitude?`, `place_provider?`, `place_external_id?`, `decision_status PENDING|ACCEPTED|DISMISSED|CANCELLED`, `decided_by_participant_id?`, `decided_at?`, `version`.

UNIQUE(trip_id,proposer_participant_id,request_id), UNIQUE(trip_id,id). 같은 requestId+같은 body는 기존 결과 반환, body 다르면 409. 좌표 없는 장소 제안도 저장 가능하나 좌표가 없는 내비 적용은 명시적 불가/해결 필요이며 성공 처리하지 않는다. 인덱스 `(trip_id,decision_status,created_at)`.

### `navigation_commands`

`id`, `trip_id`, `sharing_session_id`, `proposal_id FK?`, `target_pairing_id FK?`, `requested_by_participant_id`, `command_type SET_DESTINATION|ADD_STOP|SELECT_PARKING`, `target_name`, `target_latitude?`, `target_longitude?`, `status REQUESTED|CLAIMED|APPLIED|FAILED|DEMO_APPLIED|CANCELLED|EXPIRED|OUTCOME_UNKNOWN`, `integration_mode DEMO|PLEOS`, `requested_at`, `expires_at`, `claimed_at?`, `completed_at?`, `result_code?`, `result_detail varchar(200)?`, `version`.

제안 수락은 현재 DRIVER(또는 그 pairing의 승인 scope)만 가능하며 owner-companion에게는 허용하지 않는다. 제안 수락과 command 생성은 한 트랜잭션. 첫 adapter는 SET_DESTINATION만 지원하고 STOP/PARKING 의도는 명시적 UNSUPPORTED_COMMAND로 거절한다(목적지 변경으로 몰래 치환하지 않음). SDK 미연결이면 command는 즉시 FAILED/SDK_NOT_CONNECTED; DEMO는 별도 모드이며 실제 실행으로 가장하지 않는다. `UNIQUE(proposal_id)`로 첫 버전의 암묵적 중복 요청을 막는다. 재시도 기능이 필요해지면 attempt_no와 명시적 새 명령 API를 추가한다. partial UNIQUE(trip_id) WHERE status IN ('REQUESTED','CLAIMED','OUTCOME_UNKNOWN')로 동시 목적지 명령 한 개. 미완료 명령이 있으면 새 승인은 409 COMMAND_IN_PROGRESS; OUTCOME_UNKNOWN을 자동 취소해 우회하지 않는다. 실행 가능 REQUESTED/CLAIMED에는 목표 좌표 쌍 필수, 즉시 FAILED 기록에는 미해결 좌표 null을 허용한다.

`CLAIMED`는 차량이 받아 처리 중이라는 뜻이지 실제 적용이 아니다. APPLIED는 연결된 실제 adapter 결과만 허용, DEMO_APPLIED는 별도 표시. 미전달 REQUESTED의 기한 종료는 EXPIRED, CLAIMED 뒤 실제 실행 여부 불명은 OUTCOME_UNKNOWN으로 남기고 성공·미실행 어느 쪽도 추정하지 않는다. OUTCOME_UNKNOWN은 자동 재시도/새 명령 발행을 막고 실제 adapter 상태 조회 또는 운전자 확인으로 명시적 해소한다. 결과는 commandId+pairing scope를 검증하고 CAS 상태 전이로 처리한다. 취소 후 늦게 온 성공은 현재 목적지를 덮어쓰지 않는다. 물리 SDK 작업은 DB 트랜잭션과 원자화 불가: 차량에서 commandId 중복 방지 및 만료 직전 유효성 확인이 필요하고 이미 실행된 목적지 변경을 revoke가 되돌린다고 약속하지 않는다.

일정의 계획 목적지와 현재 실제 내비 목적지는 구분한다. 제안 승인만으로 둘 다 변경하지 않는다. APPLIED 후에만 실제 내비 목적지 projection을 갱신하고, DEMO_APPLIED는 데모 projection으로 유지한다.

## 7. B: 경유·주차·ETA·알림 (전체 기획 범위, 첫 동작 묶음 뒤)

### `trip_stops` — 시간 설계의 경유지

`id`, `trip_id`, `sequence_no integer>=0`, `kind INTERMEDIATE|DESTINATION`, `place_name`, `latitude?`, `longitude?`, `planned_stay_minutes integer>=0`, `return_walk_seconds integer? >=0`, `parking_candidate_id?`, `status PLANNED|ARRIVED|DEPARTED|SKIPPED`, `person_arrived_at?`, `departed_at?`, `version`.

UNIQUE(trip_id,sequence_no), UNIQUE(trip_id,id). 초기엔 최종 목적지를 trips에 두고, 경유지 기능 도입 때 최종 목적지 중복 저장을 제거하거나 destination row를 유일한 원본으로 바꾸는 migration을 함께 한다. 단순히 양쪽 값을 독립 수정하지 않는다. 경유 체류는 최종 목적지 체류와 별도이며, **차로 돌아오는 도보시간**은 방문지까지 가는 도보시간을 무조건 재사용하지 않는다.

### `parking_candidates` — 후보·출처 스냅샷

`id`, `trip_id`, `stop_id?`, `provider`, `provider_place_id?`, `name`, `latitude`, `longitude`, `address?`, `distance_to_destination_m?`, `walk_to_destination_seconds?`, `walk_back_to_car_seconds?`, `availability_status AVAILABLE|FULL|UNKNOWN`, `available_spaces integer?`, `availability_observed_at?`, `fetched_at`, `valid_until?`, `data_mode LIVE|CACHED|SIMULATED`.

UNIQUE(trip_id,id), 인덱스 `(trip_id, fetched_at DESC)`. 주차장 '존재'와 실제 빈자리 '확인'은 별개다. 데이터가 없으면 UNKNOWN/null이고 0대를 채우지 않는다. 오래된 공급자 응답은 available로 단정하지 않는다. 후보 선택은 실제 주차 확정이 아니며 공급자 라이선스 허용 범위 안에서만 저장한다.

### `parking_cost_quotes` — 비교 가능한 예상 주차요금

`id`, `trip_id`, `candidate_id`, `planned_entry_at`, `planned_exit_at`, `duration_minutes`, `estimated_amount_minor bigint?`, `currency char(3)`, `quote_status ESTIMATED|PROVIDER_QUOTED|UNKNOWN`, `tariff_summary text?`, `pricing_source`, `pricing_version?`, `fetched_at`, `valid_until?`, `assumptions jsonb`.

CHECK(exit>entry, duration>0, amount>=0 when present). 인덱스 `(candidate_id,fetched_at DESC)`. 동일 체류·입출차 시간 기준으로 비교하고 무료시간/단위요금/일 최대/할인 적용 여부를 assumptions에 명시한다. 요금 미제공은 UNKNOWN이며 무료 0원과 구분한다. 전체 tariff 엔진은 첫 버전에 불필요하고 출처가 있는 계산 가능한 규칙만 지원한다. 실제 결제 금액을 보장하지 않는다.

### `comparison_snapshots` / `eta_snapshots` — 서로 다른 도착시각과 근거

비교 묶음: `comparison_snapshots(id,trip_id,requested_by_participant_id,trip_plan_revision,mode LIVE|DEMO,input_hash,calculated_at,expires_at,arrival_basis,deadline_at?,pricing_duration_minutes)`. 비교 1개에 후보/요금/ETA가 N개이며 생성 후 불변이다. `comparison_id` FK를 candidate/quote/ETA에 추가해 어떤 후보들이 동일 입력으로 비교됐는지 보존한다. 인덱스 `(trip_id,calculated_at DESC)`. metric별 provider/source/observedAt/가정이 다르면 ETA의 provenance jsonb 배열로 분리한다.

`id`, `trip_id`, `stop_id?`, `parking_candidate_id?`, `calculated_at`, `valid_until`, `trip_plan_revision`, `comparison_id`, `location_observed_at?`, `routing_provider`, `provider_route_id?`, `data_mode LIVE|CACHED|SIMULATED`, `drive_seconds?`, `parking_search_seconds?`, `walk_to_destination_seconds?`, `return_walk_seconds?`, `planned_stay_seconds?`, `vehicle_arrival_at?`, `person_arrival_at?`, `return_to_car_at?`, `latest_leave_at?`, `slack_seconds?`, `quality COMPLETE|PARTIAL|UNAVAILABLE`, `assumptions jsonb`.

각 duration은 nonnegative nullable. `slack_seconds`는 지각 시 음수가 정상이다. UNIQUE(trip_id,id), 인덱스 `(trip_id,calculated_at DESC)`. snapshot은 계산 당시 값이며 trip 계획/목적지 plan_revision이나 위치가 바뀌면 구형 표시한다. 좌표 원본·전체 경로 polyline은 중복 저장하지 않는다. 없는 도보/주차 탐색 시간을 0으로 넣어 COMPLETE로 표시하지 않는다.

예시 의미: vehicleArrival = 기준시각+주행; personArrival = vehicleArrival+주차탐색+목적지까지 도보. 경유지에서 체류 후 returnToCar = personArrival+체류+차까지 복귀도보. 이후 목적지까지의 주행/주차/도보를 빼서 latestLeaveAt을 계산한다. deadline 비교는 arrivalBasis에 맞는 도착시각을 사용한다. 누락/추정 구성요소와 계산 버전을 assumptions에 남기며 현재 도착 보장으로 표현하지 않는다.

### `trip_milestones` — 추정과 실제 확인 분리

`id`, `trip_id`, `stop_id?`, `type VEHICLE_ARRIVED|PARKING_CONFIRMED|PERSON_ARRIVED|RETURNED_TO_CAR`, `occurred_at`, `recorded_at`, `reported_by_participant_id?`, `source USER_CONFIRMED|SDK_EVENT|SIMULATED`, `request_id uuid`, `evidence_code?`.

UNIQUE(trip_id,request_id). 내비 arrival 이벤트만으로 PARKING_CONFIRMED/PERSON_ARRIVED를 생성하지 않는다. phase 변경은 해당 확인 근거와 한 트랜잭션으로 반영한다. 사람 도착이 확인되었을 때 실제 체류 시작을 정할 수 있으며 ETA만으로 체류 타이머를 시작하지 않는다.

### `return_reminder_plans` / `reminder_client_statuses` — B의 로컬 알림 계획

`return_reminder_plans(id,trip_id UNIQUE,anchor PARKING_CONFIRMED|PERSON_ARRIVAL_CONFIRMED,stay_minutes,lead_minutes,return_walk_seconds?,remind_at?,status DISABLED|WAITING_FOR_ANCHOR|PLANNED|CANCELLED,version)`. 체류 종료 알림과 차로 복귀할 출발 알림은 `reminder_basis STAY_END|RETURN_TO_CAR`로 구분한다. anchor 미확인 시 remind_at=null; deadline/체류 변경 시 version 증가·재계산한다. `reminder_client_statuses(plan_id,participant_id,installation_id,plan_version,status SCHEDULED|PERMISSION_DENIED|CANCELLED|ERROR,observed_at)` PK(plan_id,participant_id,installation_id). SCHEDULED는 앱 예약 보고이지 표시 완료 증거가 아니다.

추가 phase 이벤트: `trip_phase_events(id,trip_id,phase,observed_at,recorded_at,source,reported_by_participant_id?,user_confirmed,request_id)` UNIQUE(trip_id,request_id). RETURNING/재DRIVING처럼 milestone만으로 표현되지 않는 phase도 여기에 남기고 trips.phase와 원자 갱신한다.

### `notification_jobs` / `push_installations` — 후속 서버 push

후속 알림 예약: `notification_jobs(id,trip_id,participant_id,stop_id?,type,scheduled_at,basis_eta_snapshot_id?,plan_version,status PENDING|CLAIMED|SENT|FAILED|CANCELLED|EXPIRED,attempts,next_attempt_at?,lease_until?,provider_message_id?,dedupe_key UNIQUE,expires_at)`.

push 설치: `push_installations(id,user_id,installation_id UNIQUE,platform ANDROID,encrypted_push_token,last_seen_at,revoked_at?)`. token은 민감정보로 취급하고 앱 재설치/로그아웃 시 폐기한다. 인덱스 `(next_attempt_at,scheduled_at) WHERE status='PENDING'`. Spring worker가 `FOR UPDATE SKIP LOCKED`로 lease를 얻고 처리한다. 발송 직전에 최신 동의·membership·계획 버전을 다시 확인한다. trip 변경/철회는 과거 job을 취소하고 최신 계획만 유효하게 한다.

외부 push 수락은 사용자의 수신/열람 보장이 아니다. 적어도 한 번 전달 가능성이 있으므로 클라이언트 notification ID dedupe를 사용한다. 첫 단계 foreground polling은 알림 보장으로 소개하지 않으며, 무료 서버 sleep 상태의 스케줄러는 정확한 시각 실행을 보장하지 않는다. 운영 알림 약속 전에 실행 환경과 Android background delivery를 별도 검증한다.

## 8. C: 장소 학습의 확장점

지금 생성하지 않을 후보: `place_experience_samples(user_id,place_provider,place_id,observed_stay_seconds,parking_search_seconds,source,consent_id,recorded_at)`와 `user_place_preferences`. 별도 학습 동의 후에만 측정된 값과 추정값을 구분하여 최소 수집한다. 충분한 표본·오차 평가 전 도착 지연 원인/추천 정확도를 약속하지 않는다. 원시 위치 이력을 쌓아 두고 나중에 목적을 붙이는 방식은 사용하지 않는다.

## 9. 텍스트 ER와 트랜잭션 경계

- auth.users 1—1 app_users; app_users 1—N trips(owner), trips 1—N trip_participants, 각 참여자는 app_user 1명.
- trips 1—N sharing_sessions(활성 0..1); session 1—N consent/invitation/pairing. 세션·참여자·관련 자식은 동일 trip composite FK.
- pairing 1—N vehicle_credentials; trip 1—0..1 latest_location; proposal 1—0..1 navigation_command(첫 버전).
- trip 1—N stop/parking candidate/ETA/milestone; candidate 1—N cost_quote; ETA 선택 기준을 notification_job이 참조.
- 공유 revoke는 trip 잠금 → session revoke → 초대/동의/credential 폐기 → 최신 위치 DELETE → 미완료 command/job CANCELLED → revision 증가를 **한 트랜잭션**으로 처리한다.
- 개인 위치 발신 철회는 해당 consent 폐기와 본인 latest point 삭제를 원자적으로 처리한다. 읽기만 철회한 사용자는 이후 읽기가 금지된다. 전체 revoke가 아니라면 다른 사람의 세션을 임의 종료하지 않는다.
- 요청의 동의 검사를 트랜잭션 밖에서만 하면 revoke 직후 위치가 다시 저장될 수 있다. 위치/초대소모/command 결과도 동일 trip 행 잠금 순서를 사용해 경쟁을 직렬화한다. 커밋 이후 읽기에서는 철회를 반드시 확인하고, 이미 기기로 전달된 정보를 원격으로 지웠다고 보장하지 않는다.
- 상태 변경 API는 expectedVersion + CAS update, 충돌은 409. 계획 편집은 plan_revision ETag/If-Match(누락 428, 불일치 412)를 사용한다. DB `version`은 엔티티 잠금, trip `revision`은 전체 snapshot 변경 번호, `plan_revision`은 계획만의 변경 번호다. 고빈도 위치 한 점 때문에 모든 화면 수정 CAS가 충돌하지 않도록 엔티티 version과 trip projection revision을 구분한다.
- 초대 사용은 SELECT FOR UPDATE 후 미소모/미철회/만료/세션/driver unique 확인 → membership/동의 생성 → consumed 기록. 한 초대에 두 명이 동시에 가입할 수 없다.
- 불확실한 네트워크 응답의 자동 재시도는 endpoint별 정책이 필요하다. 사용자 인증 후 일반 생성은 `api_idempotency(user_id,operation,key,request_hash,resource_id,expires_at)` UNIQUE(user_id,operation,key)로 24시간 중복 방지 가능. credential 원문은 이 테이블에 저장하지 않는다.

## 10. 보존·삭제·보안 기본안

다음 기간은 법적 보존 결론이 아닌 제품 최소수집 제안이며, 서비스 공개 전에 고지/운영 정책을 확정한다.

| 데이터 | 제안 보존 |
|---|---|
| 최신 위치 | 덮어쓰기, 철회/종료 즉시 삭제, 2분 이후 노출 중단·주기 청소 |
| 미사용 초대/pairing challenge/만료 credential | 효력 즉시 차단, 만료 후 24시간 이내 청소 |
| ETA/주차요금/후보 스냅샷 | 일정 종료 후 24시간 이내 삭제; 라이선스가 더 짧으면 그 기준 |
| 완료 일정·제안·command·참여자 | 초기 개인 기록 기본 30일 후 hard delete; 사용자가 삭제하면 즉시 도메인 제거 |
| 동의 증적 | 우선 일정과 동일 30일, 좌표 없이 버전/시각/철회만; 별도 의무가 확인되면 독립 정책 |
| notification payload/job | 위치 없는 최소 payload, 종료/철회 시 취소, terminal 7일 청소 |
| 서버 로그 | 기본 7일, credential/초대 원문/정밀 위치/이메일 제외 |

FK cascade는 trip 자식 일괄 삭제에 사용한다. 청소 실패가 접근 권한 연장으로 이어지지 않아야 한다. 백업의 잔존/복구 시 삭제 재적용까지 정책에 설명하고 즉시 물리 소거를 과장하지 않는다.

Supabase의 Data API는 사용하지 않으면 끄고 `app`을 exposed schemas에 추가하지 않는다. `anon`, `authenticated`, PUBLIC에 도메인 schema/table 권한을 주지 않는다. JDBC runtime은 제한된 app schema DML 권한만, Flyway migration role은 DDL 별도. 서버 전용 DB password/service secret을 앱에 넣지 않는다. Auth JWT 서명/issuer/audience/expiry를 검증한 뒤 membership/세션 권한은 항상 DB에서 확인한다. raw JDBC가 폰 JWT의 `auth.uid()`를 자동 전달하지 않으므로 Supabase RLS가 JDBC 접근을 자동으로 사용자별 보호한다는 전제는 금지한다. 선택적 RLS를 추가하더라도 transaction-local context와 connection pool reset 검증이 필요하며 첫 구현은 Spring authorization을 주 경계로 삼는다. [공식 Data API 보안](https://supabase.com/docs/guides/api/securing-your-api), [사용자 FK 관리](https://supabase.com/docs/guides/auth/managing-user-data), [JWT 검증](https://supabase.com/docs/guides/auth/jwts).

## 11. 현행 → 제안 migration 순서

1. 기존 V1은 이미 적용될 수 있으므로 수정하지 않는다. 새 version migration으로 schema/제약을 추가하고 실제 PostgreSQL에서 검증한다. 현재 설계 단계에서는 실행하지 않는다.
2. 기존 capability owner는 영구 계정에 안전하게 역매핑할 근거가 없다. 데모 데이터면 만료/초기화; 보존이 필요하면 기존 유효 owner bearer와 새 로그인 계정의 명시적 claim 절차를 설계한다. 임의 UUID/이름 매칭으로 소유권 이전하지 않는다.
3. 기존 `participants.active` → membership status, ready 유지; OWNER access token은 trips.owner_user_id와 owner membership으로 이전. role 중복 driver 정리 뒤 partial unique 추가.
4. trips에 deadline/arrival basis/planned stay/time_zone/lifecycle을 추가. 현행 ACTIVE/ENDED는 그대로 대응하며 새 필드는 알 수 없는 값 null(시간대는 확인된 기본값), 과거 완료시간을 지어내지 않는다.
5. `sharing_enabled` → sharing_sessions, 기존 토큰·초대는 legacy 만료까지 격리하거나 일괄 폐기. 과거 동의 문구/시각이 없으면 동의 증적을 조작하지 말고 새 공유 시작 시 재동의 받는다.
6. trips의 location_* → trip_latest_locations. legacy session/동의가 검증되지 않은 점은 삭제한다. 기존 schema의 좌표 범위/짝 제약을 추가하기 전에 오염값 점검.
7. proposals의 PENDING/DISMISSED/CANCELLED → decision 상태 그대로. APPLY_REQUESTED/APPLIED/FAILED/DEMO_APPLIED → decision ACCEPTED + 각각 REQUESTED/APPLIED/FAILED/DEMO_APPLIED command. 현행 결과를 실제 SDK 증거로 승격하지 않는다; 기존 demo mode/source를 유지한다.
8. 새 v2 API/클라이언트 전환 후 legacy access_tokens와 중복 location/status 컬럼을 삭제한다. 공개 API는 additive column migration만으로 호환되는 것이 아니므로 v1/v2 전환 창을 별도 관리한다.
9. B 테이블은 해당 기능 구현 시에만 추가. 모든 계획 테이블을 먼저 배포할 필요 없다.

검증 기준: 같은 초대 동시 가입 1건만 성공, driver 중복 금지, 다른 trip FK 거부, revoke와 위치 업로드 경합 후 위치 없음, 만료 세션 즉시 거부, 과거 command 성공으로 목적지 덮어쓰기 없음, APPLIED/DEMO_APPLIED 분리, 마감 기준별 ETA 계산, UNKNOWN 요금/빈자리/도보 처리, 재로그인 소유권 복구, 앱 JWT로 JDBC 접근 불가, 익명 Data API 도메인 읽기 불가.

# API 상세 설계

작성일: 2026-10-28. 대상: 차량 Kotlin 앱 + 운전자/동승자가 함께 쓰는 단일 폰 Kotlin 앱 + Spring Boot 모듈형 모놀리스 + Supabase PostgreSQL.

**추천 결정:** 폰 신원은 Supabase Auth 도입을 제안하고, 여유로 데이터·권한·상태 변경은 Spring Boot 한곳을 통한다. 차량은 사용자 로그인 정보를 받지 않고 운행에 한정된 페어링 자격만 받는다. 동기화는 foreground REST polling. 이 문서의 `/api/v2`는 새 설계이며 구현·배포·Supabase 설정 완료를 의미하지 않는다. 로그인 방식(이메일/소셜/전화 OTP)은 미결정이다. “폰 신원”은 전화번호 의무 수집을 뜻하지 않는다.

## 1. 현재 있는 것 / 새로 필요한 것

근거: `contracts/openapi.yaml`, `docs/ARCHITECTURE.md`, `services/backend/README.md`, `api/TripController.java`, `V1__trip_sharing.sql` 직접 확인.

현재 `/api/v1` 실제 12개 동작:

| 메서드·경로 | 현재 권한·의미 |
|---|---|
| GET /health | 공개 liveness |
| POST /trips | 로컬 공개 진입점, 24시간 trip + OWNER capability |
| GET /trips/{id} | 운행 scoped capability, 5초 foreground polling |
| POST /trips/{id}/invites | OWNER, 명시적 공유 동의, 15분 일회성 코드 |
| POST /invites/join | 코드·참여 동의, 초대에 저장된 DRIVER/COMPANION 역할 |
| PUT /trips/{id}/readiness | COMPANION |
| POST /trips/{id}/proposals | COMPANION, requestId 중복 방지 |
| POST /trips/{id}/proposals/{proposalId}/decision | OWNER/DRIVER, expectedVersion |
| POST /trips/{id}/proposals/{proposalId}/navigation-result | OWNER/DRIVER, 실 APPLIED 불가 |
| PUT /trips/{id}/location | OWNER/DRIVER, 공유 중 신선한 관측만 |
| POST /trips/{id}/sharing/revoke | OWNER, 토큰/초대 폐기·위치 삭제·대기 작업 취소 |
| POST /trips/{id}/end | OWNER/DRIVER, 종료+공유 철회 |

현재 토큰/초대는 무작위 capability의 SHA-256 해시만 저장한다. 계정, 계정 복구, 차량 페어링, 도착 마감, 체류 계획, 외부 ETA·주차 정보, 복귀 알림, 자동 학습은 없다. 기본 H2이며 PostgreSQL profile은 검증 전 scaffolding이다. 현재 create/invite/join 응답 유실은 자동 재시도 안전하지 않다. 아래 계정 기반 복구를 현재 기능으로 설명하면 안 된다.

## 2. 권한·인증 경계

- 폰 → Supabase Auth 로그인(제안) → access JWT → Spring 검증. 검증 항목: 서명/허용 알고리즘, 프로젝트 issuer, audience, exp/nbf, sub. `sub`를 사용자 UUID로 사용한다. 요청 body의 userId/role을 신뢰하지 않는다. refresh는 Auth 제공자 흐름을 사용하고 별도 여유로 refresh 프로토콜을 만들지 않는다.
- `trips.owner_user_id`는 관리 소유권, membership.role은 `DRIVER | COMPANION`. 소유자도 둘 중 실제 참여 역할을 가진다. trip당 활성 DRIVER 1명. OWNER라는 합성 관리 권한을 운전 승인 권한과 동일시하지 않는다. 동승자인 소유자는 경로 적용을 승인하지 못한다.
- 활성 membership, trip 상태, sharing session generation, 동의 scope를 **매 요청 서버에서** 재검사한다. 유효한 JWT 자체는 운행 접근 권한이 아니다. 소유자는 공유 철회 뒤 자신의 운행 계획만 읽을 수 있고 철회된 위치/타인 정보를 되살리지 못한다.
- 차량 credential은 pairing + trip + driver membership + sharing generation + expiry에 한정한 opaque bearer. 해시 저장, 만료 `min(trip.expiresAt, 발급 시각 + 12시간)`(MVP 제품 제안값), 운행 종료·페어링 해제·운전자/공유 변경 시 즉시 폐기. 폰 JWT/refresh token/Supabase secret을 차량에 전달하지 않는다. installationId는 비신뢰 앱 설치 라벨이며 차량 소유나 OEM 신원 증명이 아니다.
- 권한 약어: **U** 로그인 폰 사용자, **O** 운행 소유자, **D** 활성 운전자, **C** 활성 동승자, **V** 해당 운행·역할·generation에 묶인 차량 credential. 아래 V는 필요한 scope별 허용이며 계정·초대 관리 권한은 없다.
- PostgreSQL 앱 테이블은 private schema/Data API 비노출을 기본 제안한다. Android가 DB를 직접 수정하지 않는다. Spring DB role은 최소권한. Supabase service-role/DB 비밀은 앱에 포함하지 않는다.

Supabase JWT/JWKS 및 회전은 [공식 JWT 문서](https://supabase.com/docs/guides/auth/jwts), [서명 키 문서](https://supabase.com/docs/guides/auth/signing-keys)에 근거한다. 전화 OTP를 고르면 별도 provider/SMS 설정이 필요하며 현재 설정되지 않았다([공식 전화 로그인 문서](https://supabase.com/docs/guides/auth/phone-login)).

## 3. 제안 엔드포인트 카탈로그

공통 prefix `/api/v2`. **핵심 A** = 먼저 구현할 공유·페어링·기본 계획. **핵심 B** = 공모전 가치인 비교·복귀 흐름, 외부 연동 전에는 명시적 unavailable/fixture만. **후속** = 실 SDK/푸시/자동화. `M`은 본인 리소스만, `R`은 현재 동의 범위의 읽기. 서버가 field-level projection을 적용한다.

### 3.1 핵심 A: 폰 계정·운행·공유

| 메서드·경로 | 권한 | 요청/응답 핵심 | 성공 |
|---|---|---|---|
| GET /me | U | userId, displayName, serverTime | 200 |
| GET /trips?status=ACTIVE&cursor=… | U/M | 본인 소유·참여 목록, cursor | 200 |
| POST /trips | U | destination, arrivalBasis, deadlineAt, plannedStayMinutes, timeZone, ownerRole → trip | 201 |
| GET /trips/{t} | O/D/C/V:R | 권한별 snapshot, revision, serverTime, capabilities | 200 |
| PATCH /trips/{t} | O | 계획 필드만, If-Match → trip | 200 |
| POST /trips/{t}/start | O/D | PLANNED→ACTIVE | 200 |
| POST /trips/{t}/end | O/D 또는 V:trip.end | 종료, 공유·credential·대기 command 원자 폐기 | 200 |
| POST /trips/{t}/cancel | O | 미종료 운행 취소, 동일 정리 | 200 |
| POST /trips/{t}/sharing-sessions | O | scopes, consentPolicyVersion → sessionId/generation | 201 |
| POST /trips/{t}/invites | O | role, sessionId, expiresInSeconds → code 1회 | 201 |
| POST /invites/join | U | code, consentScopes, consentPolicyVersion → membership/trip, 폰 토큰 발급 안 함 | 201 |
| POST /trips/{t}/sharing/revoke | O | 모든 공유 접근 원자 철회 | 200 |
| PUT /trips/{t}/consents/me | D/C:M | scope별 granted, consentPolicyVersion, expectedVersion → 본인 동의 상태 | 200 |
| DELETE /trips/{t}/memberships/me | D/C:M | 본인 동의 철회·탈퇴, 운전자면 차량 credential/대기 command 폐기 | 204 |
| PUT /trips/{t}/readiness/me | C:M | ready, observedAt → readiness/version | 200 |
| PUT /trips/{t}/location | D 또는 V:location.write | observation, consent generation → 최신 위치 metadata | 200 |

동의 scope: `TRIP_COLLABORATION`, `LOCATION_PUBLISH`, `LOCATION_VIEW`. `LOCATION_PUBLISH`는 실제 위치 제공자인 DRIVER가 본인 의사로 부여하고 OWNER가 대신 동의할 수 없다. `LOCATION_VIEW`는 해당 참여자의 위치 공유 수신 동의, `TRIP_COLLABORATION`은 운행 협업 동의다. 위치 게시에는 활성 session + DRIVER의 publish 동의, 위치 열람에는 session + 게시자 publish 동의 + 열람자 view 동의를 모두 검사한다. publish 철회는 위치 즉시 삭제·추가 수집/게시 중지, view 철회는 해당 수신자의 위치 projection 즉시 제거, collaboration 철회는 참여 접근과 관련 credential/대기 작업 폐기다. 동의 재부여는 원래 철회된 credential을 되살리지 않는다. 각 scope는 별도 선택이며 앱 OS 위치 권한 허용과 서비스 공유 동의는 다르다. DRIVER 초대는 기존 활성 DRIVER가 없을 때만 가능; 첫 버전에서는 복잡한 운행 중 역할 승격/소유권 이전 API를 두지 않는다. 공유 재개는 새 generation·새 초대·새 동의를 요구한다.

### 3.2 핵심 A: 차량 페어링

| 메서드·경로 | 권한 | 핵심 | 성공 |
|---|---|---|---|
| POST /vehicle-pairings | 공개·강한 rate limit | installationId, displayLabel → pairingId, 고엔트로피 displayCode, 별도 pollSecret, expiresAt | 201 |
| POST /vehicle-pairings/{p}/approve | D 폰 | displayCode, tripId, scope 확인 → 승인 상태 | 200 |
| GET /vehicle-pairings/{p} | 해당 pollSecret | PENDING/APPROVED/EXPIRED; 사용자 상세 비노출 | 200 |
| POST /vehicle-pairings/{p}/claim | 해당 pollSecret | 승인 credential 1회 수령 | 200 |
| DELETE /trips/{t}/vehicle-pairings/{p} | O/D | credential 즉시 폐기 | 204 |

PENDING 페어링은 아직 trip/driver가 없으므로 DB의 tripId/driverMembershipId/sharingGeneration 필드는 nullable이다. 승인 transaction에서 활성 DRIVER·동의를 검증하고 이 필드를 바인딩하며 이후 변경 불가, CLAIMED는 발급 완료 상태다.

페어링 코드는 인증된 폰이 스캔/확인하며 차량 표시명·trip·scope를 보여준다. 공개 시작 endpoint는 앱/네트워크별 rate limit·만료(예: 5분)·추측 방지가 필수다. pollSecret은 URL/query/log에 넣지 않는다. 차량이 폰 승인 없이 운전자 역할을 선택할 수 없다. MVP에서는 refresh 프로토콜 없이 운행 범위에서 최대 12시간 유지하여 매 15분 재승인을 요구하지 않는다. 만료 시 다시 승인하며 긴 운행의 갱신 UX는 후속 검토한다. claim 응답 유실은 새 페어링 필요(원문 credential 재생 ledger 없음). 생체/하드웨어 attestation까지 보장하는 프로토콜이 아니다.

### 3.3 핵심 A: 제안·명령

| 메서드·경로 | 권한 | 핵심 | 성공 |
|---|---|---|---|
| POST /trips/{t}/proposals | C | place, kind DESTINATION/STOP/PARKING, optional comparisonId → proposal | 201 |
| POST /trips/{t}/proposals/{p}/decision | D 또는 V:proposal.decide | decision ACCEPT/DISMISS, expectedVersion | 200 |
| GET /trips/{t}/navigation-commands?status=REQUESTED | V:command.read | 만료 전 해당 pairing 명령 | 200 |
| POST /trips/{t}/navigation-commands/{c}/claim | V:command.execute | expectedVersion → command, 실행 lease | 200 |
| POST /trips/{t}/navigation-commands/{c}/result | V:command.result | expectedVersion, outcome, providerRequestId?, evidence → 상태 | 200 |

command type은 `SET_DESTINATION | ADD_STOP | SELECT_PARKING`으로 proposal kind와 명시 매핑한다. 처음 검증된 adapter가 지원하는 type만 실행 가능하며, ADD_STOP/SELECT_PARKING이 미검증이면 `UNSUPPORTED_COMMAND`로 FAILED 기록하고 목적지 변경으로 몰래 치환하지 않는다. 현재는 SET_DESTINATION도 SDK_NOT_CONNECTED다.

proposal ACCEPT는 사용자 의사결정이며 실제 내비 성공이 아니다. 공급자 미연결이면 승인 기록은 `ACCEPTED`, command는 즉시 `FAILED`/`SDK_NOT_CONNECTED`로 남기고 차량에 실행 가능한 명령을 내리지 않는 정책을 제안한다. 실제 SDK가 연결된 모드에서만 REQUESTED를 만들고 성공 결과 검증 뒤 APPLIED. 데모 모드는 DEMO_APPLIED 표시를 유지하고 실제 목적지를 바꾸지 않는다. 폰은 실 APPLIED 결과를 보고할 수 없다.

### 3.4 핵심 B: 시간·주차·복귀

| 메서드·경로 | 권한 | 핵심 | 성공 |
|---|---|---|---|
| PUT /trips/{t}/stops | O/D | 순서·place·stayMinutes·returnWalkSeconds, If-Match | 200 |
| POST /trips/{t}/comparisons | D/C | origin + observedAt/source, destination, stops, parkingDurationMinutes, tripPlanRevision → 불변 비교 snapshot | 201 |
| GET /trips/{t}/comparisons/{c} | O/D/C/V:R | 후보별 ETA·도보·주차 요금·근거·만료·결측 | 200 |
| POST /trips/{t}/parking-confirmations | D 또는 V:parking.confirm | 주차 확인 userConfirmed=true, parkedAt, place, source | 201 |
| POST /trips/{t}/phase-events | D 또는 V:trip.phase | phase, observedAt, source, userConfirmed → phase | 201 |
| PUT /trips/{t}/return-reminder-plan | O/D | enabled, anchor, stayMinutes, leadMinutes → 계산된 remindAt/version | 200 |
| GET /trips/{t}/return-reminder-plan | O/D/C/V:R | 계획·계산 근거·client scheduling status | 200 |
| PUT /trips/{t}/return-reminder-plan/client-status | D:M | planVersion, installationId, status SCHEDULED/PERMISSION_DENIED/CANCELLED/ERROR, observedAt | 200 |

비교 계산 모듈과 provider adapter는 분리한다. 사용 가능한 provider가 없으면 503 `ROUTING_UNAVAILABLE`; 개발 fixture는 요청·응답 `mode=DEMO`, `source=SIMULATED`를 강제 표시한다. 일부 데이터만 실패하면 응답에 후보별 availability/reasons와 null을 넣는다. 무료/0분/빈자리 있음으로 대체하지 않는다. 비교 결과는 목적지 변경 명령이 아니며 제안·운전자 승인 단계를 거친다.

후속: 실 SDK 적용 검증, FCM/SSE, 재시동 복구, 반복 방문 학습·자동 체류 추정, 주차 occupancy 실시간 연동. 첫 버전 API에서 자동 학습을 실행하거나 백그라운드 알림 도달을 보장하지 않는다. 학습은 별도 opt-in·삭제·샘플 수/신뢰도·사용자 확인 모델을 설계한 뒤 추가한다.

## 4. 대표 JSON (전부 제안·합성 예시)

### 운행 생성

`POST /api/v2/trips`, `Authorization: Bearer <phone-access-jwt>`, `Idempotency-Key: <uuid>`

```json
{
  "destination": {"name": "예시 공연장", "latitude": 37.5, "longitude": 127.0},
  "arrivalBasis": "PERSON",
  "deadlineAt": "2026-10-28T10:00:00Z",
  "plannedStayMinutes": 90,
  "timeZone": "Asia/Seoul",
  "ownerRole": "DRIVER"
}
```

DB 정식 필드 매핑은 `deadline_at ↔ deadlineAt`, `arrival_basis ↔ arrivalBasis`, `planned_stay_minutes ↔ plannedStayMinutes`, `time_zone ↔ timeZone`, `plan_revision ↔ planRevision`이다.

`deadlineAt`는 선택한 arrivalBasis의 도착 마감이다. 기본 PERSON(주차 후 실제 목적지 도착); VEHICLE 선택이면 차량 도착 기한임을 표시한다. `plannedStayMinutes`는 목적지 체류 계획, 경유지 `stayMinutes`와 구분한다. 날짜·시간은 UTC ISO-8601, 표현은 IANA timeZone. 서버는 과거 deadline 경고와 계산 불가능 상태를 구분한다.

```json
{
  "id": "11111111-1111-4111-8111-111111111111",
  "revision": 1,
  "planRevision": 1,
  "status": "PLANNED",
  "phase": null,
  "arrivalBasis": "PERSON",
  "deadlineAt": "2026-10-28T10:00:00Z",
  "plannedStayMinutes": 90,
  "timeZone": "Asia/Seoul",
  "sharing": {"enabled": false, "generation": 0},
  "location": null,
  "capabilities": {"navigation": {"availability": "UNAVAILABLE", "reason": "SDK_NOT_CONNECTED"}},
  "serverTime": "2026-10-28T08:00:00Z"
}
```

### 비교 snapshot

아래 수치·장소는 실제 조회가 아닌 DEMO fixture. 차량 ETA와 사람 ETA는 둘 다 반환한다. fee에는 체류/과금 기간을 반드시 표시한다.

```json
{
  "id": "22222222-2222-4222-8222-222222222222",
  "mode": "DEMO",
  "tripPlanRevision": 1,
  "calculatedAt": "2026-10-28T08:00:00Z",
  "expiresAt": "2026-10-28T08:05:00Z",
  "candidates": [{
    "candidateId": "fixture-parking-a",
    "vehicleArrivalAt": "2026-10-28T09:35:00Z",
    "personArrivalAt": "2026-10-28T09:45:00Z",
    "drivingSeconds": 5700,
    "parkingSearchSeconds": 240,
    "walkToDestinationSeconds": 360,
    "returnWalkSeconds": 360,
    "intermediateStaySeconds": 0,
    "slackSeconds": 900,
    "arrivalBasis": "PERSON",
    "fee": {"estimatedAmount": 6000, "currency": "KRW", "pricingDurationMinutes": 120, "rulesAsOf": null, "availability": "ESTIMATED"},
    "occupancy": {"availability": "UNKNOWN", "availableSpaces": null, "observedAt": null},
    "provenance": [{"metric": "drivingSeconds", "provider": "FIXTURE", "source": "SIMULATED", "fetchedAt": "2026-10-28T08:00:00Z", "assumptions": ["합성 데모 수치"]}],
    "warnings": ["SIMULATED_DATA", "OCCUPANCY_UNKNOWN"]
  }]
}
```

계산식: 사람 도착 = 차량 도착 + 주차 탐색/주차 여유 + 목적지까지 도보. 경유지 포함 시 운전 구간 + 경유지 도보(왕복 구분) + 체류가 차량 ETA에 포함되어 중복 합산되지 않게 breakdown 정의. slack = deadline − 선택한 기준 ETA(음수=예상 지각). 경유지 허용 체류 최대값도 동일 baseline과 안전 buffer 기준으로 계산하며 0 미만은 불가능 상태. 주차 요금은 조회 시각·provider·요금 기준 기간·할인/상한/미확인 조건을 표시한다. 실제 주차 가능 여부와 비용 확정은 보장하지 않는다. 각 metric의 출처·계산법·관측 시각이 다르면 provenance도 분리한다.

### 수락과 적용 분리

`POST .../proposals/{p}/decision` body:

```json
{"decision":"ACCEPT","expectedVersion":2}
```

SDK 미연결 응답:

```json
{
  "proposal": {"id":"33333333-3333-4333-8333-333333333333", "decisionStatus":"ACCEPTED", "version":3},
  "navigationCommand": {"id":"44444444-4444-4444-8444-444444444444", "status":"FAILED", "reason":"SDK_NOT_CONNECTED", "version":1},
  "navigation": {"availability":"UNAVAILABLE", "reason":"SDK_NOT_CONNECTED"},
  "tripRevision":12
}
```

실 SDK command의 결과 요청은 `outcome: APPLIED`, `providerRequestId`, `appliedAt`, adapter evidence를 포함하고 서버가 command 대상·generation·만료·현재 상태·provider 모드와 비교한다. OEM이 검증 가능한 receipt를 제공하지 않으면 “차량 adapter 보고” 수준이며 독립된 실제 경로 검증이라고 표현하지 않는다.

### 위치

```json
{
  "observationId":"55555555-5555-4555-8555-555555555555",
  "sharingGeneration":3,
  "latitude":37.5,
  "longitude":127.0,
  "accuracyMeters":18,
  "observedAt":"2026-10-28T08:00:00Z",
  "source":"SIMULATED"
}
```

서버 응답에 `receivedAt`, `freshness: FRESH|STALE|UNAVAILABLE`, `ageSeconds` 추가. source는 `PHONE|VEHICLE|SIMULATED`; source 문자열만으로 OEM 원천을 검증했다고 주장하지 않는다. 토큰과 허용 업로드 source를 대조한다. 관측 2분 초과/미래 30초 초과/역순은 거부하며 snapshot에서 2분 초과 좌표는 null. 같은 observationId+동일 payload 재전송은 성공 재생, 변경 payload는 409. 최신 위치 한 건만 저장하며 기본 이동 궤적 수집 없음. 좌표·accuracy 유한값/범위, observedAt 밀리초 정규화 규칙은 v1을 이어간다.

### 복귀 알림 계획

```json
{
  "enabled":true,
  "anchor":"PARKING_CONFIRMED",
  "stayMinutes":90,
  "leadMinutes":10
}
```

`remindAt = parkingConfirmedAt + stayMinutes - leadMinutes`. 다른 anchor `PERSON_ARRIVAL_CONFIRMED` 선택 시 실제 사람 도착 수동 확인 전에는 `WAITING_FOR_ANCHOR`, 시각 null. “차로 돌아갈 시각” 기준을 선택한 계획에서는 returnWalkSeconds를 별도 차감하여 출발 알림을 계산하고 UI에 기준을 명시한다. 차량 내비 도착 이벤트를 주차 확인이나 사람 도착으로 자동 대체하지 않는다. 폰 앱이 권한 확인 후 로컬 알림 예약하고 planVersion별 결과를 기록한다. 서버 plan 저장/SCHEDULED 보고는 OS 알림 도달을 증명하지 않으며 절전·권한·오프라인/앱 삭제로 실패 가능하다.

## 5. 상태기계·동시성

### 독립된 상태 축

- Trip lifecycle: `PLANNED → ACTIVE → ENDED`; PLANNED/ACTIVE→CANCELLED, 만료 정책 적용 시→EXPIRED. 종료 상태 복구 금지. 예약 운행의 expiry·보존기간은 현재 24h 데모 TTL과 별도 정책으로 결정해야 한다.
- Trip phase: ACTIVE 내 `DRIVING → PARKED → WALKING → AT_DESTINATION → RETURNING`, 경유 후 재운전 허용. 각 이벤트의 source/observedAt/userConfirmed 기록. SDK arrival만으로 PARKED 확정 금지; 자동 학습 상태 아님.
- Proposal decision: `PENDING → ACCEPTED | DISMISSED | CANCELLED`. 승인 자체는 내비 변경 아님.
- Navigation command: `REQUESTED → CLAIMED → APPLIED | FAILED | DEMO_APPLIED`; 실행 전 REQUESTED는 CANCELLED/EXPIRED로 종료 가능. CLAIMED 뒤 결과가 불명확하면 `OUTCOME_UNKNOWN`; CLAIMED 만료를 미실행으로 간주하지 않는다. CLAIMED는 전달/실행 확보일 뿐 적용 성공 아님. 취소·철회는 이후 서버 수락과 새 실행 권한을 차단하지만 이미 호출된 SDK의 물리적 효과 취소를 보장하지 않으므로 취소 시점의 실행 불확실성을 함께 보존한다. 미연결은 FAILED 생성 정책. trip당 비종결 적용 command 최대 1개; 중복 적용 승인 409 `COMMAND_IN_PROGRESS`.
- Sharing: active session generation N → revoked; 재개는 N+1. 이전 동의/초대/credential로 복원 불가.
- Pairing: PENDING→APPROVED→CLAIMED, 만료/철회→EXPIRED/REVOKED. claim 응답에서만 raw credential 반환.
- Reminder plan: DISABLED/WAITING_FOR_ANCHOR/PLANNED/CANCELLED. 클라이언트 예약·실제 표시 관측은 별도이며 서버 “DELIVERED” 자동 추정 금지.

### revision·재시도 규칙

1. trip `revision`은 관측 변경 포함 snapshot 순서, `planRevision`은 계획 변경만 증가. 엔티티 `version`은 결정/계획의 충돌 기준. 위치 polling이 모든 계획 편집을 무의미하게 충돌시키지 않도록 구분한다.
2. 계획 PATCH/PUT은 `If-Match: "plan-7"` 등 해당 엔티티 ETag 필수. 없음 428, 불일치 412. 상태 command는 expectedVersion으로 충돌 시 409. 클라이언트가 최신 snapshot을 확인한 뒤 재결정; 서버 자동 덮어쓰기 금지.
3. 일반 POST는 `Idempotency-Key` 필수. scope=(principal, method, route, key), canonical request hash와 결과 참조, TTL(제안 24h). 같은 key+동일 요청은 저장 결과 재생, 다른 내용 409 `IDEMPOTENCY_KEY_REUSED`. 권한 재검사 후 재생하여 revoke 뒤 과거 위치/민감정보를 반환하지 않는다. DB unique + transaction으로 처리 결과와 원장을 함께 commit한다.
4. create trip/join은 account identity 기반 재조회가 가능하고 raw token 응답이 없어 안전한 결과 재생을 설계할 수 있다. **초대 발급·pairing 시작·credential claim은 비밀 1회 반환 예외**: 재생 시 credential을 돌려주지 않고 `409 SECRET_ALREADY_ISSUED` + 새 발급 절차. raw 비밀을 idempotency ledger에 평문 저장하지 않는다. 새 초대/페어링 발급은 이전 불확실한 자격을 먼저 폐기한다.
5. DB transaction 내 행 잠금/낙관 버전·조건 UPDATE로 역할/consent 재검사, 상태 변경, revision, 명령·원장 갱신을 원자 처리. claim/result/end/revoke 경쟁에서 늦은 결과는 409 `COMMAND_CANCELLED`/`SHARING_REVOKED`이며 종료된 상태를 부활시키지 않는다.
6. 차량은 commandId를 로컬 중복 실행 방지 키로 보관한다. 네트워크 응답 유실 시 결과 조회/동일 결과 재보고. SDK 호출과 서버 commit을 원자화할 수 없으므로 exactly-once 내비 적용 보장 금지. 확인 불가능한 실행 결과는 `OUTCOME_UNKNOWN`과 수동 확인 필요로 보여주고 자동 재호출하지 않는다. 재연결 후 동일 commandId의 provider 증거가 있으면 권한이 살아 있고 명령이 취소되지 않은 범위에서 결과를 대조한다; 공유 철회/종료 뒤에는 늦은 결과로 상태를 되살리지 않는다. OUTCOME_UNKNOWN은 후속 명령을 보류하고 운전자 확인/명시적 정리 절차를 요구한다.

공통 오류 예:

```json
{"code":"VERSION_CONFLICT","message":"다른 기기에서 변경되었습니다. 최신 상태를 확인하세요.","requestId":"req_example","currentVersion":8,"retryable":false}
```

400 필드 오류, 401 인증 만료/폐기, 403 역할/scope/동의 부족, 404 미존재 또는 접근 불가 리소스(열거 방지 정책 통일), 409 상태/중복키 충돌, 412 ETag 불일치, 422 계획 계산 불가능, 428 조건부 수정 헤더 없음, 429 rate limit+Retry-After, 503 provider 미사용/일시 장애. 내부 스택·토큰·좌표는 오류/일반 로그에 넣지 않는다.

## 6. 동기화·동의 철회·오프라인

- 앱 foreground에서 5초 GET snapshot 제안. 화면 종료/백그라운드 시 중지; 실패 지수 backoff+jitter, 연결 복구 즉시 전체 snapshot. UI는 lastSyncedAt과 OFFLINE을 표시하며 서버 revision 역행 응답은 버린다. 응답 `Cache-Control: no-store`.
- “실시간”은 다음 성공 polling 범위이며 푸시·백그라운드 전달 약속이 아니다. navigation adapter polling은 차량 앱 허용 lifecycle 안에서만 실행한다.
- revoke 요청이 서버에 도달하면 같은 transaction에서 공유 session/초대/membership 공유 접근/차량 credential 폐기, 위치 삭제, 대기 command 취소. 이후 읽기/쓰기는 즉시 차단한다. 서버 JWT signature cache는 공유 철회를 늦추는 사유가 될 수 없다.
- 폰에서 철회 선택 즉시 로컬 위치 수집·업로드/표시를 중단한다. 네트워크가 없으면 `서버 철회 대기`를 명시하며 서버 차단 완료라고 표시하지 않는다. 연결 시 철회 요청을 우선 전송한다.
- 서버에서 이미 전달된 화면/외부 스크린샷을 원격 삭제할 수 없고 오프라인 기기 캐시를 즉시 지울 수 없다. 앱은 위치 메모리 캐시 freshness/동의 lease 만료 시 숨김, 401/403/철회 응답 시 shared snapshot과 pending location을 폐기. 이 한계를 “모든 기기 즉시 삭제”라고 표현하지 않는다.
- 위치 재연결 시 오래된 버퍼를 업로드하지 않고 새 관측만 보낸다. 제안 draft는 로컬에 보존 가능하나 동의/운행/기한 재확인 전 자동 제출 금지. 수락/내비 적용/주차 확인/공유 승인은 오프라인 성공 처리 금지. 복귀 로컬 알림은 마지막 계획으로 울릴 수 있으므로 수정 시 재예약·불확실성 표시가 필요하다.

## 7. 구현 순서 및 계약 이행 조건

1. v1 로컬 데모 계약 유지. 현재 기능/테스트를 Supabase 통합 완료로 재명명하지 않는다.
2. PostgreSQL migration/통합 테스트, Auth 설정 승인·JWT 검증, account↔membership, 공유 generation/동의, 페어링과 scoped credential부터 구현. 기존 anonymous capability를 계정으로 자동 귀속하지 않는다. 데모 데이터는 폐기하거나 별도 보관; 명시적 소유 증명 없는 계정 승격 금지.
3. 새 v2 DTO/OpenAPI와 contract tests 작성. v1 `status=APPLY_REQUESTED`는 새 proposal `ACCEPTED` + command REQUESTED/CLAIMED로 변환; APPLIED/FAILED/DEMO_APPLIED도 의사결정과 적용 결과로 분해. v1 owner token의 role을 v2 사용자 신원으로 간주하지 않는다.
4. deadline/arrivalBasis/stops/stay, ETA 비교(출처와 UNKNOWN), 주차 수동 확인, 알림 plan과 로컬 예약 확인을 추가. 공급자 미연결은 명시적 unavailable 또는 명백한 DEMO만 허용.
5. 테스트 통과 기준: 동시 join 1회 소비, role 상승 차단, owner-companion의 운전 승인 금지, generation 뒤 stale credential 차단, revoke와 location/result 경합, idempotency payload 충돌, 응답 유실 복구, TTL 경계, timezone·음수 slack, occupancy unknown, 실제 APPLIED 차단, offline 회복. 외부 공개 전 abuse limit/TLS/비밀관리/삭제·보존/backup 운영 검토 필요.

이 문서는 설계 제안이며 repo 코드·현재 OpenAPI·외부 Supabase 설정을 변경하지 않았다.

## 배포 전에 검증할 항목

### Supabase 접근 경계

업무 테이블은 Data API에 노출하지 않는 전용 스키마에 둔다. Data API를 사용하지 않으면 비활성화하고, 사용한다면 공개 범위를 명시적으로 제한한다. `anon`/`authenticated` 역할에 업무 테이블 접근을 무심코 부여하지 않는다. 신규 테이블의 기본 권한도 함께 확인한다.

Spring Boot JDBC 접속은 Supabase 클라이언트의 JWT가 자동 전달되는 방식이 아니다. `auth.uid()` 기반 RLS만 설정해 두고 서버 권한 검사를 생략하면 안 된다. 서버가 모든 요청에서 여행 참여 상태와 작업 권한을 확인한다. 실행용 DB 역할은 필요한 SELECT/INSERT/UPDATE/DELETE만, Flyway 마이그레이션 역할은 DDL을 별도로 갖는다. Supabase 관리자 DB 계정으로 일상 요청을 처리하지 않는다.

IPv4 환경에서는 Session pooler 연결을 검토한다. SSL을 검증하고 작은 HikariCP 풀부터 시작한다. 연결 주소·CA·역할 권한을 실제 배포 환경에서 검증한다. 이 문서는 실제 Supabase 접속 성공을 증명하지 않는다.

### 실패와 동시성 테스트

- 서로 다른 여행의 ID를 바꿔 넣어 조회·수정·위치 접근이 되는지 검사한다. 전부 거부되어야 한다.
- 동일 초대가 동시에 두 번 수락되어도 한 번만 참가 처리한다.
- 동일 사용자·동일 여행의 중복 참가와 두 명의 활성 driver를 막는다.
- 승인 버튼을 반복해서 눌러도 내비 명령이 여러 개 생성되지 않는다.
- 한 기기가 과거 revision으로 계획을 덮어쓰면 충돌을 반환한다.
- 더 오래된 위치가 늦게 와도 최신 위치를 덮어쓰지 않는다.
- 공유 철회 직후의 위치 읽기/쓰기와 기존 초대 수락은 거부된다. 재공유는 새 동의·새 세션을 요구한다.
- 여행 종료·만료 후 예약 명령, 결과 재전송, 위치 접근을 모두 검사한다.
- SDK 요청 전 연결 끊김, 요청 직후 프로세스 종료, SDK 응답 유실을 분리한다. 불명확한 실행 결과를 성공으로 표시하지 않는다.
- 명령 처리 중 철회/종료가 발생해도 새 실행을 막는다. 이미 외부 SDK가 실행한 동작을 철회가 되돌린다고 주장하지 않는다.
- 서버 절전·DB 중지·네트워크 단절에서도 ‘갱신 중/마지막 갱신/확인 불가’가 구분된다.
- 서버 재기동 뒤 알림 예정 시간이 지난 경우 뒤늦은 중복 알림을 남발하지 않는다.
- 비용·ETA 제공자 장애 시 무효 값을 정상값처럼 반환하지 않는다.
- 만료·삭제 정책은 실제 PostgreSQL에서 검증한다. H2 테스트 통과만으로 PostgreSQL 호환성을 주장하지 않는다.

### 구현 완료 기준

테이블 생성 성공만으로 완료하지 않는다. DB 제약·서비스 권한·OpenAPI 응답·Android 모델이 일치하고, 두 앱의 화면 흐름이 실제 서버를 통해 이어져야 한다. 테스트 데이터와 실데이터, 데모 명령과 실제 차량 명령은 명확히 분리한다. 심사 전에는 무료 서비스의 절전·할당량·연결 조건과 공모전 시연 네트워크 조건을 재확인한다.

## 확정이 필요한 제품 결정

추천 기본안은 다음과 같고 아직 사용자 확정 전이다.

1. 사용자 식별: 폰은 복구 가능한 계정 로그인을 권장한다. Supabase Auth 채택 및 로그인 수단은 별도 결정한다. 로그인 없이 시작하려면 게스트 계정·전환·기기 분실 후 복구 한계를 다시 정의해야 한다.
2. 운전자: 여행당 활성 운전자 1명. 소유자도 운전자 역할이 없으면 차량 적용을 승인하지 못한다.
3. 위치: 동의한 여행의 최신 위치 공유만 기본 제공. 경로 이력과 자동 학습 동의는 분리한다.
4. 알림: 무료 서버의 예약 작업만 믿지 않고 폰 로컬 알림을 우선한다. 차후 푸시 보조 전달을 붙인다.
5. 실제 운전 적용: 운전자의 안전한 확인과 차량의 주행 제한을 모두 충족한 경우에만 SDK에 요청한다.

## 참고 근거

조회일: 2026-10-28. 제공자 요금·제한·SDK 상태는 변경될 수 있다. 문서 내 보관 기간·만료·폴링 주기는 제품 설계 제안이지 공모전 규정 또는 법정 기간이 아니다.

- 현재 개발본: https://github.com/yj901010/yeouro/pull/2
- UI 참고 개발본: https://github.com/yj901010/yeouro/pull/1
- Supabase 요금과 무료 제한: https://supabase.com/pricing
- PostgreSQL 연결 및 pooler: https://supabase.com/docs/guides/database/connecting-to-postgres
- Supabase Data API 권한 경계: https://supabase.com/docs/guides/api/securing-your-api
- Supabase JWT 검증: https://supabase.com/docs/guides/auth/jwts
- Render 무료 운영 제한: https://render.com/docs/free
- Pleos 보안과 개인정보: https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/security-privacy
- Pleos 네트워크 단절 대응: https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/platform-api-architecture
- Pleos 앱 보안: https://document.pleos.ai/docs/pleos-only/app-registration-guide/app-security-guide
- 외부 서버 활성화 안내는 플랫폼 심사 가이드이며 공모전 특별 승인과 구분: https://document.pleos.ai/docs/pleos-only/distribute/app-submission-guide
- 공모전 규정: https://appdevchallenge.auto-hmg.io/guide/rules

## API 설계 후속 점검

공개 배포 전에 계정 삭제 및 개인 기록 삭제 API를 추가 확정해야 한다. 제안 경로는 `DELETE /api/v2/me`(재인증 후 삭제 작업 접수, 202)와 `DELETE /api/v2/trips/{id}`(소유자의 종료된 일정 삭제, 204)다. 계정 삭제는 공유 철회·관련 세션 폐기를 우선하고 DB 정리 및 Auth 계정 삭제를 재시도 가능한 작업으로 진행한다. 일반 동행자 탈퇴는 다른 사람의 일정 전체 삭제가 아니다. 장기 기록 정책과 삭제 시 다른 참여자에게 남기는 정보 범위도 함께 검토한다.

`OUTCOME_UNKNOWN` 명령은 자동 재시도하지 않는다. 첫 구현에서는 후속 차량 적용을 보류하고 운전자에게 실제 내비 상태 확인을 요청한다. 이 상태를 풀어 새 명령을 허용하는 reconciliation API 및 증거 기준은 실제 SDK 콜백 검증 후 확정한다. 이를 구현하지 않은 상태에서 서버가 성공이나 미실행을 추정해 잠금을 해제하지 않는다.

## 이번 문서의 검증 범위

기존 코드·OpenAPI·migration을 읽고 신규 DB와 API의 명칭·역할·동의 scope·상태를 대조했다. 포함된 JSON 예시의 구문을 검사했다. 이 문서는 실행 가능한 DDL이나 완성된 OpenAPI 파일을 대신하지 않는다. Supabase 실 DB 제약 검증·외부 API 호출·인증 설정·신규 엔드포인트 테스트는 아직 하지 않았으며, 기존 코드와 공개 저장소도 이 문서 작성으로 변경하지 않았다.
