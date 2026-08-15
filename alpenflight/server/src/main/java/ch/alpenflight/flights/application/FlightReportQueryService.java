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

@Service
@Transactional(readOnly = true)
public class FlightReportQueryService {

    private static final short LEGACY_AIRCRAFT_TYPE_GLIDER = 1;
    private static final short LEGACY_AIRCRAFT_TYPE_TOW = 2;
    private static final short LEGACY_AIRCRAFT_TYPE_MOTOR = 4;

    private static final int LEGACY_START_TYPE_TOWING_BY_AIRCRAFT = 1;
    private static final int LEGACY_START_TYPE_WINCH_LAUNCH = 2;
    private static final int LEGACY_START_TYPE_SELF_START = 3;
    private static final int LEGACY_START_TYPE_EXTERNAL_START = 4;
    private static final int LEGACY_START_TYPE_MOTOR_FLIGHT_START = 5;

    public static final int DEFAULT_PAGE_SIZE = 100;
    public static final int MAX_PAGE_SIZE = 500;

    private final FlightReportRepository repository;
    private final ClubTenantIdentifierResolver tenantResolver;

    public FlightReportQueryService(FlightReportRepository repository,
                                    ClubTenantIdentifierResolver tenantResolver) {
        this.repository = repository;
        this.tenantResolver = tenantResolver;
    }

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

    private List<FlightReportSummary> computeSummaries(FlightReportRepository.ReportCriteria criteria) {
        if (criteria.personId() != null) {
            return personBranch(repository.findSummaryRows(criteria));
        }
        if (criteria.locationId() != null) {
            return locationBranch(repository.findSummaryRows(criteria), criteria.locationId());
        }
        return List.of();
    }

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
                    case LEGACY_AIRCRAFT_TYPE_GLIDER -> pilotGlider.addPerson(r);
                    case LEGACY_AIRCRAFT_TYPE_MOTOR -> pilotMotor.addPerson(r);
                    case LEGACY_AIRCRAFT_TYPE_TOW -> pilotTow.addPerson(r);
                    default -> { }
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

    private static final class Accumulator {
        private int starts;
        private int ldgs;
        private int flights;
        private long seconds;

        void addPerson(SummaryRow r) {
            int ldgsOnStartLocation = r.nrOfLdgsOnStartLocation() != null ? r.nrOfLdgsOnStartLocation() : 0;
            ldgs += ldgBaseAlsoUsedForStarts(r, r.noLdgTimeInformation()) + ldgsOnStartLocation;
            starts += ldgBaseAlsoUsedForStarts(r, r.noStartTimeInformation()) + ldgsOnStartLocation;
            flights++;
            seconds += r.durationSeconds();
        }

        void addLocation(SummaryRow r, UUID reportLocationId) {
            boolean startedAtReportLocation = Objects.equals(r.startLocationId(), reportLocationId);
            boolean landedAtReportLocation = Objects.equals(r.ldgLocationId(), reportLocationId);
            int ldgsOnStartLocation = r.nrOfLdgsOnStartLocation() != null ? r.nrOfLdgsOnStartLocation() : 0;

            ldgs += (landedAtReportLocation ? ldgBaseAlsoUsedForStarts(r, r.noLdgTimeInformation()) : 0)
                    + (startedAtReportLocation ? ldgsOnStartLocation : 0);
            starts += locationStarts(r, startedAtReportLocation, landedAtReportLocation)
                    + (startedAtReportLocation ? ldgsOnStartLocation : 0);
            flights++;
            seconds += r.durationSeconds();
        }

        private static int locationStarts(SummaryRow r,
                                          boolean startedAtReportLocation,
                                          boolean landedAtReportLocation) {
            boolean hasNr = r.nrOfLdgs() != null;
            int nr = hasNr ? r.nrOfLdgs() : 0;
            boolean sameAirfield = startedAtReportLocation && landedAtReportLocation;
            boolean flewIn = !startedAtReportLocation && landedAtReportLocation;
            boolean flewOut = startedAtReportLocation && !landedAtReportLocation;
            if (sameAirfield) {
                return hasNr ? nr : (r.noStartTimeInformation() ? 1 : 0);
            }
            if (flewIn) {
                return hasNr ? nr - 1 : 0;
            }
            if (flewOut) {
                return nr;
            }
            return 0;
        }

        private static int ldgBaseAlsoUsedForStarts(SummaryRow r,
                                                    boolean countsAsOneWhenNoTimeInformation) {
            if (r.nrOfLdgs() != null) {
                return r.nrOfLdgs();
            }
            return countsAsOneWhenNoTimeInformation ? 1 : 0;
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

    private static @Nullable Integer startTypeLegacyInt(@Nullable String code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "AEROTOW" -> LEGACY_START_TYPE_TOWING_BY_AIRCRAFT;
            case "WINCH_LAUNCH" -> LEGACY_START_TYPE_WINCH_LAUNCH;
            case "SELF_START" -> LEGACY_START_TYPE_SELF_START;
            case "EXTERNAL_START" -> LEGACY_START_TYPE_EXTERNAL_START;
            case "MOTOR" -> LEGACY_START_TYPE_MOTOR_FLIGHT_START;
            default -> null;
        };
    }
}
