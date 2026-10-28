# First app-only development slice — verification

Date: 2026-10-28. This document distinguishes source, build, HTTP integration and device execution.

## Verified backend

- Java 21 / Spring Boot backend packaged into a runnable JAR.
- Checked-in Maven wrapper: 17 integration tests, zero failures/errors.
- Real local HTTP smoke: 17 checks via `scripts/smoke-local.py`, including scoped auth, readiness, proposal, explicit simulated application/location, sharing revocation and trip completion.
- File-backed H2 restart check: trip, hashed owner token and hashed unused invite survived restart; invite redeemed afterward and session ended.
- PostgreSQL profile and migration are supplied but were not tested against a live PostgreSQL instance.

## Browser reference

- Original prototype preserved under `prototype/`.
- 32 Node tests pass after relocation.
- Live private prototype remains a separate design reference; native apps do not embed it.

## Android

- Both native debug APKs compiled successfully and their APK signatures were verified.
- Android Lint: zero issues in both applications.
- Release build without a configured HTTPS API endpoint was rejected as intended.
- Only INTERNET platform permission is requested; AndroidX adds an app-internal signature receiver permission.
- Shared Kotlin/client suite: 32 tests passed, zero skipped locally (18 domain, 13 HTTP mock, 1 live-backend client test).
- The actual Kotlin HTTP client completed owner/driver/companion invite, readiness, proposal, explicit DEMO_APPLIED and SIMULATED location, then verified revocation returning 401.
- Regression tests cover 401 snapshot clearing, late old polling responses and disabled automatic POST retries.
- These are compilation and JVM/client integration results, not emulator, physical-phone or Pleos execution.

## Review findings addressed

- A confirmed HTTP 401 now clears sensitive client session and snapshot state instead of showing revoked data as merely offline.
- Automatic OkHttp connection retries are disabled for one-time create/join credentials.
- Old refresh responses are guarded by session identity; leaving a session cancels pending concrete HTTP operations.
- Real `APPLIED` is rejected while the vehicle integration is unavailable; `DEMO_APPLIED` is a separate state and does not claim a vehicle route changed.

## Explicitly not verified / not included

- Pleos SDK execution, CarUxRestrictions integration, real vehicle control or real location capture.
- Android emulator UI execution, physical-device execution or automotive compatibility certification.
- Production accounts, internet deployment, TLS endpoint, real map/routing/parking providers, Gleo, push notifications or background delivery.
- Exact display legibility on vehicle hardware or exhaustive accessibility validation.
- Full planned MVP scope: deadline/parking/learning/comfort features remain subsequent implementation work.

Test data is synthetic. Test servers are stopped after checks. No real personal location or vehicle credentials are used.

## APK SHA-256

- Vehicle debug: `360c7f415068b12b4fff243db3298ef79de617f4605be23c9fb0be12c25ef831`
- Phone debug: `4d1cd7daacea798dc90cc8d8d3d5b1e9e5c751cec1f6cea53069bdf892f436ea`

Debug APKs require the documented development backend; they are not an already-hosted consumer service.
