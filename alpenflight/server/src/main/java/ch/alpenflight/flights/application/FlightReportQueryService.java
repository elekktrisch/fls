package ch.alpenflight.flights.application;

import ch.alpenflight.flights.application.FlightReportDtos.FlightReportDataRecord;
import ch.alpenflight.flights.application.FlightReportDtos.FlightReportFilter;
import ch.alpenflight.flights.application.FlightReportDtos.FlightReportResult;
import ch.alpenflight.flights.application.FlightReportDtos.TowFlightReportDataRecord;
import ch.alpenflight.flights.domain.FlightAircraftType;
import ch.alpenflight.flights.domain.FlightAirState;
import ch.alpenflight.flights.domain.FlightCategory;
import ch.alpenflight.flights.domain.FlightProcessState;
import ch.alpenflight.flights.domain.FlightReportRepository;
import ch.alpenflight.flights.domain.FlightReportRepository.ReportRow;
import ch.alpenflight.platform.id.FlightId;
import ch.alpenflight.platform.tenancy.TenantContextCarrier;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-side query service for the flight-report screen (J-7 T-03). Pages +
 * filters the {@link ch.alpenflight.flights.domain.Flight} aggregate's data
 * through {@link FlightReportRepository} and assembles the wire DTOs.
 *
 * <p>Tenant scoping is explicit (ADR 0008): the caller's tenant is read from
 * {@link TenantContextCarrier} and passed to every query — correcting the
 * legacy tenancy hole ({@code FlightReportService.cs:114-125}) so a club-A
 * caller filtering by a club-B location sees no club-B flights.
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

    /** Default page size when the caller passes none (legacy canned reports request 1000 → clamped). */
    public static final int DEFAULT_PAGE_SIZE = 100;
    /** Hard cap on page size (oracle: paginate beyond 500, never silently truncate). */
    public static final int MAX_PAGE_SIZE = 500;

    private final FlightReportRepository repository;

    public FlightReportQueryService(FlightReportRepository repository) {
        this.repository = repository;
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
        UUID tenant = TenantContextCarrier.current().orElseThrow(() ->
                new IllegalStateException("No tenant in context for flight-report query"));
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
        return new FlightReportResult(items, total, List.of());
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
     * the new schema dropped it (t_start_type has only {@code code}). This is
     * the AlpenFlight stable code→int the report exposes — grounded in the V2
     * seed order, not a legacy id round-trip. Unknown / null ⇒ null.
     */
    private static @Nullable Integer startTypeLegacyInt(@Nullable String code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "WINCH_LAUNCH" -> 1;
            case "AEROTOW" -> 2;
            case "SELF_START" -> 3;
            case "EXTERNAL_START" -> 4;
            case "MOTOR" -> 5;
            default -> null;
        };
    }
}
