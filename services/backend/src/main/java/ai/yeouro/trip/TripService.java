package ai.yeouro.trip;

import ai.yeouro.api.ApiModels.*;
import ai.yeouro.location.*;
import ai.yeouro.sharing.*;
import java.time.*;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Transactional
public class TripService {
    private final TripRepository trips;
    private final TripAccess access;
    private final TripViews views;
    private final TokenService tokens;
    private final SharingService sharing;
    private final LocationPolicy locations;
    private final Clock clock;
    public TripService(TripRepository trips, TripAccess access, TripViews views, TokenService tokens,
                       SharingService sharing, LocationPolicy locations, Clock clock) {
        this.trips = trips; this.access = access; this.views = views; this.tokens = tokens;
        this.sharing = sharing; this.locations = locations; this.clock = clock;
    }
    public SessionResponse create(CreateTripRequest request) {
        TripEntity trip = trips.save(new TripEntity(new PlaceValue(request.destination()), clock.instant().plus(Duration.ofHours(24))));
        String token = tokens.issue(trip, null, Role.OWNER);
        return new SessionResponse(views.snapshot(trip), token, Role.OWNER, null, trip.expiresAt);
    }
    public Trip get(UUID id, String bearer) { return views.snapshot(access.lock(id, bearer).trip()); }
    public Trip location(UUID id, String bearer, Location location) {
        TripEntity trip = access.lock(id, bearer, Role.OWNER, Role.DRIVER).trip();
        access.requireSharing(trip);
        locations.validate(location, trip.location, clock.instant());
        trip.location = new LocationValue(location); trip.changed();
        return views.snapshot(trip);
    }
    public Trip end(UUID id, String bearer) {
        TripEntity trip = access.lock(id, bearer, Role.OWNER, Role.DRIVER).trip();
        if (trip.status != TripStatus.ENDED) {
            sharing.revokeLocked(trip); trip.status = TripStatus.ENDED; trip.changed();
        }
        return views.snapshot(trip);
    }
}
