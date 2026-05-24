package ch.alpenflight.flights.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.hibernate.annotations.TenantId;
import org.jspecify.annotations.Nullable;

/**
 * Flight aggregate root. Single-table sacred-cow shape — glider / tow /
 * motor flights are discriminated by {@link FlightAircraftType}, never
 * split into separate tables.
 *
 * <p>Per ADR 0022 directive 2, business rules live on this aggregate, not
 * in the schema. The V3 migration deliberately stripped 14 CHECK
 * constraints (see {@code V3__flights_aircraft_locations.sql:429-440}); the
 * surviving invariants land here:
 *
 * <ul>
 *   <li>Tow-link rules ({@link #linkTow}) — GLIDER → TOW, distinct rows,
 *       same operating club.</li>
 *   <li>Pairwise temporal ordering — {@code block_start ≤ start_date_time
 *       ≤ ldg_date_time ≤ block_end} (nullable-aware; only checked between
 *       non-null pairs).</li>
 *   <li>{@code engine_start ≤ engine_end} (additional DB CHECK survives as
 *       structural safety net).</li>
 *   <li>{@code nr_of_ldgs_on_start_location ≤ nr_of_ldgs} (DB CHECK
 *       survives).</li>
 *   <li>Discriminator immutability post-create.</li>
 * </ul>
 *
 * <p>Tenant-scoped via {@code @TenantId} on {@link #operatingClubId}; Hibernate
 * appends the tenant predicate to every JPA query. Cross-tenant access on
 * {@code findById} returns {@link java.util.Optional#empty} (mapped to 404
 * by the controller advice).
 *
 * <p>State-machine columns ({@link #processStateId}, {@link #airStateId},
 * {@link #validatedOn}, {@link #deliveryCreatedOn},
 * {@link #flightReportSentOn}, {@link #validationErrors}) are
 * package-private setters reserved for S-059's validator. The service
 * stamps initial state on create; no transitions ship in S-058.
 */
@Entity
@Table(name = "flight")
public class Flight {

    private static final int MAX_RUNWAY_LENGTH = 5;
    private static final int MAX_ROUTE_LENGTH = 50;
    private static final int MAX_COUPON_LENGTH = 20;
    private static final Pattern RUNWAY_PATTERN = Pattern.compile("^[0-9]{2}[LRC]?$");
    private static final Pattern COUPON_PATTERN = Pattern.compile("^[A-Z0-9\\-]{1,20}$");

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private @Nullable UUID id;

    @TenantId
    @Column(name = "operating_club_id", nullable = false, updatable = false)
    private @Nullable UUID operatingClubId;

    @Column(name = "aircraft_id", nullable = false)
    private UUID aircraftId = new UUID(0L, 0L);

    @Column(name = "flight_aircraft_type_id", nullable = false, updatable = false)
    @Convert(converter = FlightAircraftTypeConverter.class)
    private FlightAircraftType flightAircraftType = FlightAircraftType.GLIDER;

    @Column(name = "flight_date")
    private @Nullable LocalDate flightDate;

    @Column(name = "start_date_time")
    private @Nullable Instant startDateTime;

    @Column(name = "ldg_date_time")
    private @Nullable Instant ldgDateTime;

    @Column(name = "block_start_date_time")
    private @Nullable Instant blockStartDateTime;

    @Column(name = "block_end_date_time")
    private @Nullable Instant blockEndDateTime;

    @Column(name = "start_location_id")
    private @Nullable UUID startLocationId;

    @Column(name = "ldg_location_id")
    private @Nullable UUID ldgLocationId;

    @Column(name = "start_runway", length = MAX_RUNWAY_LENGTH)
    private @Nullable String startRunway;

    @Column(name = "ldg_runway", length = MAX_RUNWAY_LENGTH)
    private @Nullable String ldgRunway;

    @Column(name = "outbound_route", length = MAX_ROUTE_LENGTH)
    private @Nullable String outboundRoute;

    @Column(name = "inbound_route", length = MAX_ROUTE_LENGTH)
    private @Nullable String inboundRoute;

    @Column(name = "flight_type_id")
    private @Nullable UUID flightTypeId;

    @Column(name = "is_solo_flight", nullable = false)
    private boolean soloFlight;

    @Column(name = "start_type_id")
    private @Nullable UUID startTypeId;

    @Column(name = "tow_flight_id")
    private @Nullable UUID towFlightId;

    @Column(name = "nr_of_ldgs")
    private @Nullable Short nrOfLdgs;

    @Column(name = "nr_of_ldgs_on_start_location")
    private @Nullable Short nrOfLdgsOnStartLocation;

    @Column(name = "no_start_time_information", nullable = false)
    private boolean noStartTimeInformation;

    @Column(name = "no_ldg_time_information", nullable = false)
    private boolean noLdgTimeInformation;

    @Column(name = "air_state_id", nullable = false)
    private UUID airStateId = new UUID(0L, 0L);

    @Column(name = "process_state_id", nullable = false)
    private UUID processStateId = new UUID(0L, 0L);

    @Column(name = "engine_start_operating_counter_in_seconds")
    private @Nullable Long engineStartOperatingCounterInSeconds;

    @Column(name = "engine_end_operating_counter_in_seconds")
    private @Nullable Long engineEndOperatingCounterInSeconds;

    @Column(name = "comment", columnDefinition = "text")
    private @Nullable String comment;

    @Column(name = "incident_comment", columnDefinition = "text")
    private @Nullable String incidentComment;

    @Column(name = "validation_errors", columnDefinition = "text")
    @SuppressWarnings("UnusedVariable")
    private @Nullable String validationErrors;

    @Column(name = "coupon_number", length = MAX_COUPON_LENGTH)
    private @Nullable String couponNumber;

    @Column(name = "flight_cost_balance_type_id")
    private @Nullable UUID flightCostBalanceTypeId;

    @Column(name = "delivery_created_on")
    @SuppressWarnings("UnusedVariable")
    private @Nullable Instant deliveryCreatedOn;

    @Column(name = "validated_on")
    @SuppressWarnings("UnusedVariable")
    private @Nullable Instant validatedOn;

    @Column(name = "nr_of_passengers")
    private @Nullable Short nrOfPassengers;

    @Column(name = "start_position")
    private @Nullable Short startPosition;

    @Column(name = "flight_report_sent_on")
    @SuppressWarnings("UnusedVariable")
    private @Nullable Instant flightReportSentOn;

    @Column(name = "deleted_on")
    private @Nullable Instant deletedOn;

    @Column(name = "deleted_by_user_id")
    @SuppressWarnings("UnusedVariable")
    private @Nullable UUID deletedByUserId;

    @OneToMany(mappedBy = "flight",
            cascade = CascadeType.ALL,
            orphanRemoval = true)
    private List<FlightCrew> crew = new ArrayList<>();

    protected Flight() {
        // JPA.
    }

    /** Factory for a new glider flight. */
    public static Flight createGlider(UUID aircraftId,
                                      UUID initialProcessStateId,
                                      UUID initialAirStateId,
                                      FlightOperationalData ops) {
        return create(FlightAircraftType.GLIDER, aircraftId,
                initialProcessStateId, initialAirStateId, ops);
    }

    /** Factory for a new tow flight. */
    public static Flight createTow(UUID aircraftId,
                                   UUID initialProcessStateId,
                                   UUID initialAirStateId,
                                   FlightOperationalData ops) {
        return create(FlightAircraftType.TOW, aircraftId,
                initialProcessStateId, initialAirStateId, ops);
    }

    /** Factory for a new motor flight. */
    public static Flight createMotor(UUID aircraftId,
                                     UUID initialProcessStateId,
                                     UUID initialAirStateId,
                                     FlightOperationalData ops) {
        return create(FlightAircraftType.MOTOR, aircraftId,
                initialProcessStateId, initialAirStateId, ops);
    }

    private static Flight create(FlightAircraftType type,
                                 UUID aircraftId,
                                 UUID initialProcessStateId,
                                 UUID initialAirStateId,
                                 FlightOperationalData ops) {
        if (aircraftId == null) {
            throw new IllegalArgumentException("aircraftId must not be null");
        }
        if (initialProcessStateId == null) {
            throw new IllegalArgumentException("initialProcessStateId must not be null");
        }
        if (initialAirStateId == null) {
            throw new IllegalArgumentException("initialAirStateId must not be null");
        }
        Flight f = new Flight();
        f.flightAircraftType = type;
        f.aircraftId = aircraftId;
        f.processStateId = initialProcessStateId;
        f.airStateId = initialAirStateId;
        f.applyOperationalData(ops);
        return f;
    }

    /**
     * Updates the editable operational surface — everything except the
     * discriminator, the tenant column, and the state-machine columns
     * (S-059 owns those). Crew is replaced separately via
     * {@link #replaceCrew}.
     */
    public void updateOperationalData(FlightOperationalData ops) {
        applyOperationalData(ops);
    }

    private void applyOperationalData(FlightOperationalData ops) {
        if (ops == null) {
            throw new IllegalArgumentException("operational data must not be null");
        }
        this.flightDate = ops.flightDate();
        this.startDateTime = ops.startDateTime();
        this.ldgDateTime = ops.ldgDateTime();
        this.blockStartDateTime = ops.blockStartDateTime();
        this.blockEndDateTime = ops.blockEndDateTime();
        assertTemporalOrdering();
        this.startLocationId = ops.startLocationId();
        this.ldgLocationId = ops.ldgLocationId();
        this.startRunway = validateRunway(ops.startRunway(), "startRunway");
        this.ldgRunway = validateRunway(ops.ldgRunway(), "ldgRunway");
        this.outboundRoute = capLength(blankToNull(ops.outboundRoute()),
                MAX_ROUTE_LENGTH, "outboundRoute");
        this.inboundRoute = capLength(blankToNull(ops.inboundRoute()),
                MAX_ROUTE_LENGTH, "inboundRoute");
        this.flightTypeId = ops.flightTypeId();
        this.startTypeId = ops.startTypeId();
        this.nrOfLdgs = validateNonNegative(ops.nrOfLdgs(), "nrOfLdgs");
        this.nrOfLdgsOnStartLocation =
                validateNonNegative(ops.nrOfLdgsOnStartLocation(), "nrOfLdgsOnStartLocation");
        if (nrOfLdgs != null && nrOfLdgsOnStartLocation != null
                && nrOfLdgsOnStartLocation > nrOfLdgs) {
            throw new IllegalArgumentException(
                    "nrOfLdgsOnStartLocation must be <= nrOfLdgs");
        }
        this.noStartTimeInformation = ops.noStartTimeInformation();
        this.noLdgTimeInformation = ops.noLdgTimeInformation();
        this.engineStartOperatingCounterInSeconds = validateNonNegativeLong(
                ops.engineStartOperatingCounterInSeconds(),
                "engineStartOperatingCounterInSeconds");
        this.engineEndOperatingCounterInSeconds = validateNonNegativeLong(
                ops.engineEndOperatingCounterInSeconds(),
                "engineEndOperatingCounterInSeconds");
        if (engineStartOperatingCounterInSeconds != null
                && engineEndOperatingCounterInSeconds != null
                && engineEndOperatingCounterInSeconds < engineStartOperatingCounterInSeconds) {
            throw new IllegalArgumentException(
                    "engineEndOperatingCounterInSeconds must be >= engineStartOperatingCounterInSeconds");
        }
        this.comment = blankToNull(ops.comment());
        this.incidentComment = blankToNull(ops.incidentComment());
        this.couponNumber = validateCoupon(ops.couponNumber());
        this.flightCostBalanceTypeId = ops.flightCostBalanceTypeId();
        this.nrOfPassengers = validateNonNegative(ops.nrOfPassengers(), "nrOfPassengers");
        this.startPosition = ops.startPosition();
        this.soloFlight = ops.soloFlight();
    }

    /**
     * Replaces the crew list wholesale. Aggregate-internal mutation —
     * callers go through this method, never the JPA collection directly.
     * Duplicates on {@code (personId, flightCrewTypeId)} are rejected per
     * the partial-unique {@code ux_flight_crew_unique}.
     */
    public void replaceCrew(List<CrewMemberSpec> newCrew) {
        if (newCrew == null) {
            throw new IllegalArgumentException("newCrew must not be null");
        }
        Set<String> seen = new HashSet<>();
        for (CrewMemberSpec spec : newCrew) {
            String key = spec.personId() + "|" + spec.flightCrewTypeId();
            if (!seen.add(key)) {
                throw new DuplicateCrewMemberException(
                        "Duplicate crew row (personId, flightCrewTypeId)=" + key);
            }
        }
        this.crew.clear();
        for (CrewMemberSpec spec : newCrew) {
            this.crew.add(new FlightCrew(this,
                    spec.personId(),
                    spec.flightCrewTypeId(),
                    spec.beginFlightDatetime(),
                    spec.endFlightDatetime(),
                    spec.beginInstructionDatetime(),
                    spec.endInstructionDatetime(),
                    spec.nrOfLdgs(),
                    spec.nrOfStarts()));
        }
    }

    /**
     * Links a tow flight to this glider flight. Per ADR 0022 directive 2,
     * the V3 CHECK constraints encoding these rules were stripped — the
     * aggregate enforces them.
     */
    public void linkTow(Flight tow) {
        if (tow == null) {
            throw new IllegalArgumentException("tow must not be null");
        }
        if (this.flightAircraftType != FlightAircraftType.GLIDER) {
            throw new InvalidTowLinkException(
                    "Only GLIDER flights may link a tow (was " + flightAircraftType + ")");
        }
        if (tow.flightAircraftType != FlightAircraftType.TOW) {
            throw new InvalidTowLinkException(
                    "Tow target must be a TOW flight (was " + tow.flightAircraftType + ")");
        }
        if (tow.id != null && tow.id.equals(this.id)) {
            throw new InvalidTowLinkException("A flight may not link itself as its own tow");
        }
        if (tow.operatingClubId != null
                && this.operatingClubId != null
                && !tow.operatingClubId.equals(this.operatingClubId)) {
            throw new InvalidTowLinkException(
                    "Tow and glider must share the same operating club");
        }
        this.towFlightId = tow.id;
    }

    /** Clears any tow-flight link. */
    public void unlinkTow() {
        this.towFlightId = null;
    }

    /** Marks this flight as soft-deleted. Idempotent. */
    public void softDelete(Instant at) {
        if (this.deletedOn != null) {
            return;
        }
        this.deletedOn = at;
        for (FlightCrew c : crew) {
            c.softDelete(at);
        }
    }

    private void assertTemporalOrdering() {
        // Pairwise nullable-aware ordering: block_start ≤ start ≤ ldg ≤ block_end.
        assertOrder("blockStartDateTime", blockStartDateTime, "startDateTime", startDateTime);
        assertOrder("startDateTime", startDateTime, "ldgDateTime", ldgDateTime);
        assertOrder("ldgDateTime", ldgDateTime, "blockEndDateTime", blockEndDateTime);
    }

    private static void assertOrder(String earlierName, @Nullable Instant earlier,
                                    String laterName, @Nullable Instant later) {
        if (earlier == null || later == null) {
            return;
        }
        if (later.isBefore(earlier)) {
            throw new IllegalArgumentException(
                    laterName + " must be >= " + earlierName);
        }
    }

    private static @Nullable String validateRunway(@Nullable String raw, String field) {
        String trimmed = blankToNull(raw);
        if (trimmed == null) {
            return null;
        }
        String upper = trimmed.toUpperCase(java.util.Locale.ROOT);
        if (!RUNWAY_PATTERN.matcher(upper).matches()) {
            throw new IllegalArgumentException(
                    field + " must match pattern " + RUNWAY_PATTERN.pattern() + " (e.g. 06, 28L)");
        }
        return upper;
    }

    private static @Nullable String validateCoupon(@Nullable String raw) {
        String trimmed = blankToNull(raw);
        if (trimmed == null) {
            return null;
        }
        String upper = trimmed.toUpperCase(java.util.Locale.ROOT);
        if (!COUPON_PATTERN.matcher(upper).matches()) {
            throw new IllegalArgumentException(
                    "couponNumber must match pattern " + COUPON_PATTERN.pattern());
        }
        return upper;
    }

    private static @Nullable Short validateNonNegative(@Nullable Short value, String field) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(field + " must be >= 0");
        }
        return value;
    }

    private static @Nullable Long validateNonNegativeLong(@Nullable Long value, String field) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(field + " must be >= 0");
        }
        return value;
    }

    private static @Nullable String capLength(@Nullable String value, int max, String field) {
        if (value != null && value.length() > max) {
            throw new IllegalArgumentException(
                    field + " must be at most " + max + " characters");
        }
        return value;
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }

    public @Nullable UUID getId() {
        return id;
    }

    public @Nullable UUID getOperatingClubId() {
        return operatingClubId;
    }

    public UUID getAircraftId() {
        return aircraftId;
    }

    public FlightAircraftType getFlightAircraftType() {
        return flightAircraftType;
    }

    public @Nullable LocalDate getFlightDate() {
        return flightDate;
    }

    public @Nullable Instant getStartDateTime() {
        return startDateTime;
    }

    public @Nullable Instant getLdgDateTime() {
        return ldgDateTime;
    }

    public @Nullable Instant getBlockStartDateTime() {
        return blockStartDateTime;
    }

    public @Nullable Instant getBlockEndDateTime() {
        return blockEndDateTime;
    }

    public @Nullable UUID getStartLocationId() {
        return startLocationId;
    }

    public @Nullable UUID getLdgLocationId() {
        return ldgLocationId;
    }

    public @Nullable String getStartRunway() {
        return startRunway;
    }

    public @Nullable String getLdgRunway() {
        return ldgRunway;
    }

    public @Nullable String getOutboundRoute() {
        return outboundRoute;
    }

    public @Nullable String getInboundRoute() {
        return inboundRoute;
    }

    public @Nullable UUID getFlightTypeId() {
        return flightTypeId;
    }

    public boolean isSoloFlight() {
        return soloFlight;
    }

    public @Nullable UUID getStartTypeId() {
        return startTypeId;
    }

    public @Nullable UUID getTowFlightId() {
        return towFlightId;
    }

    public @Nullable Short getNrOfLdgs() {
        return nrOfLdgs;
    }

    public @Nullable Short getNrOfLdgsOnStartLocation() {
        return nrOfLdgsOnStartLocation;
    }

    public boolean isNoStartTimeInformation() {
        return noStartTimeInformation;
    }

    public boolean isNoLdgTimeInformation() {
        return noLdgTimeInformation;
    }

    public UUID getAirStateId() {
        return airStateId;
    }

    public UUID getProcessStateId() {
        return processStateId;
    }

    public @Nullable Long getEngineStartOperatingCounterInSeconds() {
        return engineStartOperatingCounterInSeconds;
    }

    public @Nullable Long getEngineEndOperatingCounterInSeconds() {
        return engineEndOperatingCounterInSeconds;
    }

    public @Nullable String getComment() {
        return comment;
    }

    public @Nullable String getIncidentComment() {
        return incidentComment;
    }

    public @Nullable String getCouponNumber() {
        return couponNumber;
    }

    public @Nullable UUID getFlightCostBalanceTypeId() {
        return flightCostBalanceTypeId;
    }

    public @Nullable Short getNrOfPassengers() {
        return nrOfPassengers;
    }

    public @Nullable Short getStartPosition() {
        return startPosition;
    }

    public boolean isDeleted() {
        return deletedOn != null;
    }

    public List<FlightCrew> getCrew() {
        return Collections.unmodifiableList(crew);
    }
}
