package ch.alpenflight.flights.application;

import ch.alpenflight.flights.application.FlightReportDtos.FlightReportDataRecord;
import ch.alpenflight.flights.application.FlightReportDtos.FlightReportFilter;
import ch.alpenflight.flights.application.FlightReportDtos.FlightReportResult;
import ch.alpenflight.flights.application.FlightReportDtos.FlightReportSummary;
import ch.alpenflight.flights.application.FlightReportDtos.TowFlightReportDataRecord;
import ch.alpenflight.flights.domain.FlightAircraftType;
import ch.alpenflight.flights.domain.FlightAirState;
import ch.alpenflight.flights.domain.FlightCategory;
import ch.alpenflight.flights.domain.FlightProcessState;
import ch.alpenflight.flights.domain.FlightReportRepository;
import ch.alpenflight.flights.domain.FlightReportRepository.ReportRow;
import ch.alpenflight.flights.domain.FlightReportRepository.SummaryRow;
import ch.alpenflight.platform.id.FlightId;
import ch.alpenflight.platform.tenancy.ClubTenantIdentifierResolver;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-side query service for the flight-report screen (J-7 T-03). Pages +
 * filters the {@link ch.alpenflight.flights.domain.Flight} aggregate's data
 * through {@link FlightReportRepository} and assembles the wire DTOs.
 *
 * <p>Tenant scoping is explicit (ADR 0008): the caller's tenant is resolved
 * via {@link ClubTenantIdentifierResolver} (the same resolver Hibernate's
 * {@code @TenantId} discriminator uses — JWT {@code clubId} on the HTTP path,
 * the {@code TenantContextCarrier} override in tests / {@code @WithTenant})
 * and passed to every query — correcting the legacy tenancy hole
 * ({@code FlightReportService.cs:114-125}) so a club-A caller filtering by a
 * club-B location sees no club-B flights.
 *
 * <p>Air-state + flight-duration + category are computed here (never stored).
 * {@code AirState} / {@code processState} are emitted as the legacy SMALLINT
 * codes ({@link FlightAirState#legacyCode()} /
 * {@link FlightProcessState#legacyCode()}) for wire + Excel parity.
 *
 * <p>Summary aggregation (T-04) and the controller endpoint (T-05) are out of
 * scope; {@link FlightReportResult#summaries()} is present-but-empty here.
 */
@Service
@Transactional(readOnly = true)
public class FlightReportQueryService {

    /** Legacy aircraft-type ints (FlightAircraftTypeValue: 1=glider, 2=tow, 4=motor). */
    private static final short TYPE_GLIDER = 1;
    private static final short TYPE_TOW = 2;
    private static final short TYPE_MOTOR = 4;

    /** Default page size when the caller passes none (legacy canned reports request 1000 → clamped). */
    public static final int DEFAULT_PAGE_SIZE = 100;
    /** Hard cap on page size (oracle: paginate beyond 500, never silently truncate). */
    public static final int MAX_PAGE_SIZE = 500;

    private final FlightReportRepository repository;
    private final ClubTenantIdentifierResolver tenantResolver;

    public FlightReportQueryService(FlightReportRepository repository,
                                    ClubTenantIdentifierResolver tenantResolver) {
        this.repository = repository;
        this.tenantResolver = tenantResolver;
    }

    /**
     * Returns one page of report rows for the caller's tenant.
     *
     * @param filter    the filter criteria (null ⇒ legacy defaults)
     * @param pageStart 0-based ROW offset (not a page number); clamped to {@code >= 0}
     * @param pageSize  page size; null ⇒ {@link #DEFAULT_PAGE_SIZE}; clamped to {@link #MAX_PAGE_SIZE}
     * @param sortByDuration true ⇒ sort by flight duration (the {@code FlightDuration}
     *                       sort-key remap → underlying seconds); false ⇒ default sort
     * @param sortAsc   sort direction (default ascending per legacy)
     */
    public FlightReportResult getReportPage(@Nullable FlightReportFilter filter,
                                            int pageStart,
                                            @Nullable Integer pageSize,
                                            boolean sortByDuration,
                                            boolean sortAsc) {
        FlightReportFilter f = filter != null ? filter : FlightReportFilter.defaults();
        UUID tenant = tenantResolver.resolveCurrentTenantIdentifier();
        if (ClubTenantIdentifierResolver.NO_TENANT.equals(tenant)) {
            throw new IllegalStateException("No tenant in context for flight-report query");
        }
        int offset = Math.max(0, pageStart);
        int limit = clampPageSize(pageSize);

        UUID personId = f.flightCrewPersonId() != null ? f.flightCrewPersonId().value() : null;
        UUID locationId = f.locationId() != null ? f.locationId().value() : null;
        FlightReportRepository.ReportCriteria criteria = new FlightReportRepository.ReportCriteria(
                tenant, f.flightDateFrom(), f.flightDateTo(), personId, locationId,
                f.gliderFlights(), f.motorFlights(), f.towFlights());

        long total = repository.countReport(criteria);
        List<ReportRow> rows = repository.findReportPage(criteria, offset, limit, sortByDuration, sortAsc);

        List<FlightReportDataRecord> items = new ArrayList<>(rows.size());
        for (ReportRow row : rows) {
            items.add(toRecord(row));
        }

        List<FlightReportSummary> summaries = computeSummaries(criteria);
        return new FlightReportResult(items, total, summaries);
    }

    /**
     * Computes the two mutually-exclusive summary branches (legacy
     * {@code FlightReportService.cs:188-730}) over ALL matched flights for the
     * filter (tenant-scoped). Person branch when a {@code personId} is set;
     * otherwise the location branch when a {@code locationId} is set; neither ⇒
     * empty.
     */
    private List<FlightReportSummary> computeSummaries(FlightReportRepository.ReportCriteria criteria) {
        if (criteria.personId() != null) {
            return personBranch(repository.findSummaryRows(criteria));
        }
        if (criteria.locationId() != null) {
            return locationBranch(repository.findSummaryRows(criteria), criteria.locationId());
        }
        return List.of();
    }

    /**
     * Person branch: up to 6 fixed-order rows (each present only when it has ≥1
     * flight) + an always-appended {@code Total}. Reproduces the legacy crew-role
     * splits ({@code FlightReportService.cs:190-624}); the J-7 correction sets
     * {@code totalFlights} on ALL rows (legacy omits it on Pilot Motor/Towing).
     */
    private static List<FlightReportSummary> personBranch(List<SummaryRow> rows) {
        Accumulator pilotGlider = new Accumulator();
        Accumulator pilotMotor = new Accumulator();
        Accumulator pilotTow = new Accumulator();
        Accumulator copilot = new Accumulator();
        Accumulator instructor = new Accumulator();
        Accumulator instructorSolo = new Accumulator();

        for (SummaryRow r : rows) {
            if (r.isPilotOrStudent()) {
                switch (r.aircraftType()) {
                    case TYPE_GLIDER -> pilotGlider.addPerson(r);
                    case TYPE_MOTOR -> pilotMotor.addPerson(r);
                    case TYPE_TOW -> pilotTow.addPerson(r);
                    default -> { /* other aircraft types not split by pilot row */ }
                }
            }
            if (r.isCoPilot()) {
                copilot.addPerson(r);
            }
            if (r.isFlightInstructor()) {
                if (r.soloFlight()) {
                    instructorSolo.addPerson(r);
                } else {
                    instructor.addPerson(r);
                }
            }
        }

        List<FlightReportSummary> out = new ArrayList<>();
        addIfNonEmpty(out, "Pilot (Glider)", pilotGlider);
        addIfNonEmpty(out, "Pilot (Motor)", pilotMotor);
        addIfNonEmpty(out, "Pilot (Towing)", pilotTow);
        addIfNonEmpty(out, "Copilot", copilot);
        addIfNonEmpty(out, "Instructor", instructor);
        addIfNonEmpty(out, "Instructor (Soloflights)", instructorSolo);
        out.add(totalRow(out));
        return out;
    }

    /**
     * Location branch: one row per {@code FlightTypeName} (alphabetical) + a
     * {@code Total} ({@code FlightReportService.cs:626-727}). Starts/ldgs use the
     * location-aware 4-term formula distinguishing same-airfield / fly-in
     * ({@code nrOfLdgs-1}) / fly-out cases.
     */
    private static List<FlightReportSummary> locationBranch(List<SummaryRow> rows, UUID locationId) {
        TreeMap<String, Accumulator> byType = new TreeMap<>();
        for (SummaryRow r : rows) {
            String key = r.flightTypeName() == null ? "" : r.flightTypeName();
            byType.computeIfAbsent(key, k -> new Accumulator()).addLocation(r, locationId);
        }
        List<FlightReportSummary> out = new ArrayList<>();
        for (Map.Entry<String, Accumulator> e : byType.entrySet()) {
            out.add(e.getValue().toSummary(e.getKey()));
        }
        out.add(totalRow(out));
        return out;
    }

    private static void addIfNonEmpty(List<FlightReportSummary> out, String label, Accumulator acc) {
        if (acc.flights > 0) {
            out.add(acc.toSummary(label));
        }
    }

    private static FlightReportSummary totalRow(List<FlightReportSummary> rows) {
        int starts = 0;
        int ldgs = 0;
        int flights = 0;
        long seconds = 0;
        for (FlightReportSummary s : rows) {
            starts += s.totalStarts();
            ldgs += s.totalLdgs();
            flights += s.totalFlights();
            seconds += s.totalFlightDuration().toSeconds();
        }
        return new FlightReportSummary("Total", starts, ldgs, flights, Duration.ofSeconds(seconds));
    }

    /** Mutable per-group accumulator for the summary branches. */
    private static final class Accumulator {
        private int starts;
        private int ldgs;
        private int flights;
        private long seconds;

        /**
         * Person-branch fold ({@code FlightReportService.cs:244-251}):
         * {@code ldgs = Σ(nrOfLdgs ?? (noLdg?1:0)) + Σ(nrOfLdgsOnStartLocation ?? 0)};
         * {@code starts = Σ(nrOfLdgs ?? (noStart?1:0)) + Σ(nrOfLdgsOnStartLocation ?? 0)}
         * — the {@code starts} base is {@code nrOfLdgs} (legacy INTENDED quirk).
         */
        void addPerson(SummaryRow r) {
            int onStart = r.nrOfLdgsOnStartLocation() != null ? r.nrOfLdgsOnStartLocation() : 0;
            ldgs += ldgBase(r, r.noLdgTimeInformation()) + onStart;
            starts += ldgBase(r, r.noStartTimeInformation()) + onStart;
            flights++;
            seconds += r.durationSeconds();
        }

        /**
         * Location-branch fold ({@code FlightReportService.cs:683-691}). Landings
         * count only when {@code ldgLocationId == locationId}; outlandings (start
         * location) add {@code nrOfLdgsOnStartLocation}. Starts use the 4-term
         * formula: same-airfield, fly-in ({@code nrOfLdgs-1}), fly-out, plus the
         * outlandings term.
         */
        void addLocation(SummaryRow r, UUID loc) {
            boolean startHere = Objects.equals(r.startLocationId(), loc);
            boolean ldgHere = Objects.equals(r.ldgLocationId(), loc);
            int onStart = r.nrOfLdgsOnStartLocation() != null ? r.nrOfLdgsOnStartLocation() : 0;

            ldgs += (ldgHere ? ldgBase(r, r.noLdgTimeInformation()) : 0)
                    + (startHere ? onStart : 0);
            starts += locationStarts(r, startHere, ldgHere) + (startHere ? onStart : 0);
            flights++;
            seconds += r.durationSeconds();
        }

        /**
         * Location-branch starts (the nrOfLdgs-based terms, sans the outlandings
         * term) per {@code FlightReportService.cs:685-687}: same-airfield uses
         * {@code nrOfLdgs} (noStart fallback), fly-in uses {@code nrOfLdgs-1},
         * fly-out uses {@code nrOfLdgs}. Exactly one branch applies to a flight
         * that touches the location.
         */
        private static int locationStarts(SummaryRow r, boolean startHere, boolean ldgHere) {
            boolean hasNr = r.nrOfLdgs() != null;
            int nr = hasNr ? r.nrOfLdgs() : 0;
            if (startHere && ldgHere) {
                return hasNr ? nr : (r.noStartTimeInformation() ? 1 : 0);
            }
            if (ldgHere) { // fly-in (start elsewhere)
                return hasNr ? nr - 1 : 0;
            }
            if (startHere) { // fly-out (ldg elsewhere)
                return nr;
            }
            return 0;
        }

        /** {@code nrOfLdgs ?? (fallbackFlag ? 1 : 0)} — the shared ldg/start base term. */
        private static int ldgBase(SummaryRow r, boolean fallbackFlag) {
            if (r.nrOfLdgs() != null) {
                return r.nrOfLdgs();
            }
            return fallbackFlag ? 1 : 0;
        }

        FlightReportSummary toSummary(String label) {
            return new FlightReportSummary(label, starts, ldgs, flights, Duration.ofSeconds(seconds));
        }
    }

    private static int clampPageSize(@Nullable Integer pageSize) {
        if (pageSize == null || pageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private static FlightReportDataRecord toRecord(ReportRow row) {
        FlightAircraftType type = FlightAircraftType.fromLegacyId(row.flightAircraftTypeLegacyId());
        int airState = FlightAirState.compute(row.ldgDateTime(), row.startDateTime(),
                row.noLdgTimeInformation(), row.noStartTimeInformation(),
                row.flightPlanOpenedOn()).legacyCode();
        int processState = FlightProcessState.fromId(row.processStateId()).legacyCode();

        return new FlightReportDataRecord(
                FlightId.of(row.flightId()),
                row.flightDate(),
                row.immatriculation(),
                row.pilotName(),
                row.secondCrewName(),
                row.comment(),
                airState,
                processState,
                row.flightCode(),
                row.flightTypeName(),
                row.startDateTime(),
                row.ldgDateTime(),
                duration(row.startDateTime(), row.ldgDateTime()),
                row.soloFlight(),
                startTypeLegacyInt(row.startTypeCode()),
                row.startLocation(),
                row.ldgLocation(),
                FlightCategory.of(type),
                FlightId.ofNullable(row.towedGliderFlightId()),
                towRecord(row));
    }

    private static @Nullable TowFlightReportDataRecord towRecord(ReportRow row) {
        if (row.towFlightId() == null) {
            return null;
        }
        int airState = FlightAirState.compute(row.towLdgDateTime(), row.towStartDateTime(),
                row.towNoLdgTimeInformation(), row.towNoStartTimeInformation(),
                row.towFlightPlanOpenedOn()).legacyCode();
        int processState = row.towProcessStateId() != null
                ? FlightProcessState.fromId(row.towProcessStateId()).legacyCode()
                : 0;
        return new TowFlightReportDataRecord(
                FlightId.of(row.towFlightId()),
                row.towImmatriculation(),
                row.towPilotName(),
                row.towFlightCode(),
                row.towFlightTypeName(),
                row.towStartDateTime(),
                row.towLdgDateTime(),
                row.towStartLocation(),
                row.towLdgLocation(),
                duration(row.towStartDateTime(), row.towLdgDateTime()),
                airState,
                processState);
    }

    private static @Nullable Duration duration(@Nullable Instant start, @Nullable Instant ldg) {
        if (start == null || ldg == null) {
            return null;
        }
        return Duration.between(start, ldg);
    }

    /**
     * Maps a {@code t_start_type.code} to the report's {@code StartType} int.
     * Legacy carried a per-club {@code StartTypeId} DB int with no fixed enum;
     * the new schema dropped it (t_start_type has only {@code code}). For Excel
     * parity this maps each code to the legacy {@code AircraftStartType} enum int
     * by SEMANTIC correspondence (flsserver {@code Enums/AircraftStartType.cs}):
     * TowingByAircraft=1, WinchLaunch=2, SelfStart=3, ExternalStart=4,
     * MotorFlightStart=5 — NOT the V2 seed order. Unknown / null ⇒ null.
     */
    private static @Nullable Integer startTypeLegacyInt(@Nullable String code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "AEROTOW" -> 1;        // legacy AircraftStartType.TowingByAircraft
            case "WINCH_LAUNCH" -> 2;   // legacy AircraftStartType.WinchLaunch
            case "SELF_START" -> 3;     // legacy AircraftStartType.SelfStart
            case "EXTERNAL_START" -> 4; // legacy AircraftStartType.ExternalStart
            case "MOTOR" -> 5;          // legacy AircraftStartType.MotorFlightStart
            default -> null;
        };
    }
}
