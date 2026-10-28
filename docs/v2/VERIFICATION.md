# V2 verification 2026-10-28

This records the final local verification of the backend-v2-design change. It does not replace the separate original APK build report in `docs/VERIFICATION.md`.

## Passed

- Java 21 / PostgreSQL 17.11: `mvn clean package`, **51 tests, zero failures/errors/skips**.
  - Legacy v1 H2 API: 17.
  - V2 real HTTP + PostgreSQL + locally signed RSA JWT/JWKS: 15.
  - PostgreSQL planning API: 5.
  - Pure planning calculators: 8.
  - Pairing isolation: 2.
  - Production profile boundaries: 2.
  - Test database destination guards: 2.
- Flyway V1–V5 applied to fresh PostgreSQL, not inferred from H2 compatibility.
- Kotlin JVM modules with both real local v1/v2 servers: **42 tests, zero failures/errors/skips** (domain18, v1 mock transport13, v1 realHTTP1, v2 mock transport9, v2 realHTTP1).
- The v2 native HTTP flow creates an account-authenticated trip, starts it, creates explicit sharing, invites/joins another phone identity, updates readiness, discovers a proposal, accepts it while preserving SDK_NOT_CONNECTED, ends the trip and denies former companion access. These are synthetic identities signed by a temporary local JWKS fixture, not real Supabase users.
- Existing UI-reference Node tests: **32 passed**.
- OpenAPI 3.0.3 structural validation, 36 controller operations, 64 schemas, 293 local references and 232 example occurrences checked. V1 contract unchanged.
- Whitespace check passed. Test keys, tool binaries, database files, logs and JARs are not committed.

Final tested backend JAR SHA256: `eb3fe054f3c947fc7da2e536f41dd95b67d190ebd48c71411abe00ab98d30c93`.

## Review regressions addressed

- No participant names/readiness from a previous sharing generation or a participant whose collaboration consent was withdrawn.
- Global sharing revoke removes nonowner membership; a new generation requires a fresh invitation and explicit consent. Personal withdrawal can recover its own consent version without exposing shared state.
- Driver proposal discovery is available through a bounded, scoped GET route. Snapshot provides own viewer membership/role/ownership rather than asking clients to infer it.
- Old-generation decision replay cannot bypass resource authorization. Proposal submission keys are generation-scoped: a conscious submission after fresh admission is a new proposal, not a replay from an old group. Clients must not silently resend stale offline actions.
- Retained or explicit expiry cannot precede a changed arrival deadline plus stay.
- Public installation labels cannot revoke an existing approved pairing.
- Missing lifecycle expectedVersion is rejected rather than defaulting to zero.
- Real APPLIED is unavailable; explicit simulation does not become actual navigation success.
- Test setup ignores production DATABASE_* variables and validates TEST_DATABASE_* loopback and `_test` database names before datasource initialization/Flyway.

## Reproduction

From repository root with a full JDK21, PostgreSQL17 tools and normal dependency access:

```sh
PG_BIN=/path/to/postgresql/bin scripts/run-postgres-tests.sh
```

For package + real Kotlin HTTP checks, package against the disposable test DB first, then invoke the harness (Python `cryptography` required):

```sh
PG_BIN=/path/to/postgresql/bin scripts/run-postgres-tests.sh -- sh ./mvnw clean package
PG_BIN=/path/to/postgresql/bin scripts/run-postgres-tests.sh -- \
  python3 /absolute/path/to/yeouro/scripts/smoke-native-v2.py
```

The script starts loopback-only, synthetic fixture servers and removes their temporary state when finished. Ordinary `gradlew -PjvmOnly=true test` without explicit live-server variables skips the two live HTTP tests; the above local harness ran both. GitHub's shared-Kotlin job uses that ordinary mode, while its backend job supplies a dedicated PostgreSQL service. Check the PR's checks for the remote commit's CI status.

## Not verified or not implemented

- No Supabase account/project connection, real auth-provider login, restricted production DB roles, network allowlist or external deployment.
- No production HTTPS ingress, load test, formal security audit, backup/restore rehearsal or account-deletion workflow. Before public use, configure provider grants, abuse limits, lifecycle/data deletion and operational monitoring.
- No native screen migration to v2 sign-in/membership state. The new phone repository is additive; existing UI remains on v1.
- No APK rebuild, native device/emulator UI inspection, accessibility review, actual Pleos SDK invocation or driving compliance test in this change.
- No real maps/route/parking provider, actual location streaming, OS local-alarm implementation, FCM, background delivery guarantee or automatic place learning.
- The backend stores reminder plans and client scheduling reports only; the phone notification implementation remains separate work.
