package ch.alpenflight.flights.infra;

import ch.alpenflight.flights.domain.Flight;
import ch.alpenflight.flights.domain.FlightRepository;
import ch.alpenflight.platform.id.FlightId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface JpaFlightRepository
        extends JpaRepository<Flight, UUID>, FlightRepository, CustomListQuery {

    @Override
    @EntityGraph(attributePaths = {"crew"})
    @Query("select f from Flight f where f.id = :id and f.deletedOn is null")
    default Optional<Flight> findByIdWithCrew(FlightId id) {
        return doFindByIdWithCrew(id.value());
    }

    @EntityGraph(attributePaths = {"crew"})
    @Query("select f from Flight f where f.id = :id and f.deletedOn is null")
    Optional<Flight> doFindByIdWithCrew(@Param("id") UUID id);

    @Override
    @Query("select f from Flight f where f.towFlightId = :towId and f.deletedOn is null")
    default List<Flight> findByTowFlightId(FlightId towId) {
        return doFindByTowFlightId(towId.value());
    }

    @Query("select f from Flight f where f.towFlightId = :towId and f.deletedOn is null")
    List<Flight> doFindByTowFlightId(@Param("towId") UUID towId);

    @Override
    @Query("select f from Flight f where f.processStateId = :psid and f.deletedOn is null")
    List<Flight> findByProcessStateId(@Param("psid") UUID processStateId);

    @Override
    @Query("select f from Flight f where f.flightReportSentOn is null and f.deletedOn is null "
            + "order by f.flightDate desc")
    List<Flight> findUnreported();

    @Override
    @Query("select count(f) from Flight f where f.flightDate = :date and f.deletedOn is null")
    long countByFlightDate(@Param("date") LocalDate flightDate);

    @Override
    @Query("select count(f) from Flight f where f.deletedOn is null")
    long countAll();

    @Override
    default long countByProcessStateIdIn(Collection<UUID> processStateIds) {
        if (processStateIds.isEmpty()) {
            return 0L;
        }
        return doCountByProcessStateIdIn(processStateIds);
    }

    @Query("select count(f) from Flight f"
            + " where f.processStateId in :psids and f.deletedOn is null")
    long doCountByProcessStateIdIn(@Param("psids") Collection<UUID> processStateIds);

    @Override
    default Optional<Flight> findLastByAircraftAndDate(UUID aircraftId, LocalDate flightDate) {
        return doFindLastByAircraftAndDate(aircraftId, flightDate, PageRequest.of(0, 1))
                .stream().findFirst();
    }

    @EntityGraph(attributePaths = {"crew"})
    @Query("""
            select f from Flight f
            where f.aircraftId = :acid
              and f.flightDate = :date
              and f.deletedOn is null
            order by f.createdOn desc, f.id desc
            """)
    List<Flight> doFindLastByAircraftAndDate(@Param("acid") UUID aircraftId,
                                             @Param("date") LocalDate flightDate,
                                             Pageable pageable);

    @Override
    @Query("select f.id from Flight f where f.deletedOn is null")
    List<UUID> findAllLiveIds();

    @Override
    @Query("select f.id from Flight f where f.aircraftId = :acid and f.deletedOn is null")
    List<UUID> findIdsByAircraftId(@Param("acid") UUID aircraftId);

    @Override
    @Query("select f.id from Flight f where (f.startLocationId = :locId"
            + " or f.ldgLocationId = :locId) and f.deletedOn is null")
    List<UUID> findIdsByLocationId(@Param("locId") UUID locationId);

    @Override
    @Query("select f.id from Flight f where f.flightTypeId = :ftid and f.deletedOn is null")
    List<UUID> findIdsByFlightTypeId(@Param("ftid") UUID flightTypeId);
}

interface CustomListQuery {
    List<FlightRepository.ListRow> findListWindow(@Nullable LocalDate from,
                                                  @Nullable LocalDate to,
                                                  @Nullable LocalDate cursorFlightDate,
                                                  @Nullable UUID cursorId,
                                                  int limit,
                                                  @Nullable UUID personId);
}

@Repository
class JpaFlightRepositoryImpl implements CustomListQuery {

    private static final String UUIDV7_ID_DESC_STANDS_IN_FOR_CREATED_ON_DESC = " f.id desc";

    private static final String ORDER_BY_FLIGHT_DATE_THEN_ID =
            " order by f.flightDate desc nulls last," + UUIDV7_ID_DESC_STANDS_IN_FOR_CREATED_ON_DESC;

    private static final String ORDER_BY_FLIGHT_DATE_THEN_START_TIME_THEN_ID =
            " order by f.flightDate desc nulls last, f.startDateTime desc nulls last,"
                    + UUIDV7_ID_DESC_STANDS_IN_FOR_CREATED_ON_DESC;

    private static final String STRICTLY_BEFORE_CURSOR_PAIR_SKIPPING_NULL_FLIGHT_DATES =
            " and (   f.flightDate < :cursorDate"
                    + "   or (f.flightDate = :cursorDate and f.id < :cursorId) )";

    private final EntityManager em;

    JpaFlightRepositoryImpl(EntityManager em) {
        this.em = em;
    }

    @Override
    public List<FlightRepository.ListRow> findListWindow(@Nullable LocalDate from,
                                                         @Nullable LocalDate to,
                                                         @Nullable LocalDate cursorFlightDate,
                                                         @Nullable UUID cursorId,
                                                         int limit,
                                                         @Nullable UUID personId) {
        StringBuilder sb = new StringBuilder();
        sb.append("select new ch.alpenflight.flights.domain.FlightRepository$ListRow(")
          .append("  f.id, f.flightAircraftType, f.flightDate, f.startDateTime,")
          .append("  f.ldgDateTime, f.aircraftId, f.processStateId, f.version,")
          .append("  f.noStartTimeInformation, f.noLdgTimeInformation, f.flightPlanOpenedOn)")
          .append(" from Flight f")
          .append(" where f.deletedOn is null");
        if (from != null) {
            sb.append(" and f.flightDate >= :from");
        }
        if (to != null) {
            sb.append(" and f.flightDate <= :to");
        }
        if (personId != null) {
            sb.append(" and exists (")
              .append("   select 1 from FlightCrew c")
              .append("   where c.flight = f")
              .append("     and c.personId = :personId")
              .append("     and c.deletedOn is null")
              .append(" )");
        }
        if (cursorFlightDate != null && cursorId != null) {
            sb.append(STRICTLY_BEFORE_CURSOR_PAIR_SKIPPING_NULL_FLIGHT_DATES);
        }
        if (personId != null) {
            sb.append(ORDER_BY_FLIGHT_DATE_THEN_START_TIME_THEN_ID);
        } else {
            sb.append(ORDER_BY_FLIGHT_DATE_THEN_ID);
        }

        TypedQuery<FlightRepository.ListRow> q =
                em.createQuery(sb.toString(), FlightRepository.ListRow.class);
        if (from != null) {
            q.setParameter("from", from);
        }
        if (to != null) {
            q.setParameter("to", to);
        }
        if (personId != null) {
            q.setParameter("personId", personId);
        }
        if (cursorFlightDate != null && cursorId != null) {
            q.setParameter("cursorDate", cursorFlightDate);
            q.setParameter("cursorId", cursorId);
        }
        q.setMaxResults(limit);
        return q.getResultList();
    }
}
