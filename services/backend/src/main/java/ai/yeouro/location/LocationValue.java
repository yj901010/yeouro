package ai.yeouro.location;

import ai.yeouro.api.ApiModels.*;
import jakarta.persistence.*;
import java.time.Instant;

@Embeddable
public class LocationValue {
    public Double latitude;
    public Double longitude;
    public Double accuracyMeters;
    public Instant observedAt;
    @Enumerated(EnumType.STRING) @Column(length=20) public Source source;
    protected LocationValue() {}
    public LocationValue(Location value) {
        latitude = value.latitude(); longitude = value.longitude(); accuracyMeters = value.accuracyMeters();
        observedAt = value.observedAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS); source = value.source();
    }
    public Location dto() { return new Location(latitude, longitude, accuracyMeters, observedAt, source); }
}
