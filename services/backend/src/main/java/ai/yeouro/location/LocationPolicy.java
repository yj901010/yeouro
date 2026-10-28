package ai.yeouro.location;

import ai.yeouro.api.ApiException;
import ai.yeouro.api.ApiModels.Location;
import java.time.*;
import org.springframework.stereotype.Component;

@Component
public class LocationPolicy {
    public static final Duration MAX_AGE = Duration.ofMinutes(2);
    public void validate(Location next, LocationValue previous, Instant now) {
        if (!Double.isFinite(next.latitude()) || !Double.isFinite(next.longitude()) || !Double.isFinite(next.accuracyMeters()))
            throw ApiException.invalid("INVALID_COORDINATES", "Location numbers must be finite.");
        if (next.observedAt().isBefore(now.minus(MAX_AGE)) || next.observedAt().isAfter(now.plusSeconds(30)))
            throw ApiException.conflict("STALE_LOCATION", "Location is too old or ahead of server time.");
        if (previous != null && !next.observedAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).isAfter(previous.observedAt))
            throw ApiException.conflict("STALE_LOCATION", "Location must be newer than the last observation.");
    }
    public boolean fresh(LocationValue value, Instant now) {
        return value != null && !value.observedAt.isBefore(now.minus(MAX_AGE));
    }
}
