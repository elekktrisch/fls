package ch.alpenflight.locations.domain;

import ch.alpenflight.platform.id.LocationId;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Location aggregate root. Mapped to the V3 {@code location} table. Per ADR
 * 0022 directive 2 business rules (ICAO format, blank-name rejection,
 * soft-delete) live on the aggregate; the schema enforces only structure
 * (PKs, FKs, NOT NULL, partial UNIQUE on {@code icao_code}).
 *
 * <p>Reference data — cross-tenant by construction per
 * {@code alpenflight/database/tenant-rules.yaml}. No {@code @TenantId}.
 * SYSTEM_ADMINISTRATOR-only mutation gate lives at the controller level.
 *
 * <p>{@link InOutboundPoint} children are managed inside the aggregate;
 * {@link #replaceInOutboundPoints(List)} is the only mutator that replaces
 * the full list, exploiting the {@code orphanRemoval=true} mapping for
 * upsert-with-orphan-cleanup semantics on PUT.
 */
@Entity
@Table(name = "location")
public class Location {

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

    @Column(name = "location_name", nullable = false, length = MAX_NAME_LENGTH)
    private String locationName = "";

    @Column(name = "location_short_name", length = MAX_SHORT_NAME_LENGTH)
    private @Nullable String locationShortName;

    @Column(name = "country_id", nullable = false)
    private @Nullable UUID countryId;

    @Column(name = "location_type_id", nullable = false)
    private @Nullable UUID locationTypeId;

    @Column(name = "icao_code", length = MAX_ICAO_LENGTH)
    private @Nullable String icaoCode;

    @Column(name = "latitude", length = MAX_COORD_LENGTH)
    private @Nullable String latitude;

    @Column(name = "longitude", length = MAX_COORD_LENGTH)
    private @Nullable String longitude;

    @Column(name = "elevation")
    private @Nullable Integer elevation;

    @Column(name = "elevation_unit_type_id")
    private @Nullable UUID elevationUnitTypeId;

    @Column(name = "runway_direction", length = MAX_RUNWAY_DIRECTION_LENGTH)
    private @Nullable String runwayDirection;

    @Column(name = "runway_length")
    private @Nullable Integer runwayLength;

    @Column(name = "runway_length_unit_type_id")
    private @Nullable UUID runwayLengthUnitTypeId;

    @Column(name = "airport_frequency", length = MAX_FREQUENCY_LENGTH)
    private @Nullable String airportFrequency;

    @Column(name = "description")
    private @Nullable String description;

    @Column(name = "sort_indicator")
    private @Nullable Integer sortIndicator;

    @Column(name = "is_inbound_route_required", nullable = false)
    private boolean inboundRouteRequired;

    @Column(name = "is_outbound_route_required", nullable = false)
    private boolean outboundRouteRequired;

    @Column(name = "is_fast_entry_record", nullable = false)
    private boolean fastEntryRecord;

    @Column(name = "deleted_on")
    private @Nullable Instant deletedOn;

    @Column(name = "deleted_by_user_id")
    private @Nullable UUID deletedByUserId;

    @OneToMany(mappedBy = "location",
            cascade = CascadeType.ALL,
            orphanRemoval = true)
    private List<InOutboundPoint> inOutboundPoints = new ArrayList<>();

    protected Location() {
        // JPA.
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

    public void softDelete(@Nullable UUID userId, Clock clock) {
        if (this.deletedOn == null) {
            this.deletedOn = Instant.now(clock);
            this.deletedByUserId = userId;
            // Release the partial-UNIQUE slot held by icao_code so a
            // replacement Location can be created with the same code. The
            // partial index is scoped WHERE icao_code IS NOT NULL — nulling
            // here is the cleanest way to honor the AC "recreate after
            // soft-delete must succeed" without an audit-fidelity loss
            // (the original icao still lives in the audit / history once
            // S-027 ships).
            this.icaoCode = null;
        }
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

    public boolean isDeleted() {
        return deletedOn != null;
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
