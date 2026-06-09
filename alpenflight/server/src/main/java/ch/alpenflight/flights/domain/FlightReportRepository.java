package ch.alpenflight.flights.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Read-model port for the flight-report query (J-7). Implemented by
 * {@code ch.alpenflight.flights.infra.JpaFlightReportRepository}.
 *
 * <p>Tenant scoping is explicit (ADR 0008): the implementation binds the
 * caller's tenant and constrains the matched flight by
 * {@code operating_club_id = :tenant}. This CORRECTS the legacy tenancy hole
 * ({@code FlightReportService.cs:114-125}) where a person-only / unknown-type
 * report could leak other clubs' flights. Decoration columns (immatriculation,
 * pilot/second-crew name, flight-type name/code, locations) are resolved
 * regardless of their own tenant — matching legacy and the cross-tenant
 * ride-throughs (charter aircraft S-058, cross-tenant crew Person S-051).
 *
 * <p>The implementation is native SQL (registered in
 * {@code alpenflight/database/native-sql-register.md}): JPQL would apply each
 * joined entity's {@code @TenantId} discriminator and so HIDE the
 * cross-tenant decorations the report must show.
 */
public interface FlightReportRepository {

    /** Crew roles a person filter counts: PilotOrStudent, CoPilot, FlightInstructor. */
    List<UUID> PERSON_FILTER_CREW_TYPES = List.of(
            FlightCrewTypeIds.PILOT_OR_STUDENT,
            FlightCrewTypeIds.CO_PILOT,
            FlightCrewTypeIds.FLIGHT_INSTRUCTOR);

    /**
     * Flat projection of one matched flight plus its (optional) aerotow tow
     * flight and the back-reference glider id. The query service assembles the
     * {@code FlightReportDataRecord} (air-state compute, duration, category,
     * nested tow) from these rows.
     *
     * <p>{@code airState} is NOT carried — it is computed by the service from
     * the timestamp/flag columns via {@link FlightAirState#compute}.
     */
    record ReportRow(
            UUID flightId,
            short flightAircraftTypeLegacyId,
            @Nullable LocalDate flightDate,
            @Nullable Instant startDateTime,
            @Nullable Instant ldgDateTime,
            boolean noStartTimeInformation,
            boolean noLdgTimeInformation,
            @Nullable Instant flightPlanOpenedOn,
            UUID processStateId,
            boolean soloFlight,
            @Nullable String comment,
            @Nullable String startTypeCode,
            @Nullable String immatriculation,
            @Nullable String pilotName,
            @Nullable String secondCrewName,
            @Nullable String flightCode,
            @Nullable String flightTypeName,
            @Nullable String startLocation,
            @Nullable String ldgLocation,
            @Nullable UUID towedGliderFlightId,
            // Tow block (all null when towFlightId is absent).
            @Nullable UUID towFlightId,
            @Nullable Instant towStartDateTime,
            @Nullable Instant towLdgDateTime,
            boolean towNoStartTimeInformation,
            boolean towNoLdgTimeInformation,
            @Nullable Instant towFlightPlanOpenedOn,
            @Nullable UUID towProcessStateId,
            @Nullable String towImmatriculation,
            @Nullable String towPilotName,
            @Nullable String towFlightCode,
            @Nullable String towFlightTypeName,
            @Nullable String towStartLocation,
            @Nullable String towLdgLocation) {}

    /**
     * Returns the matched flights for one page (rows + total count) within the
     * given tenant. {@code from}/{@code to} are inclusive date-only bounds
     * (null ⇒ unbounded); when any bound is set, flights with a null
     * {@code flight_date} are excluded. {@code locationId} matches
     * start OR landing location. {@code personId} matches flights with a
     * non-deleted crew row for that person in {@link #PERSON_FILTER_CREW_TYPES}.
     * The three type flags are OR'd against the aircraft-type discriminator;
     * all-false yields no rows.
     *
     * @param offset  0-based row offset
     * @param limit   page size (already clamped by the service)
     * @param sortBySeconds when true, order by flight duration in seconds
     *                      (the {@code FlightDuration} sort-key remap) instead
     *                      of the default {@code start_date_time, immatriculation}
     * @param sortAsc sort direction
     */
    List<ReportRow> findReportPage(ReportCriteria criteria,
                                   int offset,
                                   int limit,
                                   boolean sortBySeconds,
                                   boolean sortAsc);

    /** Total matched rows for the same filter (ignores pagination). */
    long countReport(ReportCriteria criteria);

    /**
     * Per-flight aggregation inputs for the summary computation (J-7 T-04).
     * Returns ALL matched flights for the filter (no pagination) within the
     * tenant — the service folds these into the person- or location-branch
     * summary rows in memory (legacy re-queries per group; this matches the
     * VALUES with a single tenant-scoped pass).
     *
     * <p>{@code isPilotOrStudent}/{@code isCoPilot}/{@code isFlightInstructor}
     * are true when the filter's {@code personId} holds that crew role on the
     * flight (non-deleted crew row); they are all false when the filter has no
     * person. {@code aircraftType} is the legacy aircraft-type int (1=glider,
     * 2=tow, 4=motor). {@code durationSeconds} is {@code DiffSeconds(start,ldg)}
     * (0 when either bound is absent — matching legacy {@code DbFunctions}).
     */
    record SummaryRow(
            short aircraftType,
            boolean soloFlight,
            @Nullable Short nrOfLdgs,
            @Nullable Short nrOfLdgsOnStartLocation,
            boolean noStartTimeInformation,
            boolean noLdgTimeInformation,
            @Nullable UUID startLocationId,
            @Nullable UUID ldgLocationId,
            long durationSeconds,
            @Nullable String flightTypeName,
            boolean isPilotOrStudent,
            boolean isCoPilot,
            boolean isFlightInstructor) {}

    /**
     * All matched flights (no pagination) for the filter, projected to the
     * summary aggregation inputs. Tenant-scoped identically to
     * {@link #findReportPage}.
     */
    List<SummaryRow> findSummaryRows(ReportCriteria criteria);

    /**
     * Resolved, tenant-bound filter for a report query. {@code tenantId} is
     * the caller's effective tenant (the page + count both scope by it — the
     * J-7 tenancy-hole correction). The remaining fields mirror the filter
     * DTO; see {@link #findReportPage} for the per-field semantics.
     */
    record ReportCriteria(UUID tenantId,
                          @Nullable LocalDate from,
                          @Nullable LocalDate to,
                          @Nullable UUID personId,
                          @Nullable UUID locationId,
                          boolean gliderFlights,
                          boolean motorFlights,
                          boolean towFlights) {}
}
