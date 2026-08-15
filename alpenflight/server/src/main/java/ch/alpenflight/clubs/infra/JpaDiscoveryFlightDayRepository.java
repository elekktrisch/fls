package ch.alpenflight.clubs.infra;

import ch.alpenflight.clubs.domain.DiscoveryFlightDay;
import ch.alpenflight.clubs.domain.DiscoveryFlightDayRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaDiscoveryFlightDayRepository
        extends JpaRepository<DiscoveryFlightDay, UUID>, DiscoveryFlightDayRepository {

    @Override
    @Query("select d from DiscoveryFlightDay d where d.deletedOn is null "
            + "order by d.eventDate asc")
    List<DiscoveryFlightDay> findAllActive();

    @Override
    @Query("select d from DiscoveryFlightDay d where d.deletedOn is null "
            + "and d.eventDate >= :from order by d.eventDate asc")
    List<DiscoveryFlightDay> findBookableFrom(@Param("from") LocalDate from);

    @Override
    @Query("select d from DiscoveryFlightDay d where d.id = :id and d.deletedOn is null")
    Optional<DiscoveryFlightDay> findActiveById(@Param("id") UUID id);

    @Override
    @Query("select d from DiscoveryFlightDay d where d.eventDate = :eventDate "
            + "and d.deletedOn is null")
    Optional<DiscoveryFlightDay> findActiveByEventDate(@Param("eventDate") LocalDate eventDate);
}
