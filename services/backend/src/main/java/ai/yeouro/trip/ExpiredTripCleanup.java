package ai.yeouro.trip;

import java.time.Clock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ExpiredTripCleanup {
    private final TripRepository trips;
    private final Clock clock;
    public ExpiredTripCleanup(TripRepository trips, Clock clock) { this.trips = trips; this.clock = clock; }
    @Scheduled(fixedDelayString="${yeouro.cleanup-delay-ms:60000}") @Transactional
    public void purge() { trips.deleteExpired(clock.instant()); }
}
