package ai.yeouro.trip;

import ai.yeouro.api.ApiModels.*;
import ai.yeouro.integration.NavigationGateway;
import ai.yeouro.location.LocationPolicy;
import ai.yeouro.sharing.ParticipantRepository;
import java.time.Clock;
import org.springframework.stereotype.Component;

@Component
public class TripViews {
    private final ParticipantRepository participants;
    private final ProposalRepository proposals;
    private final LocationPolicy locations;
    private final NavigationGateway navigation;
    private final Clock clock;
    public TripViews(ParticipantRepository participants, ProposalRepository proposals, LocationPolicy locations,
                     NavigationGateway navigation, Clock clock) {
        this.participants = participants; this.proposals = proposals; this.locations = locations;
        this.navigation = navigation; this.clock = clock;
    }
    public Trip snapshot(TripEntity trip) {
        var people = participants.findByTripIdOrderById(trip.id).stream().filter(p -> p.active)
            .map(p -> new Participant(p.id, p.displayName, p.role, p.ready)).toList();
        var places = proposals.findByTripIdOrderById(trip.id).stream()
            .map(p -> new Proposal(p.id, p.participantId, p.place.dto(), p.status, p.revision, p.resultReason)).toList();
        Location location = trip.sharingEnabled && locations.fresh(trip.location, clock.instant()) ? trip.location.dto() : null;
        return new Trip(trip.id, trip.destination.dto(), trip.status, trip.sharingEnabled, trip.revision,
                        trip.expiresAt, people, places, location, navigation.status());
    }
}
