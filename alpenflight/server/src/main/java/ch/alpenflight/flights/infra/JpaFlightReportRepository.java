package ch.alpenflight.flights.infra;

import ch.alpenflight.flights.domain.FlightAircraftType;
import ch.alpenflight.flights.domain.FlightCrewTypeIds;
import ch.alpenflight.flights.domain.FlightReportRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

/**
 * Native-SQL implementation of {@link FlightReportRepository}. Registered in
 * {@code alpenflight/database/native-sql-register.md}
 * ({@code flight-report-read-model}) because it reads tenant-scoped tables
 * (t_flight, t_flight_crew, t_aircraft, t_person, t_location, t_flight_type)
 * outside the {@code @TenantId} filter.
 *
 * <p>Why native and not JPQL: the report must surface decoration columns
 * REGARDLESS of their own tenant (charter aircraft S-058, cross-tenant crew
 * Person S-051, the legacy cross-club location report). JPQL would apply each
 * joined entity's {@code @TenantId} discriminator and hide them. The matched
 * flight is constrained explicitly by {@code operating_club_id = :tenant}
 * (the J-7 tenancy-hole correction); decoration joins carry no tenant
 * predicate by design.
 *
 * <p>Air-state is NOT selected — the service computes it from the timestamp/
 * flag columns (sacred-cow: never stored). Pilot / second-crew names + the
 * tow-pilot name are correlated subqueries; the second-crew pick orders by the
 * crew-type {@code legacy_int_id} (lowest of CoPilot / Instructor / Passenger /
 * Observer) to match legacy {@code FlightReportService.cs:84-88}.
 */
@Repository
class JpaFlightReportRepository implements FlightReportRepository {

    private static final String PILOT_TYPE = literal(FlightCrewTypeIds.PILOT_OR_STUDENT);
    private static final String SECOND_CREW_TYPES = literals(
            FlightCrewTypeIds.CO_PILOT,
            FlightCrewTypeIds.FLIGHT_INSTRUCTOR,
            FlightCrewTypeIds.PASSENGER,
            FlightCrewTypeIds.OBSERVER);
    private static final String PERSON_FILTER_TYPES = literals(
            FlightCrewTypeIds.PILOT_OR_STUDENT,
            FlightCrewTypeIds.CO_PILOT,
            FlightCrewTypeIds.FLIGHT_INSTRUCTOR);

    /** Quoted SQL literal for a canonical seed UUID (compile-time constant, not user input). */
    private static String literal(UUID id) {
        return "'" + id + "'";
    }

    private static String literals(UUID... ids) {
        StringBuilder out = new StringBuilder();
        for (UUID id : ids) {
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(literal(id));
        }
        return out.toString();
    }

    /** Pilot name as "Lastname Firstname" for a given flight alias. */
    private static String pilotNameSubselect(String flightAlias) {
        return "(select p.lastname || ' ' || p.firstname from t_flight_crew fc"
                + " join t_person p on p.id = fc.person_id"
                + " where fc.flight_id = " + flightAlias + ".id and fc.deleted_on is null"
                + " and fc.flight_crew_type_id = " + PILOT_TYPE + " limit 1)";
    }

    private final EntityManager em;

    JpaFlightReportRepository(EntityManager em) {
        this.em = em;
    }

    @Override
    public List<ReportRow> findReportPage(ReportCriteria c,
                                          int offset,
                                          int limit,
                                          boolean sortBySeconds,
                                          boolean sortAsc) {
        String typePredicate = typePredicate(c.gliderFlights(), c.motorFlights(), c.towFlights());
        if (typePredicate == null) {
            return List.of();
        }
        String dir = sortAsc ? "asc" : "desc";
        String orderBy = sortBySeconds
                ? "order by extract(epoch from (f.ldg_date_time - f.start_date_time)) "
                        + dir + " nulls last, ac.immatriculation asc"
                : "order by f.start_date_time " + dir + " nulls last, ac.immatriculation asc";

        StringBuilder sql = new StringBuilder();
        sql.append("select")
           .append("  f.id, f.flight_aircraft_type_id, f.flight_date,")
           .append("  f.start_date_time, f.ldg_date_time,")
           .append("  f.no_start_time_information, f.no_ldg_time_information,")
           .append("  f.flight_plan_opened_on, f.process_state_id, f.is_solo_flight, f.comment,")
           .append("  st.code,")
           .append("  ac.immatriculation,")
           .append("  ").append(pilotNameSubselect("f")).append(",")
           .append("  (select p.lastname || ' ' || p.firstname from t_flight_crew fc")
           .append("     join t_person p on p.id = fc.person_id")
           .append("     join t_flight_crew_type ct on ct.id = fc.flight_crew_type_id")
           .append("     where fc.flight_id = f.id and fc.deleted_on is null")
           .append("       and fc.flight_crew_type_id in (").append(SECOND_CREW_TYPES).append(")")
           .append("     order by ct.legacy_int_id asc limit 1),")
           .append("  ft.flight_code, ft.flight_type_name,")
           .append("  sl.location_name, ll.location_name,")
           .append("  (select tg.id from t_flight tg")
           .append("     where tg.tow_flight_id = f.id and tg.deleted_on is null limit 1),")
           // Tow block.
           .append("  tw.id, tw.start_date_time, tw.ldg_date_time,")
           .append("  tw.no_start_time_information, tw.no_ldg_time_information,")
           .append("  tw.flight_plan_opened_on, tw.process_state_id,")
           .append("  twac.immatriculation,")
           .append("  ").append(pilotNameSubselect("tw")).append(",")
           .append("  twft.flight_code, twft.flight_type_name,")
           .append("  twsl.location_name, twll.location_name")
           .append(" from t_flight f")
           .append(" left join t_aircraft ac on ac.id = f.aircraft_id")
           .append(" left join t_flight_type ft on ft.id = f.flight_type_id")
           .append(" left join t_start_type st on st.id = f.start_type_id")
           .append(" left join t_location sl on sl.id = f.start_location_id")
           .append(" left join t_location ll on ll.id = f.ldg_location_id")
           .append(" left join t_flight tw on tw.id = f.tow_flight_id and tw.deleted_on is null")
           .append(" left join t_aircraft twac on twac.id = tw.aircraft_id")
           .append(" left join t_flight_type twft on twft.id = tw.flight_type_id")
           .append(" left join t_location twsl on twsl.id = tw.start_location_id")
           .append(" left join t_location twll on twll.id = tw.ldg_location_id");
        Query q = prepare(sql, typePredicate, c,
                ' ' + orderBy + " offset :offset limit :limit");
        q.setParameter("offset", offset);
        q.setParameter("limit", limit);

        List<ReportRow> out = new ArrayList<>();
        for (Object raw : q.getResultList()) {
            out.add(toRow((Object[]) raw));
        }
        return out;
    }

    @Override
    public long countReport(ReportCriteria c) {
        String typePredicate = typePredicate(c.gliderFlights(), c.motorFlights(), c.towFlights());
        if (typePredicate == null) {
            return 0L;
        }
        StringBuilder sql = new StringBuilder("select count(*) from t_flight f");
        Query q = prepare(sql, typePredicate, c, "");
        Object n = q.getSingleResult();
        return ((Number) n).longValue();
    }

    @Override
    public List<SummaryRow> findSummaryRows(ReportCriteria c) {
        String typePredicate = typePredicate(c.gliderFlights(), c.motorFlights(), c.towFlights());
        if (typePredicate == null) {
            return List.of();
        }
        // Person-role flags: true when the filter's person holds that role on the
        // flight. Constant false when no person filter (no personId bound).
        String pilotFlag = roleExists(c.personId(), PILOT_TYPE);
        String coPilotFlag = roleExists(c.personId(), literal(FlightCrewTypeIds.CO_PILOT));
        String instructorFlag = roleExists(c.personId(), literal(FlightCrewTypeIds.FLIGHT_INSTRUCTOR));

        StringBuilder sql = new StringBuilder();
        sql.append("select")
           .append("  f.flight_aircraft_type_id, f.is_solo_flight,")
           .append("  f.nr_of_ldgs, f.nr_of_ldgs_on_start_location,")
           .append("  f.no_start_time_information, f.no_ldg_time_information,")
           .append("  f.start_location_id, f.ldg_location_id,")
           .append("  coalesce(extract(epoch from (f.ldg_date_time - f.start_date_time)), 0),")
           .append("  ft.flight_type_name,")
           .append("  ").append(pilotFlag).append(",")
           .append("  ").append(coPilotFlag).append(",")
           .append("  ").append(instructorFlag)
           .append(" from t_flight f")
           .append(" left join t_flight_type ft on ft.id = f.flight_type_id");
        Query q = prepare(sql, typePredicate, c, "");

        List<SummaryRow> out = new ArrayList<>();
        for (Object raw : q.getResultList()) {
            out.add(toSummaryRow((Object[]) raw));
        }
        return out;
    }

    /**
     * SQL boolean: does {@code :personId} hold the given crew role on flight
     * {@code f}? Constant {@code false} when no person filter (the summary
     * person-role split is only meaningful under a person filter).
     */
    private static String roleExists(@Nullable UUID personId, String crewTypeLiteral) {
        if (personId == null) {
            return "false";
        }
        return "exists (select 1 from t_flight_crew fc where fc.flight_id = f.id"
                + " and fc.deleted_on is null and fc.person_id = :personId"
                + " and fc.flight_crew_type_id = " + crewTypeLiteral + ")";
    }

    private static SummaryRow toSummaryRow(Object[] r) {
        int i = 0;
        short typeId = ((Number) r[i++]).shortValue();
        boolean solo = asBool(r[i++]);
        Short nrOfLdgs = asShort(r[i++]);
        Short nrOfLdgsOnStart = asShort(r[i++]);
        boolean noStart = asBool(r[i++]);
        boolean noLdg = asBool(r[i++]);
        UUID startLoc = asUuid(r[i++]);
        UUID ldgLoc = asUuid(r[i++]);
        long durationSeconds = ((Number) r[i++]).longValue();
        String flightTypeName = asString(r[i++]);
        boolean isPilot = asBool(r[i++]);
        boolean isCoPilot = asBool(r[i++]);
        boolean isInstructor = asBool(r[i]);
        return new SummaryRow(typeId, solo, nrOfLdgs, nrOfLdgsOnStart, noStart, noLdg,
                startLoc, ldgLoc, durationSeconds, flightTypeName,
                isPilot, isCoPilot, isInstructor);
    }

    private static @Nullable Short asShort(@Nullable Object o) {
        return o == null ? null : ((Number) o).shortValue();
    }

    /** Null ⇒ all flags off ⇒ caller short-circuits to an empty result. */
    private static @Nullable String typePredicate(boolean glider, boolean motor, boolean tow) {
        List<Integer> types = new ArrayList<>();
        if (glider) {
            types.add((int) FlightAircraftType.GLIDER.legacyId());
        }
        if (motor) {
            types.add((int) FlightAircraftType.MOTOR.legacyId());
        }
        if (tow) {
            types.add((int) FlightAircraftType.TOW.legacyId());
        }
        if (types.isEmpty()) {
            return null;
        }
        StringBuilder p = new StringBuilder("f.flight_aircraft_type_id in (");
        for (int i = 0; i < types.size(); i++) {
            if (i > 0) {
                p.append(',');
            }
            p.append(types.get(i));
        }
        return p.append(')').toString();
    }

    /**
     * Appends the shared tenant-scoped WHERE clause + an optional {@code suffix}
     * (order/limit) to {@code sql}, then builds the native query and binds the
     * filter parameters. Single seam for the page / count / summary queries so
     * the tenant predicate + parameter binding live in exactly one place.
     */
    private Query prepare(StringBuilder sql, String typePredicate, ReportCriteria c, String suffix) {
        appendWhere(sql, typePredicate, c.from(), c.to(), c.personId(), c.locationId());
        sql.append(suffix);
        Query q = em.createNativeQuery(sql.toString());
        bindWhere(q, c.tenantId(), c.from(), c.to(), c.personId(), c.locationId());
        return q;
    }

    private static void appendWhere(StringBuilder sql,
                                    String typePredicate,
                                    @Nullable LocalDate from,
                                    @Nullable LocalDate to,
                                    @Nullable UUID personId,
                                    @Nullable UUID locationId) {
        sql.append(" where f.deleted_on is null")
           .append(" and f.operating_club_id = :tenant")
           .append(" and ").append(typePredicate);
        if (from != null) {
            sql.append(" and f.flight_date is not null and f.flight_date >= :from");
        }
        if (to != null) {
            sql.append(" and f.flight_date is not null and f.flight_date <= :to");
        }
        if (locationId != null) {
            sql.append(" and (f.start_location_id = :locationId"
                    + " or f.ldg_location_id = :locationId)");
        }
        if (personId != null) {
            sql.append(" and exists (select 1 from t_flight_crew fc")
               .append("   where fc.flight_id = f.id and fc.deleted_on is null")
               .append("     and fc.person_id = :personId")
               .append("     and fc.flight_crew_type_id in (").append(PERSON_FILTER_TYPES).append("))");
        }
    }

    private static void bindWhere(Query q,
                                  UUID tenantId,
                                  @Nullable LocalDate from,
                                  @Nullable LocalDate to,
                                  @Nullable UUID personId,
                                  @Nullable UUID locationId) {
        q.setParameter("tenant", tenantId);
        if (from != null) {
            q.setParameter("from", from);
        }
        if (to != null) {
            q.setParameter("to", to);
        }
        if (locationId != null) {
            q.setParameter("locationId", locationId);
        }
        if (personId != null) {
            q.setParameter("personId", personId);
        }
    }

    private static ReportRow toRow(Object[] r) {
        int i = 0;
        UUID flightId = requireUuid(r[i++], "flight.id");
        short typeId = ((Number) r[i++]).shortValue();
        LocalDate flightDate = asLocalDate(r[i++]);
        Instant start = asInstant(r[i++]);
        Instant ldg = asInstant(r[i++]);
        boolean noStart = asBool(r[i++]);
        boolean noLdg = asBool(r[i++]);
        Instant planOpened = asInstant(r[i++]);
        UUID processStateId = requireUuid(r[i++], "flight.process_state_id");
        boolean solo = asBool(r[i++]);
        String comment = asString(r[i++]);
        String startTypeCode = asString(r[i++]);
        String immat = asString(r[i++]);
        String pilotName = asString(r[i++]);
        String secondCrewName = asString(r[i++]);
        String flightCode = asString(r[i++]);
        String flightTypeName = asString(r[i++]);
        String startLocation = asString(r[i++]);
        String ldgLocation = asString(r[i++]);
        UUID towedGliderFlightId = asUuid(r[i++]);
        UUID towFlightId = asUuid(r[i++]);
        Instant towStart = asInstant(r[i++]);
        Instant towLdg = asInstant(r[i++]);
        boolean towNoStart = asBool(r[i++]);
        boolean towNoLdg = asBool(r[i++]);
        Instant towPlanOpened = asInstant(r[i++]);
        UUID towProcessStateId = asUuid(r[i++]);
        String towImmat = asString(r[i++]);
        String towPilotName = asString(r[i++]);
        String towFlightCode = asString(r[i++]);
        String towFlightTypeName = asString(r[i++]);
        String towStartLocation = asString(r[i++]);
        String towLdgLocation = asString(r[i]);
        return new ReportRow(flightId, typeId, flightDate, start, ldg, noStart, noLdg,
                planOpened, processStateId, solo, comment, startTypeCode, immat,
                pilotName, secondCrewName, flightCode, flightTypeName,
                startLocation, ldgLocation, towedGliderFlightId,
                towFlightId, towStart, towLdg, towNoStart, towNoLdg, towPlanOpened,
                towProcessStateId, towImmat, towPilotName, towFlightCode,
                towFlightTypeName, towStartLocation, towLdgLocation);
    }

    private static UUID requireUuid(@Nullable Object o, String col) {
        UUID v = asUuid(o);
        if (v == null) {
            throw new IllegalStateException("NOT NULL column returned null: " + col);
        }
        return v;
    }

    private static @Nullable UUID asUuid(@Nullable Object o) {
        if (o == null) {
            return null;
        }
        return o instanceof UUID u ? u : UUID.fromString(o.toString());
    }

    private static @Nullable LocalDate asLocalDate(@Nullable Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (o instanceof LocalDate ld) {
            return ld;
        }
        return LocalDate.parse(o.toString());
    }

    private static @Nullable Instant asInstant(@Nullable Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Instant inst) {
            return inst;
        }
        if (o instanceof Timestamp ts) {
            return ts.toInstant();
        }
        if (o instanceof java.time.OffsetDateTime odt) {
            return odt.toInstant();
        }
        throw new IllegalStateException("Unexpected timestamp type: " + o.getClass());
    }

    private static boolean asBool(@Nullable Object o) {
        return o != null && (Boolean) o;
    }

    private static @Nullable String asString(@Nullable Object o) {
        return o == null ? null : o.toString();
    }
}
