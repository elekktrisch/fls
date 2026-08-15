package ch.alpenflight.flights.infra;

import ch.alpenflight.flights.domain.FlightInitialStateProvider;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class FlightInitialState implements FlightInitialStateProvider {

    private static final String INITIAL_PROCESS_STATE_CODE = "NOT_PROCESSED";

    private final EntityManager em;
    private final TransactionTemplate tx;

    private UUID initialProcessStateId = new UUID(0L, 0L);

    public FlightInitialState(EntityManager em, TransactionTemplate tx) {
        this.em = em;
        this.tx = tx;
    }

    @PostConstruct
    void resolveSeeds() {
        tx.executeWithoutResult(status -> initialProcessStateId = lookup());
    }

    @Override
    public UUID initialProcessStateId() {
        return initialProcessStateId;
    }

    private UUID lookup() {
        FlightProcessStateProjection row;
        try {
            row = em.createQuery(
                            "select p from FlightProcessStateProjection p where p.code = :c",
                            FlightProcessStateProjection.class)
                    .setParameter("c", INITIAL_PROCESS_STATE_CODE)
                    .getSingleResult();
        } catch (NoResultException e) {
            throw new IllegalStateException(
                    "Reference seed missing: flight_process_state.code='"
                            + INITIAL_PROCESS_STATE_CODE
                            + "' — V3 migration must seed this row", e);
        }
        UUID id = row.getId();
        if (id == null) {
            throw new IllegalStateException(
                    "Reference seed flight_process_state.code='"
                            + INITIAL_PROCESS_STATE_CODE + "' has null id");
        }
        return id;
    }
}
