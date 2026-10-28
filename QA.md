# Prototype verification

This is a browser-only UI prototype. No Kotlin APK, Pleos emulator, real vehicle, location, microphone, push service, or cloud API integration was executed.

## Automated checks

Run `npm test` (Node built-in test runner) and JavaScript syntax checks. Domain/state/render tests cover time calculation, optional deadlines, late arrivals, route-change confirmation, cancellation, consent revocation, sharing termination, driving restrictions, and view-size state preservation.

## Interactive browser checks

Verified in the cloud Chromium browser on 2026-10-28:

- Home → example hospital → plan → start simulated trip.
- Driving mode shows summary and blocks detailed settings; returning to parked restores detail access.
- Sharing disabled until explicit simulated-trip consent.
- Companion view displays the schematic car/pickup position and synthetic update time.
- Companion readiness and proposed pickup point reach driver review.
- Cancelling route change preserves original pickup point.
- Pickup completion removes the companion's shared trip display.
- Parking B requires confirmation; after confirmation ETA changes from 14:48 to 14:53 and walking time from 3 to 8 minutes.
- Arrival requires separate parking confirmation.
- Compact-window and night-theme changes preserve active trip state.

## Limits

- Browser state is in-memory and intentionally resets on refresh. Multi-device synchronization is not implemented.
- Real smartphone device testing, physical display sizing, full accessibility audit, and OEM certification were not performed.
- Map, ETA, occupancy, notifications, vehicle events, and comfort settings are illustrative/simulated.
- A web demo's parked/driving toggle is not a replacement for native `CarUxRestrictions`.
