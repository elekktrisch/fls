package ch.alpenflight.locations.domain;

import ch.alpenflight.platform.id.LocationId;
import ch.alpenflight.platform.persistence.SoftDeletableAggregate;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.hibernate.annotations.TenantId;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.DomainEvents;

@Entity
@Table(name = "t_location")
public class Location extends SoftDeletableAggregate {

    private static final Pattern ICAO_PATTERN = Pattern.compile("^[A-Z]{4}$|^[A-Z]{2}[0-9]{2}$");
    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_SHORT_NAME_LENGTH = 50;
    private static final int MAX_ICAO_LENGTH = 10;
    private static final int MAX_COORD_LENGTH = 10;
    private static final int MAX_RUNWAY_DIRECTION_LENGTH = 50;
    private static final int MAX_FREQUENCY_LENGTH = 50;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private @Nullable UUID id;

    @TenantId
    @Column(nullable = false, updatable = false)
    private @Nullable UUID clubId;

    @Column(nullable = false, length = MAX_NAME_LENGTH)
    private String locationName = "";

    @Column(length = MAX_SHORT_NAME_LENGTH)
    private @Nullable String locationShortName;

    @Column(nullable = false)
    private @Nullable UUID countryId;

    @Column(nullable = false)
    private @Nullable UUID locationTypeId;

    @Column(length = MAX_ICAO_LENGTH)
    private @Nullable String icaoCode;

    @Column(length = MAX_COORD_LENGTH)
    private @Nullable String latitude;

    @Column(length = MAX_COORD_LENGTH)
    private @Nullable String longitude;

    @Column
    private @Nullable Integer elevation;

    @Column
    private @Nullable UUID elevationUnitTypeId;

    @Column(length = MAX_RUNWAY_DIRECTION_LENGTH)
    private @Nullable String runwayDirection;

    @Column
    private @Nullable Integer runwayLength;

    @Column
    private @Nullable UUID runwayLengthUnitTypeId;

    @Column(length = MAX_FREQUENCY_LENGTH)
    private @Nullable String airportFrequency;

    @Column
    private @Nullable String description;

    @Column
    private @Nullable Integer sortIndicator;

    @Column(name = "is_inbound_route_required", nullable = false)
    private boolean inboundRouteRequired;

    @Column(name = "is_outbound_route_required", nullable = false)
    private boolean outboundRouteRequired;

    @Column(name = "is_fast_entry_record", nullable = false)
    private boolean fastEntryRecord;

    @OneToMany(mappedBy = "location",
            cascade = CascadeType.ALL,
            orphanRemoval = true)
    private List<InOutboundPoint> inOutboundPoints = new ArrayList<>();

    protected Location() {
    }

    public static Location create(String locationName,
                                  @Nullable String locationShortName,
                                  UUID countryId,
                                  UUID locationTypeId,
                                  @Nullable String icaoCode,
                                  @Nullable String latitude,
                                  @Nullable String longitude,
                                  @Nullable Integer elevation,
                                  @Nullable UUID elevationUnitTypeId,
                                  @Nullable String runwayDirection,
                                  @Nullable Integer runwayLength,
                                  @Nullable UUID runwayLengthUnitTypeId,
                                  @Nullable String airportFrequency,
                                  @Nullable String description,
                                  @Nullable Integer sortIndicator,
                                  boolean inboundRouteRequired,
                                  boolean outboundRouteRequired,
                                  boolean fastEntryRecord) {
        Location loc = new Location();
        loc.rename(locationName, locationShortName);
        loc.relocate(countryId, locationTypeId, latitude, longitude, elevation, elevationUnitTypeId);
        loc.setIcao(icaoCode);
        loc.setRunway(runwayDirection, runwayLength, runwayLengthUnitTypeId);
        loc.airportFrequency = blankToNull(airportFrequency);
        loc.description = blankToNull(description);
        loc.sortIndicator = sortIndicator;
        loc.inboundRouteRequired = inboundRouteRequired;
        loc.outboundRouteRequired = outboundRouteRequired;
        loc.fastEntryRecord = fastEntryRecord;
        return loc;
    }

    public void rename(String newName, @Nullable String newShortName) {
        String trimmed = newName == null ? "" : newName.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Location name must not be blank");
        }
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "Location name exceeds %d characters".formatted(MAX_NAME_LENGTH));
        }
        this.locationName = trimmed;
        String shortTrimmed = blankToNull(newShortName);
        if (shortTrimmed != null && shortTrimmed.length() > MAX_SHORT_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "Location short name exceeds %d characters".formatted(MAX_SHORT_NAME_LENGTH));
        }
        this.locationShortName = shortTrimmed;
    }

    public void relocate(UUID newCountryId,
                         UUID newLocationTypeId,
                         @Nullable String newLatitude,
                         @Nullable String newLongitude,
                         @Nullable Integer newElevation,
                         @Nullable UUID newElevationUnitTypeId) {
        if (newCountryId == null) {
            throw new IllegalArgumentException("countryId must not be null");
        }
        if (newLocationTypeId == null) {
            throw new IllegalArgumentException("locationTypeId must not be null");
        }
        this.countryId = newCountryId;
        this.locationTypeId = newLocationTypeId;
        this.latitude = capCoord(newLatitude, "latitude");
        this.longitude = capCoord(newLongitude, "longitude");
        this.elevation = newElevation;
        this.elevationUnitTypeId = newElevationUnitTypeId;
    }

    public void setRunway(@Nullable String newRunwayDirection,
                          @Nullable Integer newRunwayLength,
                          @Nullable UUID newRunwayLengthUnitTypeId) {
        String dir = blankToNull(newRunwayDirection);
        if (dir != null && dir.length() > MAX_RUNWAY_DIRECTION_LENGTH) {
            throw new IllegalArgumentException(
                    "Runway direction exceeds %d characters".formatted(MAX_RUNWAY_DIRECTION_LENGTH));
        }
        this.runwayDirection = dir;
        this.runwayLength = newRunwayLength;
        this.runwayLengthUnitTypeId = newRunwayLengthUnitTypeId;
    }

    public void setIcao(@Nullable String newIcaoCode) {
        String trimmed = blankToNull(newIcaoCode);
        validateIcao(trimmed);
        this.icaoCode = trimmed;
    }

    public void setFrequency(@Nullable String newAirportFrequency) {
        String trimmed = blankToNull(newAirportFrequency);
        if (trimmed != null && trimmed.length() > MAX_FREQUENCY_LENGTH) {
            throw new IllegalArgumentException(
                    "Airport frequency exceeds %d characters".formatted(MAX_FREQUENCY_LENGTH));
        }
        this.airportFrequency = trimmed;
    }

    public void setDescription(@Nullable String newDescription) {
        this.description = blankToNull(newDescription);
    }

    public void setSortIndicator(@Nullable Integer newSortIndicator) {
        this.sortIndicator = newSortIndicator;
    }

    public void setRouteFlags(boolean newInboundRouteRequired,
                              boolean newOutboundRouteRequired,
                              boolean newFastEntryRecord) {
        this.inboundRouteRequired = newInboundRouteRequired;
        this.outboundRouteRequired = newOutboundRouteRequired;
        this.fastEntryRecord = newFastEntryRecord;
    }

    public void replaceInOutboundPoints(List<InOutboundPoint> newPoints) {
        this.inOutboundPoints.clear();
        if (newPoints == null) {
            return;
        }
        for (InOutboundPoint p : newPoints) {
            p.attachTo(this);
            this.inOutboundPoints.add(p);
        }
    }

    @DomainEvents
    Collection<Object> domainEvents() {
        return List.of(new LocationSaved(Objects.requireNonNull(
                this.id, "Location.id null at domain-event publication — save() runs first")));
    }

    private void validateIcao(@Nullable String value) {
        if (value == null) {
            return;
        }
        if (value.length() > MAX_ICAO_LENGTH) {
            throw new IcaoCodeInvalidException(value);
        }
        if (!ICAO_PATTERN.matcher(value).matches()) {
            throw new IcaoCodeInvalidException(value);
        }
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static @Nullable String capCoord(@Nullable String value, String field) {
        String trimmed = blankToNull(value);
        if (trimmed != null && trimmed.length() > MAX_COORD_LENGTH) {
            throw new IllegalArgumentException(
                    "%s exceeds %d characters".formatted(field, MAX_COORD_LENGTH));
        }
        return trimmed;
    }

    public @Nullable LocationId getId() {
        return LocationId.ofNullable(id);
    }

    public String getLocationName() {
        return locationName;
    }

    public @Nullable String getLocationShortName() {
        return locationShortName;
    }

    public @Nullable UUID getCountryId() {
        return countryId;
    }

    public @Nullable UUID getLocationTypeId() {
        return locationTypeId;
    }

    public @Nullable String getIcaoCode() {
        return icaoCode;
    }

    public @Nullable String getLatitude() {
        return latitude;
    }

    public @Nullable String getLongitude() {
        return longitude;
    }

    public @Nullable Integer getElevation() {
        return elevation;
    }

    public @Nullable UUID getElevationUnitTypeId() {
        return elevationUnitTypeId;
    }

    public @Nullable String getRunwayDirection() {
        return runwayDirection;
    }

    public @Nullable Integer getRunwayLength() {
        return runwayLength;
    }

    public @Nullable UUID getRunwayLengthUnitTypeId() {
        return runwayLengthUnitTypeId;
    }

    public @Nullable String getAirportFrequency() {
        return airportFrequency;
    }

    public @Nullable String getDescription() {
        return description;
    }

    public @Nullable Integer getSortIndicator() {
        return sortIndicator;
    }

    public boolean isInboundRouteRequired() {
        return inboundRouteRequired;
    }

    public boolean isOutboundRouteRequired() {
        return outboundRouteRequired;
    }

    public boolean isFastEntryRecord() {
        return fastEntryRecord;
    }

    public List<InOutboundPoint> getInOutboundPoints() {
        return Collections.unmodifiableList(inOutboundPoints);
    }

    public static InOutboundPoint newInOutboundPoint(String pointName,
                                                     @Nullable String pointType,
                                                     @Nullable String direction,
                                                     @Nullable String description) {
        return InOutboundPoint.create(pointName, pointType, direction, description);
    }
}
