package ch.alpenflight.accounting.infra;

import ch.alpenflight.accounting.domain.PersonFlightTimeCredit;
import ch.alpenflight.accounting.domain.PersonFlightTimeCreditRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaPersonFlightTimeCreditRepository
        extends JpaRepository<PersonFlightTimeCredit, UUID>, PersonFlightTimeCreditRepository {

    @Override
    @Query("select distinct c from PersonClub pc "
            + "join pc.person p "
            + "join PersonFlightTimeCredit c on c.person = p "
            + "left join fetch c.transactions "
            + "where p.id = :personId "
            + "and pc.deletedOn is null "
            + "and p.deletedOn is null "
            + "and c.deletedOn is null")
    List<PersonFlightTimeCredit> findActiveForPersonInCurrentTenant(@Param("personId") UUID personId);

    @Override
    @Query("select distinct c from PersonFlightTimeCredit c "
            + "left join fetch c.transactions "
            + "where c.id in ("
            + "select t.credit.id from PersonFlightTimeCreditTransaction t "
            + "where t.balancedDeliveryId = :deliveryId)")
    Optional<PersonFlightTimeCredit> findByBalancedDeliveryId(@Param("deliveryId") UUID deliveryId);
}
