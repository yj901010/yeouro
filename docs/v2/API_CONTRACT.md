# V2 implementation contract

`contracts/openapi-v2.yaml` is the wire contract for the implemented `/api/v2`
controllers. `contracts/openapi.yaml` remains the independent v1 contract. The
approved `docs/design/BACKEND_V2.md` is a target design, not a list of shipped
endpoints; where it differs, use the v2 implementation contract and the scope
notes below. This is local implementation work, not evidence of a deployed or
externally integrated service.

## Security and privacy boundaries

- Phone account JWTs, one-trip opaque vehicle credentials, and pairing polling
  secrets are different credentials. Do not reuse a v1 capability as a phone
  identity, give a vehicle the phone refresh token, or put secrets in URLs.
- OWNER controls the itinerary; DRIVER is the current travel role. An owner who
  is a companion does not acquire driver approval permissions.
- Sharing generation and individual consent scope are checked independently of
  JWT validity. Restarting sharing does not reactivate old consents, invitations,
  or vehicle credentials. OS location permission is not service sharing consent.
- `Cache-Control: no-store` applies to API responses. Exact locations, bearer
  values, invitation codes, pairing challenges and polling secrets must not be
  recorded in routine client logs or analytics. OpenAPI examples are synthetic.
- The latest position is not a movement-history API. Revocation blocks future
  server access; it cannot erase screenshots or data already received offline.

## Integration limits

Approval of a proposal is not proof of navigation application. The unavailable
navigation adapter cannot yield a real `APPLIED` success. A `SIMULATED` source or
`DEMO` mode must stay visibly labelled through client presentation. Missing ETA,
price, occupancy, or anchor timestamps must not be rendered as zero or success.
A saved reminder and a client `SCHEDULED` report do not prove that the OS showed
an alert. No background delivery, exact-once SDK execution, or automatic learning
is promised by this contract.

## Deliberately not advertised

Account deletion, ownership transfer, driver reassignment, credential refresh,
SDK outcome reconciliation, real routing/parking providers, push delivery, SSE,
reignition recovery, and automatic learned place/stay data are not implied by the
approved design and must not be implemented by clients against invented routes.
Only paths present in the v2 OpenAPI are supported by this implementation.

## Implemented wire details and design differences

- V2 is opt-in (`yeouro.v2.enabled`); configured phone issuer/JWKS/audience are
  required. No fake identity fallback, login-provider choice or external account
  configuration is delivered by this contract.
- Pairing polling/claim uses `X-Pairing-Poll-Secret`, **not** an Authorization
  bearer. Subsequent vehicle API calls use `Authorization: Bearer <credential>`.
  `displayLabel` is accepted by pairing start but not persisted or returned.
- `POST /trips` and `PATCH /trips/{tripId}` require destination and timeZone.
  PATCH replaces the full plan, not JSON Merge Patch: omitted optional fields
  clear/reset, except expiresAt retains its previous value. ownerRole is allowed
  on create only. There is no role-transfer API.
- Trip list is newest 100 active memberships, without cursor pagination. A join
  returns `{tripId, participantId, role, sharingGeneration}`, not a v1 session or
  a newly issued phone token. Account display name supplies membership name.
- Snapshot includes stops but does not include proposal history or startsAt.
  Sharing is `{sessionId,generation,expiresAt}` or null. Participant projection
  uses camelCase and includes consentVersion. Sensitive shared fields disappear
  from terminal owner reads; other members cannot use terminal access.
- Proposal submission currently permits any phone collaborator with live
  collaboration consent, not only companions. comparisonId linking is not
  implemented. STOP/PARKING approval does not silently become destination change:
  its command fails `UNSUPPORTED_COMMAND`.
- DEMO comparison and navigation execution require the server demo flag. Real
  routing requests return `503 ROUTING_UNAVAILABLE`. Real navigation `APPLIED`
  reports return `400 SDK_NOT_CONNECTED`; there is no real adapter evidence DTO.
  DEMO does not change the actual itinerary destination.
- Comparison creation returns the snapshot directly (`comparisonId`), while GET
  returns `{snapshot,stalePlan,expired}`. An expired comparison remains explicitly
  flagged, not silently refreshed. Stops and destination come from the server
  plan, not a second client-supplied plan in the comparison request.
- Parking and phase confirmation accept only `source=USER_CONFIRMED` and
  `userConfirmed=true`. SDK_EVENT/SIMULATED exist in Java's shared source enum but
  are rejected for these operations. PARKED uses parking-confirmations, not
  phase-events. Progress observations may be at most one day old / 30s future;
  location/comparison origins have the stricter two-minute freshness rule.
- Reminder writes use plan If-Match. An absent reminder is returned as DISABLED
  version 0 with null remindAt and no detailed plan fields. Only the current
  participant's client scheduling reports are returned.
- Errors are `{code,message}`. The design's richer error fields (requestId,
  currentVersion, retryable) and a uniform Retry-After guarantee are not present.
  Pairing budgets are development safety bounds, not internet-grade abuse control.

## Version and retry matrix

| Operation | Concurrency/retry input |
| --- | --- |
| Trip PATCH; stops PUT; reminder-plan PUT | `If-Match: "plan-N"` with current planRevision; missing 428, mismatch 412 |
| Trip start/end/cancel | body expectedVersion = trip.version |
| Parking confirmation / phase event | body expectedVersion = trip.revision |
| Proposal decision | body expectedVersion = proposal.version |
| Command claim/result | body expectedVersion = command.version |
| Own consent PUT | body expectedVersion = participant.consentVersion (initially 0) |
| Reminder client-status PUT | body planVersion = reminder.version |
| Latest location PUT | observationId + normalized payload, sharingGeneration; no Idempotency-Key |
| All implemented POST routes | Idempotency-Key, 1–128 chars, principal + route scope, 24h ledger |

Exact POST replay returns the stored result only after access checks; a changed
payload with the same key returns `409 IDEMPOTENCY_KEY_REUSED`. Invite issuance,
pairing start and credential claim instead return `409 SECRET_ALREADY_ISSUED` on
replay: raw credentials are not saved for replay. New pairing is needed after a
lost claim response. Trip-end revocation means a vehicle cannot replay its old
credential afterward. These are HTTP retry rules, not exactly-once SDK execution.

Java primitive request fields sometimes default to zero/false when omitted; the
OpenAPI notes those cases instead of claiming validation that does not exist.
Clients should still send intentional coordinates, booleans and version values.

## Contract verification

The contract is checked with `openapi-spec-validator` 0.7.2 and
`openapi-schema-validator` 0.6.3: YAML parsing, OpenAPI 3.0.3 structure, local
references and request/response example validation. Validators were installed
only in a temporary tooling directory, not added to server dependencies. The
original v1 contract SHA-256 is unchanged. Runtime/PostgreSQL, Android and SDK
verification are separate checks; schema validation alone does not prove them.

Trip start requires PLANNED. End requires ACTIVE; a still-planned trip must use
cancel. Ending is distinct from cancelling a plan.

## Foreground proposal discovery and account recovery

`GET /trips/{tripId}/proposals` closes the polling/reconnect discovery loop without
placing proposal history inside the trip snapshot. It returns
`{proposals,nextCursor,serverTime}`; each proposal includes its separate nullable
navigationCommand. Query limit defaults to 50 (1–100), cursor is the prior page's
nextCursor UUID, and optional status filters decision state, not command outcome.
Results are newest first and restricted to the current sharing generation.
Polling clients fetch the newest page without a cursor, then follow nextCursor
for older pages. Both phone readers and vehicle TRIP_READ require current
collaboration consent; owner status alone does not bypass that check. Cross-trip
or old-generation cursors fail rather than exposing historic shared proposals.

Trip snapshots now include `viewer: {participantId,role,isOwner,clientType}`.
Clients can recover their own membership after login without guessing by display
name or treating ownership as driver authority. A vehicle viewer is not an owner.

Expiry validation covers `max(now, startsAt, deadlineAt) + plannedStayMinutes`,
including a retained expiresAt during PATCH. Default expiry adds one day to that
covered window; explicit expiry must be future and within 30 days. An edited plan
that outgrows retained expiry fails `INVALID_EXPIRY` instead of silently extending
existing sharing credentials. Reminder plans additionally expose nullable
anchorConfirmedAt when a saved plan exists, distinguishing a real confirmed
anchor from a desired future time.

## Consent recovery versus sharing restart

`GET /trips/{tripId}/consents/me` returns only the caller's
`{sessionId,generation,version,scopes}`; scopes includes all three consent booleans.
It is available to an active phone member after personal collaboration revoke,
so a lost PUT response does not strand the caller without the current version.
This GET does not grant access to other people's shared data. Consent PUT still
returns the changed scopes only, not the full active set.

Personal consent revoke retains membership within the same generation; revoked
vehicle credentials are never resurrected by regranting. **Global** sharing
revoke instead marks nonowner memberships LEFT and increments consent versions.
After a restart, nonowners need a fresh invitation and join with fresh consent;
they cannot directly regrant against their old membership. Owner membership
remains active. This distinction also applies when end/cancel performs global
sharing cleanup.
