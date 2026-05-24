package ch.alpenflight.flights.infra;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * One-shot lookup of the two reference-data UUIDs the Flight aggregate
 * stamps on create — {@code flight_process_state.code='NOT_PROCESSED'} and
 * {@code flight_air_state.code='NEW'}. Cached at {@code @PostConstruct} so
 * every create is a pure in-memory call; the canonical UUIDs are seeded by
 * V3 and never change at runtime (they're identity-bearing per ADR 0019).
 *
 * <p>Pre-S-059 (state-machine) all new flights start in this fixed pair.
 * S-059 owns the transition logic; this component is invariant against
 * that work.
 */
@Component
public class FlightInitialState {

    private static final String INITIAL_PROCESS_STATE_CODE = "NOT_PROCESSED";
    private static final String INITIAL_AIR_STATE_CODE = "NEW";

    private final EntityManager em;

    private UUID initialProcessStateId = new UUID(0L, 0L);
    private UUID initialAirStateId = new UUID(0L, 0L);

    public FlightInitialState(EntityManager em) {
        this.em = em;
    }

    @PostConstruct
    void resolveSeeds() {
        initialProcessStateId = lookup(FlightProcessStateProjection.class,
                INITIAL_PROCESS_STATE_CODE, "flight_process_state");
        initialAirStateId = lookup(FlightAirStateProjection.class,
                INITIAL_AIR_STATE_CODE, "flight_air_state");
    }

    public UUID initialProcessStateId() {
        return initialProcessStateId;
    }

    public UUID initialAirStateId() {
        return initialAirStateId;
    }

    private <T> UUID lookup(Class<T> projection, String code, String tableName) {
        String entityName = projection.getSimpleName();
        @SuppressWarnings("unchecked")
        Object row = em.createQuery(
                        "select p from " + entityName + " p where p.code = :c",
                        projection)
                .setParameter("c", code)
                .getResultStream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Reference seed missing: " + tableName + ".code='" + code
                                + "' — V3 migration must seed this row"));
        if (row instanceof FlightProcessStateProjection p && p.getId() != null) {
            return p.getId();
        }
        if (row instanceof FlightAirStateProjection p && p.getId() != null) {
            return p.getId();
        }
        throw new IllegalStateException(
                "Reference seed " + tableName + ".code='" + code + "' has null id");
    }
}
