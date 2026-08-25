package ch.alpenflight.tenancy.sandbox.infra;

import ch.alpenflight.tenancy.sandbox.domain.DemoSeat;
import ch.alpenflight.tenancy.sandbox.domain.DemoSeatRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface JpaDemoSeatRepository
        extends JpaRepository<DemoSeat, UUID>, DemoSeatRepository {

    @Override
    @Query("select s from DemoSeat s order by s.seatNumber")
    List<DemoSeat> findAllInSeatNumberOrder();
}
