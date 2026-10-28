# 여유로 backend v2

Java 21 / Spring Boot modular monolith with real PostgreSQL migrations and a separate v2 contract. This is implementation and local validation, not a deployed Supabase service, configured sign-in UI, or connected Pleos runtime.

## Current boundaries

- `contracts/openapi-v2.yaml` is the implemented v2 contract; `docs/design/BACKEND_V2.md` is the broader approved design. Actual differences and postponed endpoints are listed in `docs/v2/API_CONTRACT.md`.
- Phone requests validate configured JWT signature, issuer, expiry and audience. RS256/ES256 are allowed; missing issuer/JWKS returns `AUTH_NOT_CONFIGURED`. No anonymous production identity or trusted user-id header exists.
- Supabase Auth is an integration candidate, not a configured external account. One trusted issuer is supported. App user UUIDs map to validated subjects without creating or changing Supabase's managed `auth` schema.
- Ownership and DRIVER/COMPANION membership are separate. Sharing publication/view/collaboration consents and vehicle scopes are checked for each request.
- Vehicle pairing uses a separate poll secret and one-time scoped credential (maximum 12 hours, also bounded by trip/session expiry). Installation IDs are not ownership proof and cannot revoke other pending pairings.
- Locations are latest-point only. Revocation and expiry deny access synchronously; minute-based cleanup is supplemental.
- Real navigation always reports unavailable until the actual adapter is verified. Comparison fixtures and DEMO navigation require explicit `yeouro.v2.demo-enabled=true`, default false. REAL routing returns unavailable, not synthetic success.
- Phase/parking confirmation and return-reminder plans are persisted. A plan or client `SCHEDULED` report is not proof an OS notification appeared. No background push scheduler is promised on sleeping free hosting.

## Local PostgreSQL validation

Use a full JDK 21 and PostgreSQL 17 binaries. From repository root:

```sh
PG_BIN=/path/to/postgresql/bin scripts/run-postgres-tests.sh
```

The script creates a fresh, loopback-only, trust-authenticated disposable cluster, runs Maven tests, then stops and removes it. It is only for synthetic local tests, never production. Alternatively supply the dedicated local test DB using `TEST_DATABASE_URL`, `TEST_DATABASE_USERNAME`, and `TEST_DATABASE_PASSWORD` (loopback URL with an explicit port and a database name ending `_test`; production `DATABASE_*` values are ignored) and run `sh ./mvnw test` from this directory. Integration tests require PostgreSQL and must not silently skip. Never point tests at a live service database: fixtures include destructive cleanup. CI provides a dedicated PostgreSQL database. Legacy v1 tests still use H2; passing H2 is not the v2 verification gate.

For manually running the v2 API against an existing local PostgreSQL DB:

```sh
export DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/yeouro
export DATABASE_USERNAME=yeouro_app
# Supply DATABASE_PASSWORD through your private environment, not source control.
export PHONE_JWT_ISSUER=https://YOUR_PROJECT.supabase.co/auth/v1
export PHONE_JWT_JWKS_URI=https://YOUR_PROJECT.supabase.co/auth/v1/.well-known/jwks.json
export PHONE_JWT_AUDIENCE=authenticated
sh ./mvnw spring-boot:run -Dspring-boot.run.profiles=v2
```

These URLs are templates, not deployed configuration. Use your provider's verified project settings and a real phone login flow. Never enter a service-role key or database password as the phone access token. The server stays loopback-bound by default. Account connection, credential grants, external ingress and deployment are separate setup steps.

## Before public deployment

Keep business tables in the non-exposed `app` schema, disable unused Supabase Data API access, and audit grants. Separate migration DDL permissions from runtime DML permissions. JDBC does not automatically inherit a phone JWT's `auth.uid()` context. Test restrictive roles, TLS and connection pool settings against the actual provider, not just local PostgreSQL.

Use HTTPS, external abuse/IP rate limits, a private secret store, bounded pools, log redaction, and tested data deletion. Built-in pairing caps are not a public-internet abuse defense. Account deletion, role transfer and user-operated command reconciliation remain postponed; unresolved SDK effects block further commands instead of blindly retrying them.

For free Supabase, prepare regular exported backups, an off-site copy and a restore rehearsal; do not assume paid-plan automatic daily restores. Keep only synthetic demo seed data in source. Never publish real locations, credentials or database dumps. Render free hosting can sleep or suspend on usage limits, including unusually high external DB/API traffic. Rehearse cold starts and degraded UI; do not promise constant availability.

Official operational references: [Supabase backups](https://supabase.com/docs/guides/platform/backups), [Data API security](https://supabase.com/docs/guides/api/securing-your-api), [Render free limits](https://render.com/docs/free).

# Legacy v1 local demo reference

Java 21 / Spring Boot 4.0.8 modular monolith. This is a **working local REST service**, not a deployed service or a connected Pleos integration. The original browser prototype is separate.

## Run and test

Install a full JDK 21 (a JRE alone is insufficient). The checked-in official Maven Wrapper downloads Maven 3.9.11 from Maven Central; no global Maven installation is required.

```sh
cd services/backend
./mvnw -Dtest=TripApiTest test
# Full test/package requires the isolated PostgreSQL test setup above.
./mvnw package
java -jar target/backend-0.1.0-SNAPSHOT.jar
# GET http://127.0.0.1:8080/api/v1/health -> {"status":"UP"}
```

The default server binds **127.0.0.1**. H2 stores state in `data/yeouro` under the backend working directory (gitignored). This allows restart persistence, not encrypted storage. Use only synthetic development data. Stop the server before removing the `data/` directory to reset it. Tests use isolated in-memory H2 and a controllable clock.

For a trusted Android emulator/dev LAN test, explicitly set `SERVER_ADDRESS=0.0.0.0`; this exposes the service to that network. Do not expose this local-development profile to the internet. Use `adb reverse tcp:8080 tcp:8080` when appropriate rather than exposing an interface unnecessarily. Native release builds must use HTTPS.

`application-prod.yaml` now enables v2 and disables this legacy v1 HTTP surface. It selects PostgreSQL using `DATABASE_URL` (JDBC URL), `DATABASE_USERNAME`, and `DATABASE_PASSWORD`. Local PostgreSQL migrations are verified; actual Supabase deployment and restrictive runtime roles are not. TLS termination, managed credentials, ingress limits, account/device authentication, observability/redaction, backups, operational deletion and a real PostgreSQL test are required before external deployment.

## Authoritative contract

[`../../contracts/openapi.yaml`](../../contracts/openapi.yaml) specifies request/response fields, enums, paths, and status behavior. All API routes are under `/api/v1`. All responses are `Cache-Control: no-store`. `GET /health` is minimal liveness, not database readiness.

| Action | Authorization | Result |
| --- | --- | --- |
| Create trip | Public local MVP entry point | 24-hour trip and one-time-returned OWNER token |
| Create invite | OWNER + explicit consent | Single-use 15-minute code; inviter selects DRIVER or COMPANION |
| Join invite | Code + explicit consent | Trip-scoped token with the stored invite role; no client-selected privilege |
| Get trip | OWNER, DRIVER, COMPANION | Snapshot only; never credentials |
| Set readiness / propose place | COMPANION | Persisted participant intent |
| Accept/dismiss proposal | OWNER or DRIVER | `APPLY_REQUESTED` or `DISMISSED`, not a navigation success |
| Report navigation result | OWNER or DRIVER | `FAILED` or explicit `DEMO_APPLIED`; `APPLIED` rejected until real integration exists |
| Upload location | OWNER or DRIVER, sharing active | Fresh timestamped observation with PHONE / VEHICLE / SIMULATED source |
| Revoke sharing | OWNER | Revokes participant capabilities/invites, clears location, cancels pending commands |
| End trip | OWNER or DRIVER | Ends trip and performs the same revocation atomically |

DRIVER is an explicitly invited role, not a companion self-upgrade. OWNER alone can invite and revoke sharing. Ending from a DRIVER revokes that driver's token too; only OWNER can subsequently read the ended trip or safely repeat `end` until owner-token expiry.

### Integration is honestly unavailable

`navigation` always reports `PLEOS / UNAVAILABLE / SDK_NOT_CONNECTED` in this slice. No real SDK or external location/navigation API is called. Driver approval changes the proposal to `APPLY_REQUESTED`; a device adapter must separately report its result. `DEMO_APPLIED` is a test/demo outcome and never changes the real trip destination or claims navigation success. `APPLIED` returns `409 NAVIGATION_UNAVAILABLE` until the integration boundary is implemented and verified. Only one application request may be pending per trip.

### Foreground synchronization

Authenticated **foreground polling** (recommended 5 seconds) is the first-stage transport. There is no SSE, WebSocket, push, background worker, delivery guarantee, account login, or device pairing discovery yet. The client must stop polling in the background and discard participant-visible cached data upon 401/revocation. SSE is a later enhancement, not an implemented capability.

## Privacy, expiry, and concurrency

- Tokens are 256-bit cryptographically random bearer capabilities. Invites have 144 bits of randomness. Only SHA-256 hashes are persisted; raw secrets are returned once, never placed in URL parameters, logged by application code, or included in trip snapshots.
- Keep credentials in memory for this slice. If persistence is added, use OS-backed encrypted storage. Losing a capability cannot be recovered through this API; there is no account recovery or token refresh.
- Sharing needs consent on both the owner's invite action and the participant's join action. It is scoped to one trip and cannot be reused for a future trip. Re-enabling sharing cannot reactivate revoked tokens/invites.
- Location writes require active sharing. Observations older than two minutes, over 30 seconds in the future, or not newer than the prior observation are rejected. Old location is omitted from snapshots after two minutes. Observation timestamps are normalized to millisecond precision for consistent database ordering. Coordinates must be finite and in range; place coordinates are optional but must be supplied as a pair.
- Revoke/end clear the stored location immediately and cancel both `PENDING` and `APPLY_REQUESTED` commands. Late callbacks cannot revive them. Owner inspection remains available until expiry; participant/driver access does not.
- Trip/token lifetime is 24 hours from creation. A scheduled sweep deletes expired trips and all child records within approximately one minute while the server is running. Expired tokens fail immediately even before the sweep. Destination, display names, and proposal history remain until this trip expiry, including after early end; this is an explicit local-MVP retention limit, not a production retention policy.
- Every aggregate operation uses a database row lock; token authorization and state transitions occur after acquiring it. Invite redemption is single-use even under concurrent requests. Version counters are API revisions, separate from JPA optimistic locking fields.
- Proposal `requestId` deduplicates exact participant requests; changed content with the same key returns `REQUEST_ID_REUSED`. Readiness PUT, identical decision replay, identical result replay, OWNER end, and revoke are idempotent. Decision replay uses the original `expectedVersion`; conflicting versions/actions fail closed.
- Create-trip, create-invite, and join return credentials once and are **not automatically retryable** after an uncertain response. A lost join response consumes its invite; obtain a new invite. No raw-token replay ledger is kept. This is a deliberate first-slice limitation.
- A trip permits at most 32 invitations, 8 participants over its lifetime, and 100 proposals. These bounds are not internet-grade abuse/rate limiting. No production deployment is authorized or included.

## Code boundaries

```text
api/          validated wire records, REST mapping, redacted errors, privacy headers
trip/         aggregate coordination, proposal state machine, snapshots, expiry cleanup
sharing/      scoped tokens, invite redemption, participant readiness, revocation
location/     observation freshness/range policy and persistence value
integration/  NavigationGateway and explicit unavailable implementation
```

Persistence entities never cross the REST boundary. `TripAccess` serializes transitions on the trip row; `TripViews` assembles credential-free snapshots. `SharingService` owns consent and capability revocation; `ProposalService` owns proposal/result transitions. Small DTO records are kept together in `ApiModels` to make contract drift visible.

## Validation coverage

`TripApiTest` uses the real Spring MVC, validation, service, Flyway and JPA stack. It covers authorization/scope, both consent checks, one-time/expired invites, forbidden privilege escalation, readiness, proposal deduplication, stale decisions, approval/result separation, failed/demo results, unavailable real navigation, stale/out-of-order location, revocation, end/late callbacks, token expiry and cascading deletion, and concurrent invitation redemption, duplicate proposal races, and end/readiness races. All destinations/names/coordinates are synthetic fixtures.

Live-HTTP and native-client smoke checks are separate from this suite. A passing in-memory H2 integration suite does not establish PostgreSQL, real devices, background delivery, safety certification, or real Pleos SDK behavior.

## Dependency provenance

- [Spring Boot 4.0.8 official release](https://spring.io/blog/2026/08/20/spring-boot-4-0-8-available-now/) and the matching Maven Central BOM pin framework/transitive dependency versions.
- [Apache Maven 3.9.11 release notes](https://maven.apache.org/docs/3.9.11/release-notes.html).
- Maven Wrapper 3.3.4 `only-script` files were generated by the official wrapper plugin. No downloaded JDK, runtime proxy settings, dependency cache, SDK binaries, or build outputs belong in this repository.
