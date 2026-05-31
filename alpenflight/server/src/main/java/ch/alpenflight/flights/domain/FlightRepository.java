package ch.alpenflight.flights.domain;

import ch.alpenflight.platform.id.FlightId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Domain port for {@link Flight} persistence. Implemented by
 * {@code ch.alpenflight.flights.infra.JpaFlightRepository}.
 *
 * <p>Flight is tenant-scoped via Hibernate's {@code @TenantId} discriminator
 * on {@code Flight.operatingClubId} (ADR 0008); every read + write query
 * carries the tenant predicate automatically. {@link #findByIdWithCrew}
 * therefore returns empty for cross-tenant ids — the caller's tenant scope
 * makes the row invisible.
 *
 * <p>Soft-delete ({@code deleted_on}) is filtered at the query layer.
 */
public interface FlightRepository {

    /**
     * Slim projection for {@code GET /api/v1/flights}. S-058 scope is basic
     * CRUD — decoration columns (aircraft immatriculation, pilot display
     * name) are intentionally NOT in this row to keep the list query a
     * single-table JPQL projection that crosses no module boundary
     * (ADR 0023). The web layer joins decorations from the
     * {@code /api/v1/aircraft/picker} payload client-side, or a future
     * story adds a per-row enrichment pass.
     */
    record ListRow(UUID id,
                   FlightAircraftType flightAircraftType,
                   @Nullable LocalDate flightDate,
                   @Nullable Instant startDateTime,
                   @Nullable Instant ldgDateTime,
                   UUID aircraftId,
                   UUID processStateId,
                   long version,
                   boolean noStartTimeInformation,
                   boolean noLdgTimeInformation,
                   @Nullable Instant flightPlanOpenedOn) {

        /** Cross-view parity: same compute as {@link Flight#airState()}. */
        public FlightAirState airState() {
            return FlightAirState.compute(ldgDateTime, startDateTime,
                    noLdgTimeInformation, noStartTimeInformation, flightPlanOpenedOn);
        }
    }

    Flight save(Flight flight);

    /**
     * Detail load. Eagerly fetches the crew collection via {@code @EntityGraph}
     * so the detail GET serves in one query.
     */
    Optional<Flight> findByIdWithCrew(FlightId id);

    /**
     * Keyset-cursor list. Returns rows where the (flight_date, id) pair is
     * strictly less than the cursor, filtered by the date window (both
     * bounds optional). Soft-deleted rows excluded. Tenant filter applied
     * structurally by {@code @TenantId}.
     *
     * <p>Callers request {@code limit + 1} rows; the service trims to
     * {@code limit} and emits a {@code nextCursor} only when the sentinel
     * row was returned.
     *
     * <p>When {@code personId} is non-null, rows are filtered to flights
     * with a non-deleted FlightCrew row whose {@code person_id} matches.
     * Sort under the filter is {@code flight_date DESC NULLS LAST,
     * start_date_time DESC NULLS LAST, id DESC} (S-165 AC; UUIDv7 id
     * stand-in for the {@code created_on} tie-breaker). The default sort
     * (no {@code personId}) remains {@code flight_date DESC NULLS LAST,
     * id DESC} so the keyset cursor — encoded as {@code (flight_date, id)}
     * — remains strictly monotonic with the order; paginating past the
     * limit-1 dashboard call under {@code personId} is best-effort within
     * same-day ties (S-165 only consumes {@code limit=1}).
     */
    List<ListRow> findListWindow(@Nullable LocalDate from,
                                 @Nullable LocalDate to,
                                 @Nullable LocalDate cursorFlightDate,
                                 @Nullable UUID cursorId,
                                 int limit,
                                 @Nullable UUID personId);

    /**
     * Findall gliders linked to the given tow flight (sacred-cow 1:N
     * pairing per S-013 + S-058 design notes). Soft-deleted glider rows
     * excluded; tenant filter structural.
     */
    List<Flight> findByTowFlightId(FlightId towFlightId);

    /**
     * Findall flights in the given process state. Soft-deleted excluded;
     * tenant filter structural.
     */
    List<Flight> findByProcessStateId(UUID processStateId);

    /**
     * Returns the most recently created flight for the given (aircraft,
     * flight_date) tuple within the caller's tenant — feeds the SPA's
     * last-flight-context pre-fill per AC-DIR-1. Soft-deleted excluded;
     * tenant filter structural via {@code @TenantId}.
     */
    Optional<Flight> findLastByAircraftAndDate(UUID aircraftId, LocalDate flightDate);
}
