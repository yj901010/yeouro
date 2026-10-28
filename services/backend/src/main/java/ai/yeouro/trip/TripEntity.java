package ai.yeouro.trip;

import ai.yeouro.api.ApiModels.TripStatus;
import ai.yeouro.location.LocationValue;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="trips")
public class TripEntity {
    @Id public UUID id;
    @Version public long lockVersion;
    public long revision;
    @Embedded @AttributeOverrides({
        @AttributeOverride(name="name",column=@Column(name="destination_name",nullable=false,length=120)),
        @AttributeOverride(name="latitude",column=@Column(name="destination_latitude")),
        @AttributeOverride(name="longitude",column=@Column(name="destination_longitude"))})
    public PlaceValue destination;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) public TripStatus status;
    public boolean sharingEnabled;
    @Column(nullable=false) public Instant expiresAt;
    @Embedded @AttributeOverrides({
        @AttributeOverride(name="latitude",column=@Column(name="location_latitude")),
        @AttributeOverride(name="longitude",column=@Column(name="location_longitude")),
        @AttributeOverride(name="accuracyMeters",column=@Column(name="location_accuracy_meters")),
        @AttributeOverride(name="observedAt",column=@Column(name="location_observed_at")),
        @AttributeOverride(name="source",column=@Column(name="location_source",length=20))})
    public LocationValue location;
    protected TripEntity() {}
    public TripEntity(PlaceValue destination, Instant expiresAt) {
        this.id = UUID.randomUUID(); this.destination = destination;
        this.status = TripStatus.ACTIVE; this.expiresAt = expiresAt;
    }
    public void changed() { revision++; }
}
