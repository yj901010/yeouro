package ai.yeouro.trip;

import ai.yeouro.api.ApiException;
import ai.yeouro.api.ApiModels.Place;
import jakarta.persistence.*;

@Embeddable
public class PlaceValue {
    @Column(nullable=false, length=120) public String name;
    public Double latitude;
    public Double longitude;
    protected PlaceValue() {}
    public PlaceValue(Place place) {
        if ((place.latitude() == null) != (place.longitude() == null))
            throw ApiException.invalid("INVALID_COORDINATES", "Provide both coordinates, or neither.");
        if ((place.latitude() != null && !Double.isFinite(place.latitude())) ||
            (place.longitude() != null && !Double.isFinite(place.longitude())))
            throw ApiException.invalid("INVALID_COORDINATES", "Coordinates must be finite.");
        this.name = place.name().strip(); this.latitude = place.latitude(); this.longitude = place.longitude();
    }
    public Place dto() { return new Place(name, latitude, longitude); }
}
