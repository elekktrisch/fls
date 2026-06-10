package ch.alpenflight.flights.infra;

import ch.alpenflight.flights.domain.FlightReportRow;
import ch.alpenflight.flights.domain.FlightReportRowRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA implementation of {@link FlightReportRowRepository}.
 * Plain JPA per ADR 0027 §1 — every query rides the {@code @TenantId}
 * discriminator on {@link FlightReportRow#getOperatingClubId()} (ADR 0008);
 * no native SQL, nothing to register.
 */
@Repository
public interface JpaFlightReportRowRepository
        extends JpaRepository<FlightReportRow, UUID>, FlightReportRowRepository {

    @Override
    @EntityGraph(attributePaths = {"crew"})
    Optional<FlightReportRow> findByFlightId(UUID flightId);

    @Override
    @Query("select r.flightId from FlightReportRow r where r.towFlightId = :towFlightId")
    List<UUID> findFlightIdsByTowFlightId(@Param("towFlightId") UUID towFlightId);

    @Override
    @Query("select r.flightId from FlightReportRow r"
            + " where r.towedGliderFlightId = :towedGliderFlightId")
    List<UUID> findFlightIdsByTowedGliderFlightId(
            @Param("towedGliderFlightId") UUID towedGliderFlightId);
}
