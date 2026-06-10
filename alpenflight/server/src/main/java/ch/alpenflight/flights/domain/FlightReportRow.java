package ch.alpenflight.flights.domain;

import ch.alpenflight.flights.domain.FlightReportDecorations.FlightTypeDecoration;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.TenantId;
import org.jspecify.annotations.Nullable;

/**
 * Flight-report read-model row (ADR 0027 §2) — a redundant, denormalized
 * projection of one live {@link Flight} carrying exactly the columns the
 * report screen reads (the column oracle: the native SELECT in
 * {@code JpaFlightReportRepository}, page + summary queries). Maintained by
 * {@code FlightReportProjector} in the same transaction as every Flight save;
 * never written by DB triggers (ADR 0022 directive 2).
 *
 * <p>PK = the flight id (upsert-by-replace semantics: the projector refreshes
 * the existing row in place, or creates / deletes it as the source flight
 * appears / soft-deletes). Tenant-scoped via {@code @TenantId} on
 * {@link #operatingClubId} — report reads under another tenant cannot see this
 * row (ADR 0008).
 *
 * <p>AIR-STATE IS NEVER STORED (sacred cow): only the raw timestamp / flag
 * columns are carried; consumers compute it via
 * {@link FlightAirState#compute}. {@link #durationSeconds} IS precomputed
 * ({@code ldg - start}, null when either bound is missing) because the report
 * sorts and sums by it.
 *
 * <p>The {@link #crew} children mirror the flight's live (non-soft-deleted)
 * crew {@code (personId, flightCrewTypeId)} pairs — they serve the report's
 * person filter and person-role summary flags at query time (RM-3).
 */
@Entity
@Table(name = "t_flight_report_row")
public class FlightReportRow {

    /**
     * Second-crew pick order — lowest {@code t_flight_crew_type.legacy_int_id}
     * among CoPilot(2) &lt; FlightInstructor(3) &lt; Passenger(4) &lt;
     * Observer(6), matching legacy {@code FlightReportService.cs:84-88} and the
     * oracle's {@code order by ct.legacy_int_id asc limit 1} subselect.
     * {@link FlightCrewTypeIds} is the canonical id source; the relative order
     * is fixed legacy domain knowledge (V3 seed), encoded here as list order.
     */
    private static final List<UUID> SECOND_CREW_PRIORITY = List.of(
            FlightCrewTypeIds.CO_PILOT,
            FlightCrewTypeIds.FLIGHT_INSTRUCTOR,
            FlightCrewTypeIds.PASSENGER,
            FlightCrewTypeIds.OBSERVER);

    @Id
    @Column(name = "flight_id", nullable = false, updatable = false)
    private UUID flightId = new UUID(0L, 0L);

    @TenantId
    @Column(name = "operating_club_id", nullable = false, updatable = false)
    private @Nullable UUID operatingClubId;

    @Column(name = "flight_aircraft_type_id", nullable = false)
    @Convert(converter = FlightAircraftTypeConverter.class)
    private FlightAircraftType flightAircraftType = FlightAircraftType.GLIDER;

    @Column(name = "flight_date")
    private @Nullable LocalDate flightDate;

    @Column(name = "start_date_time")
    private @Nullable Instant startDateTime;

    @Column(name = "ldg_date_time")
    private @Nullable Instant ldgDateTime;

    @Column(name = "no_start_time_information", nullable = false)
    private boolean noStartTimeInformation;

    @Column(name = "no_ldg_time_information", nullable = false)
    private boolean noLdgTimeInformation;

    @Column(name = "flight_plan_opened_on")
    private @Nullable Instant flightPlanOpenedOn;

    @Column(name = "process_state_id", nullable = false)
    private UUID processStateId = new UUID(0L, 0L);

    @Column(name = "is_solo_flight", nullable = false)
    private boolean soloFlight;

    @Column(name = "comment", columnDefinition = "text")
    private @Nullable String comment;

    @Column(name = "start_type_code")
    private @Nullable String startTypeCode;

    @Column(name = "immatriculation")
    private @Nullable String immatriculation;

    @Column(name = "pilot_name")
    private @Nullable String pilotName;

    @Column(name = "second_crew_name")
    private @Nullable String secondCrewName;

    @Column(name = "flight_code")
    private @Nullable String flightCode;

    @Column(name = "flight_type_name")
    private @Nullable String flightTypeName;

    @Column(name = "start_location_id")
    private @Nullable UUID startLocationId;

    @Column(name = "start_location_name")
    private @Nullable String startLocationName;

    @Column(name = "ldg_location_id")
    private @Nullable UUID ldgLocationId;

    @Column(name = "ldg_location_name")
    private @Nullable String ldgLocationName;

    @Column(name = "nr_of_ldgs")
    private @Nullable Short nrOfLdgs;

    @Column(name = "nr_of_ldgs_on_start_location")
    private @Nullable Short nrOfLdgsOnStartLocation;

    @Column(name = "duration_seconds")
    private @Nullable Long durationSeconds;

    @Column(name = "towed_glider_flight_id")
    private @Nullable UUID towedGliderFlightId;

    // Tow block — all null when no tow is linked.

    @Column(name = "tow_flight_id")
    private @Nullable UUID towFlightId;

    @Column(name = "tow_start_date_time")
    private @Nullable Instant towStartDateTime;

    @Column(name = "tow_ldg_date_time")
    private @Nullable Instant towLdgDateTime;

    @Column(name = "tow_no_start_time_information")
    private @Nullable Boolean towNoStartTimeInformation;

    @Column(name = "tow_no_ldg_time_information")
    private @Nullable Boolean towNoLdgTimeInformation;

    @Column(name = "tow_flight_plan_opened_on")
    private @Nullable Instant towFlightPlanOpenedOn;

    @Column(name = "tow_process_state_id")
    private @Nullable UUID towProcessStateId;

    @Column(name = "tow_immatriculation")
    private @Nullable String towImmatriculation;

    @Column(name = "tow_pilot_name")
    private @Nullable String towPilotName;

    @Column(name = "tow_flight_code")
    private @Nullable String towFlightCode;

    @Column(name = "tow_flight_type_name")
    private @Nullable String towFlightTypeName;

    @Column(name = "tow_start_location_name")
    private @Nullable String towStartLocationName;

    @Column(name = "tow_ldg_location_name")
    private @Nullable String towLdgLocationName;

    @OneToMany(mappedBy = "row",
            cascade = CascadeType.ALL,
            orphanRemoval = true)
    private List<FlightReportCrewEntry> crew = new ArrayList<>();

    protected FlightReportRow() {
        // JPA.
    }

    /**
     * Projects a fresh read-model row from the live {@code flight}.
     *
     * @param flight               the saved aggregate (id non-null — projection
     *                             runs post-save)
     * @param towFlight            the linked tow flight (live, same club), or
     *                             null when {@code flight} has no tow link
     * @param towedGliderFlightId  the live glider referencing {@code flight} as
     *                             its tow (the oracle's reverse subselect), or
     *                             null
     * @param decorations          decoration lookups (names / codes)
     * @param operatingClubId      the effective tenant — stamps the crew
     *                             children; the row's own discriminator is
     *                             authoritative-stamped by Hibernate's
     *                             {@code @TenantId} resolver on insert
     */
    public static FlightReportRow project(Flight flight,
                                          @Nullable Flight towFlight,
                                          @Nullable UUID towedGliderFlightId,
                                          FlightReportDecorations decorations,
                                          UUID operatingClubId) {
        UUID flightId = flight.getId();
        if (flightId == null) {
            throw new IllegalStateException(
                    "FlightReportRow.project requires a persisted Flight (null id)");
        }
        if (operatingClubId == null) {
            throw new IllegalArgumentException("operatingClubId must not be null");
        }
        FlightReportRow row = new FlightReportRow();
        row.flightId = flightId;
        row.operatingClubId = operatingClubId;
        row.applyProjection(flight, towFlight, towedGliderFlightId, decorations, operatingClubId);
        return row;
    }

    /**
     * In-place refresh of an existing (managed) row from the current flight
     * state — the idempotent-upsert half of the projector. Reconciles the
     * crew children by identity rather than clear-and-recreate so an
     * unchanged {@code (personId, crewTypeId)} pair is never re-inserted
     * (Hibernate orders INSERTs before orphan DELETEs within one flush — a
     * re-insert of an identical key would trip the composite PK; same lesson
     * as {@link Flight#replaceCrew}).
     */
    public void refreshFrom(Flight flight,
                            @Nullable Flight towFlight,
                            @Nullable UUID towedGliderFlightId,
                            FlightReportDecorations decorations) {
        if (!this.flightId.equals(flight.getId())) {
            throw new IllegalArgumentException(
                    "Cannot refresh row " + flightId + " from flight " + flight.getId());
        }
        UUID club = this.operatingClubId;
        if (club == null) {
            throw new IllegalStateException(
                    "Managed FlightReportRow " + flightId + " carries no operating club");
        }
        applyProjection(flight, towFlight, towedGliderFlightId, decorations, club);
    }

    private void applyProjection(Flight flight,
                                 @Nullable Flight towFlight,
                                 @Nullable UUID towedGlider,
                                 FlightReportDecorations dec,
                                 UUID club) {
        this.flightAircraftType = flight.getFlightAircraftType();
        this.flightDate = flight.getFlightDate();
        this.startDateTime = flight.getStartDateTime();
        this.ldgDateTime = flight.getLdgDateTime();
        this.noStartTimeInformation = flight.isNoStartTimeInformation();
        this.noLdgTimeInformation = flight.isNoLdgTimeInformation();
        this.flightPlanOpenedOn = flight.getFlightPlanOpenedOn();
        this.processStateId = flight.getProcessStateId();
        this.soloFlight = flight.isSoloFlight();
        this.comment = flight.getComment();
        this.startTypeCode = dec.startTypeCode(flight.getStartTypeId());
        this.immatriculation = dec.immatriculation(flight.getAircraftId());
        this.pilotName = dec.personName(pilotPersonId(flight));
        this.secondCrewName = dec.personName(secondCrewPersonId(flight));
        FlightTypeDecoration flightType = dec.flightType(flight.getFlightTypeId());
        this.flightCode = flightType == null ? null : flightType.flightCode();
        this.flightTypeName = flightType == null ? null : flightType.flightTypeName();
        this.startLocationId = flight.getStartLocationId();
        this.startLocationName = dec.locationName(flight.getStartLocationId());
        this.ldgLocationId = flight.getLdgLocationId();
        this.ldgLocationName = dec.locationName(flight.getLdgLocationId());
        this.nrOfLdgs = flight.getNrOfLdgs();
        this.nrOfLdgsOnStartLocation = flight.getNrOfLdgsOnStartLocation();
        this.durationSeconds = durationSeconds(flight.getStartDateTime(), flight.getLdgDateTime());
        this.towedGliderFlightId = towedGlider;
        applyTowBlock(towFlight, dec);
        reconcileCrew(flight, club);
    }

    private void applyTowBlock(@Nullable Flight tow, FlightReportDecorations dec) {
        if (tow == null) {
            this.towFlightId = null;
            this.towStartDateTime = null;
            this.towLdgDateTime = null;
            this.towNoStartTimeInformation = null;
            this.towNoLdgTimeInformation = null;
            this.towFlightPlanOpenedOn = null;
            this.towProcessStateId = null;
            this.towImmatriculation = null;
            this.towPilotName = null;
            this.towFlightCode = null;
            this.towFlightTypeName = null;
            this.towStartLocationName = null;
            this.towLdgLocationName = null;
            return;
        }
        this.towFlightId = tow.getId();
        this.towStartDateTime = tow.getStartDateTime();
        this.towLdgDateTime = tow.getLdgDateTime();
        this.towNoStartTimeInformation = tow.isNoStartTimeInformation();
        this.towNoLdgTimeInformation = tow.isNoLdgTimeInformation();
        this.towFlightPlanOpenedOn = tow.getFlightPlanOpenedOn();
        this.towProcessStateId = tow.getProcessStateId();
        this.towImmatriculation = dec.immatriculation(tow.getAircraftId());
        this.towPilotName = dec.personName(pilotPersonId(tow));
        FlightTypeDecoration towType = dec.flightType(tow.getFlightTypeId());
        this.towFlightCode = towType == null ? null : towType.flightCode();
        this.towFlightTypeName = towType == null ? null : towType.flightTypeName();
        this.towStartLocationName = dec.locationName(tow.getStartLocationId());
        this.towLdgLocationName = dec.locationName(tow.getLdgLocationId());
    }

    /** First live PilotOrStudent crew member (oracle: {@code limit 1}). */
    private static @Nullable UUID pilotPersonId(Flight flight) {
        for (FlightCrew member : flight.getCrew()) {
            if (!member.isDeleted()
                    && FlightCrewTypeIds.PILOT_OR_STUDENT.equals(member.getFlightCrewTypeId())) {
                return member.getPersonId();
            }
        }
        return null;
    }

    /** Live crew member with the lowest {@link #SECOND_CREW_PRIORITY} rank. */
    private static @Nullable UUID secondCrewPersonId(Flight flight) {
        for (UUID crewTypeId : SECOND_CREW_PRIORITY) {
            for (FlightCrew member : flight.getCrew()) {
                if (!member.isDeleted() && crewTypeId.equals(member.getFlightCrewTypeId())) {
                    return member.getPersonId();
                }
            }
        }
        return null;
    }

    /** Reconciles the crew children to the flight's live crew, in place. */
    private void reconcileCrew(Flight flight, UUID club) {
        Map<String, FlightCrew> desired = new LinkedHashMap<>();
        for (FlightCrew member : flight.getCrew()) {
            if (!member.isDeleted()) {
                desired.putIfAbsent(
                        crewKey(member.getPersonId(), member.getFlightCrewTypeId()), member);
            }
        }
        this.crew.removeIf(entry ->
                !desired.containsKey(crewKey(entry.getPersonId(), entry.getFlightCrewTypeId())));
        Map<String, FlightReportCrewEntry> existing = new LinkedHashMap<>();
        for (FlightReportCrewEntry entry : this.crew) {
            existing.put(crewKey(entry.getPersonId(), entry.getFlightCrewTypeId()), entry);
        }
        for (Map.Entry<String, FlightCrew> wanted : desired.entrySet()) {
            if (!existing.containsKey(wanted.getKey())) {
                this.crew.add(new FlightReportCrewEntry(this,
                        wanted.getValue().getPersonId(),
                        wanted.getValue().getFlightCrewTypeId(),
                        club));
            }
        }
    }

    private static String crewKey(UUID personId, UUID crewTypeId) {
        return personId + "|" + crewTypeId;
    }

    private static @Nullable Long durationSeconds(@Nullable Instant start, @Nullable Instant ldg) {
        if (start == null || ldg == null) {
            return null;
        }
        return Duration.between(start, ldg).toSeconds();
    }

    public UUID getFlightId() {
        return flightId;
    }

    public @Nullable UUID getOperatingClubId() {
        return operatingClubId;
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

    public boolean isNoStartTimeInformation() {
        return noStartTimeInformation;
    }

    public boolean isNoLdgTimeInformation() {
        return noLdgTimeInformation;
    }

    public @Nullable Instant getFlightPlanOpenedOn() {
        return flightPlanOpenedOn;
    }

    public UUID getProcessStateId() {
        return processStateId;
    }

    public boolean isSoloFlight() {
        return soloFlight;
    }

    public @Nullable String getComment() {
        return comment;
    }

    public @Nullable String getStartTypeCode() {
        return startTypeCode;
    }

    public @Nullable String getImmatriculation() {
        return immatriculation;
    }

    public @Nullable String getPilotName() {
        return pilotName;
    }

    public @Nullable String getSecondCrewName() {
        return secondCrewName;
    }

    public @Nullable String getFlightCode() {
        return flightCode;
    }

    public @Nullable String getFlightTypeName() {
        return flightTypeName;
    }

    public @Nullable UUID getStartLocationId() {
        return startLocationId;
    }

    public @Nullable String getStartLocationName() {
        return startLocationName;
    }

    public @Nullable UUID getLdgLocationId() {
        return ldgLocationId;
    }

    public @Nullable String getLdgLocationName() {
        return ldgLocationName;
    }

    public @Nullable Short getNrOfLdgs() {
        return nrOfLdgs;
    }

    public @Nullable Short getNrOfLdgsOnStartLocation() {
        return nrOfLdgsOnStartLocation;
    }

    public @Nullable Long getDurationSeconds() {
        return durationSeconds;
    }

    public @Nullable UUID getTowedGliderFlightId() {
        return towedGliderFlightId;
    }

    public @Nullable UUID getTowFlightId() {
        return towFlightId;
    }

    public @Nullable Instant getTowStartDateTime() {
        return towStartDateTime;
    }

    public @Nullable Instant getTowLdgDateTime() {
        return towLdgDateTime;
    }

    public @Nullable Boolean getTowNoStartTimeInformation() {
        return towNoStartTimeInformation;
    }

    public @Nullable Boolean getTowNoLdgTimeInformation() {
        return towNoLdgTimeInformation;
    }

    public @Nullable Instant getTowFlightPlanOpenedOn() {
        return towFlightPlanOpenedOn;
    }

    public @Nullable UUID getTowProcessStateId() {
        return towProcessStateId;
    }

    public @Nullable String getTowImmatriculation() {
        return towImmatriculation;
    }

    public @Nullable String getTowPilotName() {
        return towPilotName;
    }

    public @Nullable String getTowFlightCode() {
        return towFlightCode;
    }

    public @Nullable String getTowFlightTypeName() {
        return towFlightTypeName;
    }

    public @Nullable String getTowStartLocationName() {
        return towStartLocationName;
    }

    public @Nullable String getTowLdgLocationName() {
        return towLdgLocationName;
    }

    public List<FlightReportCrewEntry> getCrew() {
        return Collections.unmodifiableList(crew);
    }
}
