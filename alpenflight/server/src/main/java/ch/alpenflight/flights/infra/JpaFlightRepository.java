package ch.alpenflight.flights.infra;

import ch.alpenflight.flights.domain.Flight;
import ch.alpenflight.flights.domain.FlightRepository;
import ch.alpenflight.platform.id.FlightId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.LocalDate;
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

/**
 * Spring Data JPA implementation of {@link FlightRepository}. Tenant
 * filtering is structural via {@code @TenantId} on
 * {@link Flight#getOperatingClubId()}; soft-delete filtered at the query
 * layer. The keyset-cursor list uses an inline native-shape JPQL composed
 * via {@link CustomListQuery} since Spring Data can't synthesize the
 * complex predicate from a method name.
 */
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
}

interface CustomListQuery {
    List<FlightRepository.ListRow> findListWindow(@Nullable LocalDate from,
                                                  @Nullable LocalDate to,
                                                  @Nullable LocalDate cursorFlightDate,
                                                  @Nullable UUID cursorId,
                                                  int limit);
}

@Repository
class JpaFlightRepositoryImpl implements CustomListQuery {

    private final EntityManager em;

    JpaFlightRepositoryImpl(EntityManager em) {
        this.em = em;
    }

    @Override
    public List<FlightRepository.ListRow> findListWindow(@Nullable LocalDate from,
                                                         @Nullable LocalDate to,
                                                         @Nullable LocalDate cursorFlightDate,
                                                         @Nullable UUID cursorId,
                                                         int limit) {
        StringBuilder sb = new StringBuilder();
        sb.append("select new ch.alpenflight.flights.domain.FlightRepository$ListRow(")
          .append("  f.id, f.flightAircraftType, f.flightDate, f.startDateTime,")
          .append("  f.ldgDateTime, f.aircraftId, f.processStateId,")
          .append("  f.noStartTimeInformation, f.noLdgTimeInformation, f.flightPlanOpenedOn)")
          .append(" from Flight f")
          .append(" where f.deletedOn is null");
        if (from != null) {
            sb.append(" and f.flightDate >= :from");
        }
        if (to != null) {
            sb.append(" and f.flightDate <= :to");
        }
        if (cursorFlightDate != null && cursorId != null) {
            // (flight_date, id) < (cursor.flight_date, cursor.id) — DESC order
            // NULLS first means a NULL flight_date sorts after every real date
            // for DESC; we keep the cursor predicate strict for non-null pairs
            // only. Callers that paginate past the NULL block re-issue without
            // a cursor.
            sb.append(" and (")
              .append("   f.flightDate < :cursorDate")
              .append("   or (f.flightDate = :cursorDate and f.id < :cursorId)")
              .append(" )");
        }
        sb.append(" order by f.flightDate desc nulls last, f.id desc");

        TypedQuery<FlightRepository.ListRow> q =
                em.createQuery(sb.toString(), FlightRepository.ListRow.class);
        if (from != null) {
            q.setParameter("from", from);
        }
        if (to != null) {
            q.setParameter("to", to);
        }
        if (cursorFlightDate != null && cursorId != null) {
            q.setParameter("cursorDate", cursorFlightDate);
            q.setParameter("cursorId", cursorId);
        }
        q.setMaxResults(limit);
        return q.getResultList();
    }
}
