package ai.yeouro.trip;

import ai.yeouro.api.*;
import ai.yeouro.api.ApiModels.*;
import ai.yeouro.sharing.*;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Every aggregate operation acquires the same database row lock before authorization/state checks. */
@Component
public class TripAccess {
    private final TripRepository trips;
    private final TokenService tokens;
    private final Clock clock;
    public TripAccess(TripRepository trips, TokenService tokens, Clock clock) {
        this.trips = trips; this.tokens = tokens; this.clock = clock;
    }
    public record Authorized(TripEntity trip, AccessToken token) {}
    public Authorized lock(UUID id, String bearer, Role... roles) {
        TripEntity trip = trips.lockById(id).orElseThrow(ApiException::unauthorized);
        return new Authorized(trip, tokens.authorize(trip, bearer, roles));
    }
    public void requireActive(TripEntity trip) {
        if (!trip.expiresAt.isAfter(clock.instant())) throw ApiException.conflict("TRIP_EXPIRED", "This trip has expired.");
        if (trip.status != TripStatus.ACTIVE) throw ApiException.conflict("TRIP_ENDED", "This trip has ended.");
    }
    public void requireSharing(TripEntity trip) {
        requireActive(trip);
        if (!trip.sharingEnabled) throw ApiException.conflict("CONSENT_REQUIRED", "Enable sharing with explicit consent first.");
    }
}
