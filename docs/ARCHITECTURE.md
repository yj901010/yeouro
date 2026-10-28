# App-only MVP architecture

## Accepted product shape

- One Kotlin Android vehicle app for Pleos Connect.
- One Kotlin Android phone app shared by the driver and companion, with roles scoped to each trip.
- One Java/Spring Boot backend. No product web client.
- `prototype/` is the preserved browser UX reference, not a production client.

## Boundaries

- Android UI uses ViewModels and lifecycle-aware state. Vehicle and phone applications are separate installable packages.
- Share API contracts and pure domain types where useful, not vehicle-specific permissions or platform code.
- The backend remains one deployable modular monolith. Trip, sharing, location and navigation-application states have distinct responsibilities.
- SDK/vendor calls sit behind adapters. Missing capabilities are reported explicitly; no silent fake-data fallback in a real integration mode.
- Native vehicle restriction policy must ultimately use `CarUxRestrictions`; a debug/demo switch does not establish vehicle compliance.

## First implementation slice

Create a trip, explicitly consent to sharing, issue an expiring invite, join as a companion, update readiness, submit and review a place proposal, report navigation-application outcome, send an explicitly simulated location, and end/revoke the shared session.

The first slice uses authenticated foreground HTTP polling. SSE remains a planned transport upgrade, not an implemented claim. Shared state is persisted by the backend; reconnecting clients fetch the authoritative snapshot.

## Critical state distinctions

- Vehicle arrival and person arrival after parking/walking are separate.
- Proposal acceptance is not a successful navigation change. SDK acknowledgement/result must determine application outcome.
- Navigation arrival is not parking confirmation.
- Sharing consent, invitation expiry, revocation and trip completion are distinct transitions.
- Location source, observation time and accuracy are recorded. Simulated observations remain visibly simulated.

## Explicitly outside the first slice

Real Pleos control, real continuous vehicle GPS, Gleo, external map/routing/parking feeds, production push delivery, automatic next-ignition restoration and learned arrival-cause classification are not established by this implementation.

No API key, private user data, signing secret or SDK credential belongs in the repository. Real location sharing must be scoped and revocable; publicly reachable production deployment requires a further authentication, abuse-prevention and security review.

## Official references

- https://document.pleos.ai/docs/pleos-only/getting-started
- https://document.pleos.ai/docs/pleos-only/create-my-project
- https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driver-distraction-guideline
- https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-structure-screen-model
- https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/event-listeners
