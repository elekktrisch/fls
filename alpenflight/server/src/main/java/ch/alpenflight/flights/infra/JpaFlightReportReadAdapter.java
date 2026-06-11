package ch.alpenflight.flights.infra;

import ch.alpenflight.flights.domain.FlightAircraftType;
import ch.alpenflight.flights.domain.FlightCrewTypeIds;
import ch.alpenflight.flights.domain.FlightReportCrewEntry;
import ch.alpenflight.flights.domain.FlightReportRepository;
import ch.alpenflight.flights.domain.FlightReportRow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/**
 * Plain-JPA implementation of {@link FlightReportRepository} over the
 * domain-maintained read-model ({@link FlightReportRow} + its
 * {@link FlightReportCrewEntry} children, ADR 0027 §2). Replaces the retired
 * native-SQL adapter (J-7 RM-3; register entry {@code flight-report-read-model}
 * retired): every statement is HQL against the read-model entities, so tenant
 * scoping is STRUCTURAL — Hibernate's {@code @TenantId} discriminator on
 * {@code t_flight_report_row.operating_club_id} constrains every query; no
 * manual tenant predicate, nothing to register.
 *
 * <p>Decoration columns (immatriculation, pilot / second-crew names,
 * flight-type name/code, location names, the whole tow block) are denormalized
 * copies written by {@code FlightReportProjector} at mutation time — the read
 * path joins nothing beyond the crew child (person filter + person-role
 * summary flags). Air-state is never stored: the query service computes it
 * from the raw timestamp/flag columns ({@code FlightAirState.compute}).
 */
@Repository
class JpaFlightReportReadAdapter implements FlightReportRepository {

    private final EntityManager em;

    JpaFlightReportReadAdapter(EntityManager em) {
        this.em = em;
    }

    @Override
    public List<ReportRow> findReportPage(ReportCriteria c,
                                          int offset,
                                          int limit,
                                          boolean sortBySeconds,
                                          boolean sortAsc) {
        List<FlightAircraftType> types = selectedTypes(c);
        if (types.isEmpty()) {
            return List.of();
        }
        String dir = sortAsc ? "asc" : "desc";
        // Oracle sort: start_date_time (or precomputed duration_seconds) with
        // NULLS LAST, immatriculation asc tiebreak (asc default = nulls last).
        String orderBy = sortBySeconds
                ? " order by r.durationSeconds " + dir + " nulls last, r.immatriculation asc"
                : " order by r.startDateTime " + dir + " nulls last, r.immatriculation asc";
        TypedQuery<FlightReportRow> q = query(
                "select r from FlightReportRow r", orderBy, c, types, FlightReportRow.class);
        q.setFirstResult(offset);
        q.setMaxResults(limit);

        List<ReportRow> out = new ArrayList<>();
        for (FlightReportRow row : q.getResultList()) {
            out.add(toReportRow(row));
        }
        return out;
    }

    @Override
    public long countReport(ReportCriteria c) {
        List<FlightAircraftType> types = selectedTypes(c);
        if (types.isEmpty()) {
            return 0L;
        }
        return query("select count(r) from FlightReportRow r", "", c, types, Long.class)
                .getSingleResult();
    }

    @Override
    public List<SummaryRow> findSummaryRows(ReportCriteria c) {
        List<FlightAircraftType> types = selectedTypes(c);
        if (types.isEmpty()) {
            return List.of();
        }
        // The person-role flags read the crew children — fetch them with the
        // rows only when a person filter is set (flags are constant false
        // otherwise and the lazy collection is never touched).
        String select = c.personId() != null
                ? "select r from FlightReportRow r left join fetch r.crew"
                : "select r from FlightReportRow r";
        List<FlightReportRow> rows =
                query(select, "", c, types, FlightReportRow.class).getResultList();

        List<SummaryRow> out = new ArrayList<>(rows.size());
        for (FlightReportRow row : rows) {
            out.add(toSummaryRow(row, c.personId()));
        }
        return out;
    }

    /**
     * Builds + binds the shared filtered query ({@code selectFrom} + WHERE +
     * {@code suffix}) — single seam for page / count / summary so the filter
     * predicates and parameter binding live in exactly one place. The tenant
     * predicate is NOT built here: {@code @TenantId} on {@link FlightReportRow}
     * applies it structurally.
     */
    private <T> TypedQuery<T> query(String selectFrom,
                                    String suffix,
                                    ReportCriteria c,
                                    List<FlightAircraftType> types,
                                    Class<T> resultType) {
        TypedQuery<T> q = em.createQuery(selectFrom + where(c) + suffix, resultType);
        q.setParameter("types", types);
        if (c.from() != null) {
            q.setParameter("from", c.from());
        }
        if (c.to() != null) {
            q.setParameter("to", c.to());
        }
        if (c.locationId() != null) {
            q.setParameter("locationId", c.locationId());
        }
        if (c.personId() != null) {
            q.setParameter("personId", c.personId());
            q.setParameter("personRoles", PERSON_FILTER_CREW_TYPES);
        }
        return q;
    }

    private static String where(ReportCriteria c) {
        StringBuilder w = new StringBuilder(" where r.flightAircraftType in :types");
        if (c.from() != null) {
            w.append(" and r.flightDate is not null and r.flightDate >= :from");
        }
        if (c.to() != null) {
            w.append(" and r.flightDate is not null and r.flightDate <= :to");
        }
        if (c.locationId() != null) {
            w.append(" and (r.startLocationId = :locationId or r.ldgLocationId = :locationId)");
        }
        if (c.personId() != null) {
            w.append(" and exists (select 1 from FlightReportCrewEntry e")
             .append(" where e.row = r and e.personId = :personId")
             .append(" and e.flightCrewTypeId in :personRoles)");
        }
        return w.toString();
    }

    /** Empty ⇒ all type flags off ⇒ caller short-circuits to an empty result. */
    private static List<FlightAircraftType> selectedTypes(ReportCriteria c) {
        List<FlightAircraftType> types = new ArrayList<>(3);
        if (c.gliderFlights()) {
            types.add(FlightAircraftType.GLIDER);
        }
        if (c.motorFlights()) {
            types.add(FlightAircraftType.MOTOR);
        }
        if (c.towFlights()) {
            types.add(FlightAircraftType.TOW);
        }
        return types;
    }

    private static ReportRow toReportRow(FlightReportRow r) {
        return new ReportRow(
                r.getFlightId(),
                r.getFlightAircraftType().legacyId(),
                r.getFlightDate(),
                r.getStartDateTime(),
                r.getLdgDateTime(),
                r.isNoStartTimeInformation(),
                r.isNoLdgTimeInformation(),
                r.getFlightPlanOpenedOn(),
                r.getProcessStateId(),
                r.isSoloFlight(),
                r.getComment(),
                r.getStartTypeCode(),
                r.getImmatriculation(),
                r.getPilotName(),
                r.getSecondCrewName(),
                r.getFlightCode(),
                r.getFlightTypeName(),
                r.getStartLocationName(),
                r.getLdgLocationName(),
                r.getTowedGliderFlightId(),
                r.getTowFlightId(),
                r.getTowStartDateTime(),
                r.getTowLdgDateTime(),
                Boolean.TRUE.equals(r.getTowNoStartTimeInformation()),
                Boolean.TRUE.equals(r.getTowNoLdgTimeInformation()),
                r.getTowFlightPlanOpenedOn(),
                r.getTowProcessStateId(),
                r.getTowImmatriculation(),
                r.getTowPilotName(),
                r.getTowFlightCode(),
                r.getTowFlightTypeName(),
                r.getTowStartLocationName(),
                r.getTowLdgLocationName());
    }

    private static SummaryRow toSummaryRow(FlightReportRow r, @Nullable UUID personId) {
        Long duration = r.getDurationSeconds();
        return new SummaryRow(
                r.getFlightAircraftType().legacyId(),
                r.isSoloFlight(),
                r.getNrOfLdgs(),
                r.getNrOfLdgsOnStartLocation(),
                r.isNoStartTimeInformation(),
                r.isNoLdgTimeInformation(),
                r.getStartLocationId(),
                r.getLdgLocationId(),
                duration == null ? 0L : duration,
                r.getFlightTypeName(),
                hasRole(r, personId, FlightCrewTypeIds.PILOT_OR_STUDENT),
                hasRole(r, personId, FlightCrewTypeIds.CO_PILOT),
                hasRole(r, personId, FlightCrewTypeIds.FLIGHT_INSTRUCTOR));
    }

    /**
     * Does the FILTER person hold the given crew role on this flight? Constant
     * {@code false} when no person filter — the lazy crew collection is then
     * never touched (the summary person-role split is only meaningful under a
     * person filter, matching the retired oracle's constant-false columns).
     */
    private static boolean hasRole(FlightReportRow row,
                                   @Nullable UUID personId,
                                   UUID crewTypeId) {
        if (personId == null) {
            return false;
        }
        for (FlightReportCrewEntry entry : row.getCrew()) {
            if (personId.equals(entry.getPersonId())
                    && crewTypeId.equals(entry.getFlightCrewTypeId())) {
                return true;
            }
        }
        return false;
    }
}
