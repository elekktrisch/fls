package ch.alpenflight.clubs.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain port for {@link DiscoveryFlightDay} persistence. Implemented by the
 * clubs {@code infra} Spring Data JPA adapter.
 *
 * <p>Tenant-scoped via {@code @TenantId} on {@code DiscoveryFlightDay.clubId} —
 * every method here reads the caller's club only, including the anonymous
 * public read, which supplies its tenant through {@code Tenants.runAs}.
 * Soft-deleted rows are excluded by every finder; withdrawal is
 * {@link DiscoveryFlightDay#softDelete} followed by {@link #save}.
 */
public interface DiscoveryFlightDayRepository {

    /** Every live day, past ones included — the club admin's management list. */
    List<DiscoveryFlightDay> findAllActive();

    /**
     * The days a visitor may still book, ascending. Mirrors
     * {@link DiscoveryFlightDay#isBookableOn} in SQL so the public list does not
     * load a club's history to filter it in memory.
     */
    List<DiscoveryFlightDay> findBookableFrom(LocalDate from);

    Optional<DiscoveryFlightDay> findActiveById(UUID id);

    /** Pre-check for the {@code ux_discovery_flight_day_club_date} partial UNIQUE. */
    Optional<DiscoveryFlightDay> findActiveByEventDate(LocalDate eventDate);

    DiscoveryFlightDay save(DiscoveryFlightDay day);
}
