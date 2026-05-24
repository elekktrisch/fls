package ch.alpenflight.aircraft.domain;

import ch.alpenflight.platform.id.AircraftId;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Aircraft aggregate root. Per ADR 0018 the AR owns its airworthiness-state
 * history and operating-counter history as aggregate-internal entities.
 * Per ADR 0022 directive 2, business rules live on the aggregate, not the
 * schema:
 *
 * <ul>
 *   <li>{@link Immatriculation} format + length (VO at boundary; legacy was lax).</li>
 *   <li>Counter monotonicity (non-decreasing totals) — {@link #recordCounter}.</li>
 *   <li>State-history "exactly one open" — {@link #changeState} closes the
 *       previous open row; the V3 partial unique
 *       {@code ux_aas_current_state_per_aircraft} is the structural safety net.</li>
 * </ul>
 *
 * <p>Cross-tenant per S-058 (reverts S-159's {@code @TenantId} discriminator
 * but keeps the {@code managing_club_id} column): the day-1 charter case —
 * a glider club flying a tow plane owned by another club — requires that
 * any club may reference any active aircraft on a Flight. The aircraft
 * picker is therefore unscoped (any authenticated user reads).
 *
 * <p>Two ownership-axis columns:
 *
 * <ul>
 *   <li>{@code managing_club_id} (NOT NULL): operational manager — the
 *       club that may edit masterdata / state / counters via the
 *       {@code AircraftAccess} SpEL gate. Required even for external-owned
 *       aircraft (the "Club A operates an aircraft owned by an
 *       organisation not in the system" case — Club A is the manager,
 *       owner_club_id is NULL).</li>
 *   <li>{@code owner_club_id} (NULL OK): physical / regulatory owner
 *       metadata. May differ from manager. NULL when owned externally
 *       (not in the Clubs catalog) or by a private person. Does NOT
 *       gate edits.</li>
 *   <li>{@code aircraft_owner_person_id} (NULL OK): private-person owner
 *       metadata; orthogonal to {@code owner_club_id}. Today purely
 *       informational — person-edit predicate deferred to a follow-up
 *       when S-052 wires User→Person.</li>
 * </ul>
 *
 * <p>{@code AircraftAccess} gates writes:
 * CLUB_ADMINISTRATOR of {@code managing_club_id} for masterdata;
 * CLUB_ADMINISTRATOR or FLIGHT_OPERATOR of {@code managing_club_id} for
 * state + counter; SYSTEM_ADMINISTRATOR as the universal fallback.
 *
 * <p>Aggregate boundary: {@link AircraftStateHistoryEntry} +
 * {@link AircraftOperatingCounter} are managed via Aircraft mutation
 * methods only. No top-level CRUD endpoint for either.
 */
@Entity
@Table(name = "aircraft")
public class Aircraft {

    private static final int MAX_MANUFACTURER_LENGTH = 100;
    private static final int MAX_MODEL_LENGTH = 50;
    private static final int MAX_COMPETITION_SIGN_LENGTH = 5;
    private static final int MAX_FLARM_ID_LENGTH = 50;
    private static final int MAX_SERIAL_NUMBER_LENGTH = 20;
    private static final int MAX_NOISE_CLASS_LENGTH = 1;
    private static final int MAX_COMMENT_LENGTH = 250;
    private static final int MAX_SPOT_LINK_LENGTH = 250;

    private static final Pattern FLARM_ID_PATTERN = Pattern.compile("^[A-F0-9]{6}$");
    private static final Pattern COMPETITION_SIGN_PATTERN = Pattern.compile("^[A-Z0-9]{1,5}$");

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private @Nullable UUID id;

    @Column(name = "managing_club_id", nullable = false, updatable = false)
    private @Nullable UUID managingClubId;

    @Column(name = "owner_club_id")
    private @Nullable UUID ownerClubId;

    @Column(name = "aircraft_type_id", nullable = false)
    private @Nullable UUID aircraftTypeId;

    @Column(name = "manufacturer_name", length = MAX_MANUFACTURER_LENGTH)
    private @Nullable String manufacturerName;

    @Column(name = "aircraft_model", length = MAX_MODEL_LENGTH)
    private @Nullable String aircraftModel;

    @Column(name = "immatriculation", nullable = false, length = Immatriculation.MAX_LENGTH)
    private String immatriculation = "";

    @Column(name = "competition_sign", length = MAX_COMPETITION_SIGN_LENGTH)
    private @Nullable String competitionSign;

    @Column(name = "flarm_id", length = MAX_FLARM_ID_LENGTH)
    private @Nullable String flarmId;

    @Column(name = "aircraft_serial_number", length = MAX_SERIAL_NUMBER_LENGTH)
    private @Nullable String aircraftSerialNumber;

    @Column(name = "year_of_manufacture")
    private @Nullable LocalDate yearOfManufacture;

    @Column(name = "noise_class", length = MAX_NOISE_CLASS_LENGTH)
    private @Nullable String noiseClass;

    @Column(name = "noise_level")
    private @Nullable BigDecimal noiseLevel;

    @Column(name = "mtom")
    private @Nullable Integer mtom;

    @Column(name = "nr_of_seats")
    private @Nullable Integer nrOfSeats;

    @Column(name = "aircraft_owner_person_id")
    private @Nullable UUID aircraftOwnerPersonId;

    @Column(name = "flight_operating_counter_unit_type_id")
    private @Nullable UUID flightOperatingCounterUnitTypeId;

    @Column(name = "engine_operating_counter_unit_type_id")
    private @Nullable UUID engineOperatingCounterUnitTypeId;

    @Column(name = "homebase_id")
    private @Nullable UUID homebaseId;

    @Column(name = "spot_link", length = MAX_SPOT_LINK_LENGTH)
    private @Nullable String spotLink;

    @Column(name = "is_towing_or_winch_required", nullable = false)
    private boolean towingOrWinchRequired;

    @Column(name = "is_towing_start_allowed", nullable = false)
    private boolean towingStartAllowed;

    @Column(name = "is_winch_start_allowed", nullable = false)
    private boolean winchStartAllowed;

    @Column(name = "is_towing_aircraft", nullable = false)
    private boolean towingAircraft;

    @Column(name = "is_fast_entry_record", nullable = false)
    private boolean fastEntryRecord;

    @Column(name = "comment", length = MAX_COMMENT_LENGTH)
    private @Nullable String comment;

    @Column(name = "daec_index")
    private @Nullable Integer daecIndex;

    @Column(name = "deleted_on")
    private @Nullable Instant deletedOn;

    @Column(name = "deleted_by_user_id")
    @SuppressWarnings("UnusedVariable")
    private @Nullable UUID deletedByUserId;

    @OneToMany(mappedBy = "aircraft",
            cascade = CascadeType.ALL,
            orphanRemoval = true)
    private List<AircraftStateHistoryEntry> stateHistory = new ArrayList<>();

    @OneToMany(mappedBy = "aircraft",
            cascade = CascadeType.ALL)
    private List<AircraftOperatingCounter> operatingCounters = new ArrayList<>();

    protected Aircraft() {
        // JPA.
    }

    /**
     * Factory for a new Aircraft. Aircraft is cross-tenant (S-058 reversion
     * of S-159) — reads are open, writes are gated by the
     * {@code managing_club_id} via the {@code AircraftAccess} SpEL bean.
     *
     * @param managingClubId the operational manager — the club that may edit
     *     this aircraft's masterdata, state, and counter records. Required.
     *     Service layer defaults to the caller's club.
     * @param ownerClubId    the physical owner club. NULL when the owner is
     *     an external organisation not in the Clubs catalog, or when the
     *     aircraft is privately owned (see {@code aircraftOwnerPersonId}).
     *     {@code ownerClubId} is metadata only — it does NOT gate edits.
     */
    public static Aircraft register(UUID managingClubId,
                                    @Nullable UUID ownerClubId,
                                    UUID aircraftTypeId,
                                    String immatriculation,
                                    @Nullable String manufacturerName,
                                    @Nullable String aircraftModel,
                                    @Nullable String competitionSign,
                                    @Nullable String flarmId,
                                    @Nullable String aircraftSerialNumber,
                                    @Nullable LocalDate yearOfManufacture,
                                    @Nullable String noiseClass,
                                    @Nullable BigDecimal noiseLevel,
                                    @Nullable Integer mtom,
                                    @Nullable Integer nrOfSeats,
                                    @Nullable UUID aircraftOwnerPersonId,
                                    @Nullable UUID flightOperatingCounterUnitTypeId,
                                    @Nullable UUID engineOperatingCounterUnitTypeId,
                                    @Nullable UUID homebaseId,
                                    @Nullable String spotLink,
                                    boolean towingOrWinchRequired,
                                    boolean towingStartAllowed,
                                    boolean winchStartAllowed,
                                    boolean towingAircraft,
                                    @Nullable String comment,
                                    @Nullable Integer daecIndex) {
        if (managingClubId == null) {
            throw new IllegalArgumentException("managingClubId must not be null");
        }
        Aircraft a = new Aircraft();
        a.managingClubId = managingClubId;
        a.ownerClubId = ownerClubId;
        a.assignAircraftType(aircraftTypeId);
        a.assignImmatriculation(immatriculation);
        a.updateMasterdata(manufacturerName, aircraftModel, competitionSign, flarmId,
                aircraftSerialNumber, yearOfManufacture, noiseClass, noiseLevel,
                mtom, nrOfSeats, aircraftOwnerPersonId,
                flightOperatingCounterUnitTypeId, engineOperatingCounterUnitTypeId,
                homebaseId, spotLink,
                towingOrWinchRequired, towingStartAllowed, winchStartAllowed,
                towingAircraft, comment, daecIndex);
        return a;
    }

    public void rename(String newImmatriculation) {
        assignImmatriculation(newImmatriculation);
    }

    public void changeType(UUID newAircraftTypeId) {
        assignAircraftType(newAircraftTypeId);
    }

    public void updateMasterdata(@Nullable String newManufacturerName,
                                 @Nullable String newAircraftModel,
                                 @Nullable String newCompetitionSign,
                                 @Nullable String newFlarmId,
                                 @Nullable String newAircraftSerialNumber,
                                 @Nullable LocalDate newYearOfManufacture,
                                 @Nullable String newNoiseClass,
                                 @Nullable BigDecimal newNoiseLevel,
                                 @Nullable Integer newMtom,
                                 @Nullable Integer newNrOfSeats,
                                 @Nullable UUID newAircraftOwnerPersonId,
                                 @Nullable UUID newFlightOperatingCounterUnitTypeId,
                                 @Nullable UUID newEngineOperatingCounterUnitTypeId,
                                 @Nullable UUID newHomebaseId,
                                 @Nullable String newSpotLink,
                                 boolean newTowingOrWinchRequired,
                                 boolean newTowingStartAllowed,
                                 boolean newWinchStartAllowed,
                                 boolean newTowingAircraft,
                                 @Nullable String newComment,
                                 @Nullable Integer newDaecIndex) {
        this.manufacturerName = capLength(blankToNull(newManufacturerName),
                MAX_MANUFACTURER_LENGTH, "manufacturerName");
        this.aircraftModel = capLength(blankToNull(newAircraftModel),
                MAX_MODEL_LENGTH, "aircraftModel");
        setCompetitionSign(newCompetitionSign);
        setFlarmId(newFlarmId);
        this.aircraftSerialNumber = capLength(blankToNull(newAircraftSerialNumber),
                MAX_SERIAL_NUMBER_LENGTH, "aircraftSerialNumber");
        this.yearOfManufacture = newYearOfManufacture;
        this.noiseClass = capLength(blankToNull(newNoiseClass),
                MAX_NOISE_CLASS_LENGTH, "noiseClass");
        if (newNoiseLevel != null && newNoiseLevel.signum() < 0) {
            throw new IllegalArgumentException("noiseLevel must be >= 0");
        }
        this.noiseLevel = newNoiseLevel;
        if (newMtom != null && newMtom < 0) {
            throw new IllegalArgumentException("mtom must be >= 0");
        }
        this.mtom = newMtom;
        if (newNrOfSeats != null && newNrOfSeats < 0) {
            throw new IllegalArgumentException("nrOfSeats must be >= 0");
        }
        this.nrOfSeats = newNrOfSeats;
        this.aircraftOwnerPersonId = newAircraftOwnerPersonId;
        this.flightOperatingCounterUnitTypeId = newFlightOperatingCounterUnitTypeId;
        this.engineOperatingCounterUnitTypeId = newEngineOperatingCounterUnitTypeId;
        this.homebaseId = newHomebaseId;
        setSpotLink(newSpotLink);
        this.towingOrWinchRequired = newTowingOrWinchRequired;
        this.towingStartAllowed = newTowingStartAllowed;
        this.winchStartAllowed = newWinchStartAllowed;
        this.towingAircraft = newTowingAircraft;
        this.comment = capLength(blankToNull(newComment),
                MAX_COMMENT_LENGTH, "comment");
        this.daecIndex = newDaecIndex;
    }

    /**
     * Closes the currently-open state period (if any) by setting its
     * {@code validTo} to {@code closingValidTo}. Idempotent when no period
     * is open. Split out from {@link #openStatePeriod} so the service can
     * flush the UPDATE before the next INSERT — the partial-unique
     * {@code ux_aas_current_state_per_aircraft} would otherwise reject the
     * new row, since Hibernate's default flush order is insert-before-update
     * (PostgreSQL doesn't support DEFERRABLE on partial unique indexes).
     */
    public void closeCurrentStatePeriodAt(Instant closingValidTo) {
        if (closingValidTo == null) {
            throw new IllegalArgumentException("closingValidTo must not be null");
        }
        Optional<AircraftStateHistoryEntry> existing = currentOpenStateEntry();
        if (existing.isEmpty()) {
            return;
        }
        AircraftStateHistoryEntry current = existing.get();
        Instant currentFrom = current.getValidFrom();
        if (currentFrom != null && !closingValidTo.isAfter(currentFrom)) {
            throw new AircraftStateConflictException(
                    "New state's valid_from (" + closingValidTo
                            + ") must be strictly after the open state's valid_from (" + currentFrom + ")");
        }
        current.closeAt(closingValidTo);
    }

    /**
     * Opens a new airworthiness state period. Requires that no other period
     * is currently open — call {@link #closeCurrentStatePeriodAt} first.
     */
    public AircraftStateHistoryEntry openStatePeriod(UUID newAircraftStateId,
                                                     Instant validFrom,
                                                     @Nullable UUID noticedByPersonId,
                                                     @Nullable String remarks) {
        if (validFrom == null) {
            throw new IllegalArgumentException("validFrom must not be null");
        }
        if (currentOpenStateEntry().isPresent()) {
            throw new AircraftStateConflictException(
                    "An open state period already exists; close it before opening a new one");
        }
        AircraftStateHistoryEntry entry = AircraftStateHistoryEntry.open(
                newAircraftStateId, validFrom, noticedByPersonId, remarks);
        entry.attachTo(this);
        stateHistory.add(entry);
        return entry;
    }

    /**
     * Convenience facade for unit tests and in-memory callers: close the
     * existing period (if any) and open a new one in one call. Persistence
     * callers should use {@link #closeCurrentStatePeriodAt} +
     * {@link #openStatePeriod} with a flush between to satisfy the partial
     * unique invariant at the schema level.
     */
    public AircraftStateHistoryEntry changeState(UUID newAircraftStateId,
                                                 Instant validFrom,
                                                 @Nullable UUID noticedByPersonId,
                                                 @Nullable String remarks) {
        closeCurrentStatePeriodAt(validFrom);
        return openStatePeriod(newAircraftStateId, validFrom, noticedByPersonId, remarks);
    }

    /**
     * Records a new operating-counter snapshot. Append-only; totals must
     * be monotonic non-decreasing relative to the previous latest snapshot.
     */
    public AircraftOperatingCounter recordCounter(Instant atDateTime,
                                                  @Nullable Integer totalTowedGliderStarts,
                                                  @Nullable Integer totalWinchLaunchStarts,
                                                  @Nullable Integer totalSelfStarts,
                                                  @Nullable Long flightOperatingCounterInSeconds,
                                                  @Nullable Long engineOperatingCounterInSeconds,
                                                  @Nullable Long nextMaintenanceAtFlightOperatingCounterInSeconds,
                                                  @Nullable Long nextMaintenanceAtEngineOperatingCounterInSeconds) {
        Optional<AircraftOperatingCounter> latest = latestCounter();
        if (latest.isPresent()) {
            AircraftOperatingCounter prev = latest.get();
            Instant prevAt = prev.getAtDateTime();
            if (prevAt != null && !atDateTime.isAfter(prevAt)) {
                throw new CounterMonotonicityException(
                        "New counter at_date_time (" + atDateTime
                                + ") must be strictly after the previous (" + prevAt + ")");
            }
            requireMonotonic("flightOperatingCounterInSeconds",
                    prev.getFlightOperatingCounterInSeconds(), flightOperatingCounterInSeconds);
            requireMonotonic("engineOperatingCounterInSeconds",
                    prev.getEngineOperatingCounterInSeconds(), engineOperatingCounterInSeconds);
            requireMonotonicInt("totalTowedGliderStarts",
                    prev.getTotalTowedGliderStarts(), totalTowedGliderStarts);
            requireMonotonicInt("totalWinchLaunchStarts",
                    prev.getTotalWinchLaunchStarts(), totalWinchLaunchStarts);
            requireMonotonicInt("totalSelfStarts",
                    prev.getTotalSelfStarts(), totalSelfStarts);
        }
        AircraftOperatingCounter counter = AircraftOperatingCounter.record(
                atDateTime, totalTowedGliderStarts, totalWinchLaunchStarts, totalSelfStarts,
                flightOperatingCounterInSeconds, engineOperatingCounterInSeconds,
                nextMaintenanceAtFlightOperatingCounterInSeconds,
                nextMaintenanceAtEngineOperatingCounterInSeconds);
        counter.attachTo(this);
        operatingCounters.add(counter);
        return counter;
    }

    public void transferOwnership(@Nullable UUID newOwnerClubId, @Nullable UUID newOwnerPersonId) {
        this.ownerClubId = newOwnerClubId;
        this.aircraftOwnerPersonId = newOwnerPersonId;
    }

    public void softDelete(@Nullable UUID userId, Clock clock) {
        if (this.deletedOn == null) {
            this.deletedOn = Instant.now(clock);
            this.deletedByUserId = userId;
        }
    }

    private void assignImmatriculation(String value) {
        this.immatriculation = Immatriculation.of(value).normalized();
    }

    private void assignAircraftType(UUID newAircraftTypeId) {
        if (newAircraftTypeId == null) {
            throw new IllegalArgumentException("aircraftTypeId must not be null");
        }
        this.aircraftTypeId = newAircraftTypeId;
    }

    private void setCompetitionSign(@Nullable String value) {
        String trimmed = blankToNull(value);
        if (trimmed == null) {
            this.competitionSign = null;
            return;
        }
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (!COMPETITION_SIGN_PATTERN.matcher(upper).matches()) {
            throw new IllegalArgumentException(
                    "competitionSign must match ^[A-Z0-9]{1,5}$, got: " + value);
        }
        this.competitionSign = upper;
    }

    private void setFlarmId(@Nullable String value) {
        String trimmed = blankToNull(value);
        if (trimmed == null) {
            this.flarmId = null;
            return;
        }
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (!FLARM_ID_PATTERN.matcher(upper).matches()) {
            throw new IllegalArgumentException(
                    "flarmId must match ^[A-F0-9]{6}$ (ICAO 24-bit hex), got: " + value);
        }
        this.flarmId = upper;
    }

    private void setSpotLink(@Nullable String value) {
        String trimmed = blankToNull(value);
        if (trimmed == null) {
            this.spotLink = null;
            return;
        }
        if (trimmed.length() > MAX_SPOT_LINK_LENGTH) {
            throw new IllegalArgumentException(
                    "spotLink exceeds " + MAX_SPOT_LINK_LENGTH + " characters");
        }
        if (!trimmed.toLowerCase(Locale.ROOT).startsWith("https://")) {
            throw new IllegalArgumentException(
                    "spotLink must start with 'https://' (A10 SSRF defense-in-depth), got: " + value);
        }
        this.spotLink = trimmed;
    }

    private Optional<AircraftStateHistoryEntry> currentOpenStateEntry() {
        return stateHistory.stream()
                .filter(AircraftStateHistoryEntry::isOpen)
                .findFirst();
    }

    private Optional<AircraftOperatingCounter> latestCounter() {
        return operatingCounters.stream()
                .filter(c -> c.getAtDateTime() != null)
                .max(Comparator.comparing(AircraftOperatingCounter::getAtDateTime));
    }

    private static void requireMonotonic(String name, @Nullable Long prev, @Nullable Long next) {
        if (prev != null && next != null && next < prev) {
            throw new CounterMonotonicityException(
                    name + " decreased: " + prev + " -> " + next + " (must be monotonic non-decreasing)");
        }
    }

    private static void requireMonotonicInt(String name, @Nullable Integer prev, @Nullable Integer next) {
        if (prev != null && next != null && next < prev) {
            throw new CounterMonotonicityException(
                    name + " decreased: " + prev + " -> " + next + " (must be monotonic non-decreasing)");
        }
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static @Nullable String capLength(@Nullable String value, int max, String field) {
        if (value != null && value.length() > max) {
            throw new IllegalArgumentException(field + " exceeds " + max + " characters");
        }
        return value;
    }

    public @Nullable AircraftId getId() {
        return AircraftId.ofNullable(id);
    }

    public @Nullable UUID getManagingClubId() {
        return managingClubId;
    }

    public @Nullable UUID getOwnerClubId() {
        return ownerClubId;
    }

    public @Nullable UUID getAircraftTypeId() {
        return aircraftTypeId;
    }

    public String getImmatriculation() {
        return immatriculation;
    }

    public @Nullable String getManufacturerName() {
        return manufacturerName;
    }

    public @Nullable String getAircraftModel() {
        return aircraftModel;
    }

    public @Nullable String getCompetitionSign() {
        return competitionSign;
    }

    public @Nullable String getFlarmId() {
        return flarmId;
    }

    public @Nullable String getAircraftSerialNumber() {
        return aircraftSerialNumber;
    }

    public @Nullable LocalDate getYearOfManufacture() {
        return yearOfManufacture;
    }

    public @Nullable String getNoiseClass() {
        return noiseClass;
    }

    public @Nullable BigDecimal getNoiseLevel() {
        return noiseLevel;
    }

    public @Nullable Integer getMtom() {
        return mtom;
    }

    public @Nullable Integer getNrOfSeats() {
        return nrOfSeats;
    }

    public @Nullable UUID getAircraftOwnerPersonId() {
        return aircraftOwnerPersonId;
    }

    public @Nullable UUID getFlightOperatingCounterUnitTypeId() {
        return flightOperatingCounterUnitTypeId;
    }

    public @Nullable UUID getEngineOperatingCounterUnitTypeId() {
        return engineOperatingCounterUnitTypeId;
    }

    public @Nullable UUID getHomebaseId() {
        return homebaseId;
    }

    public @Nullable String getSpotLink() {
        return spotLink;
    }

    public boolean isTowingOrWinchRequired() {
        return towingOrWinchRequired;
    }

    public boolean isTowingStartAllowed() {
        return towingStartAllowed;
    }

    public boolean isWinchStartAllowed() {
        return winchStartAllowed;
    }

    public boolean isTowingAircraft() {
        return towingAircraft;
    }

    public boolean isFastEntryRecord() {
        return fastEntryRecord;
    }

    public @Nullable String getComment() {
        return comment;
    }

    public @Nullable Integer getDaecIndex() {
        return daecIndex;
    }

    public boolean isDeleted() {
        return deletedOn != null;
    }

    public List<AircraftStateHistoryEntry> getStateHistory() {
        return Collections.unmodifiableList(stateHistory);
    }

    public Optional<AircraftStateHistoryEntry> getCurrentStateEntry() {
        return currentOpenStateEntry();
    }

    public List<AircraftOperatingCounter> getOperatingCounters() {
        return Collections.unmodifiableList(operatingCounters);
    }

    public Optional<AircraftOperatingCounter> getLatestCounter() {
        return latestCounter();
    }
}
