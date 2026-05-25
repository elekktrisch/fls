package ch.alpenflight.flights.infra;

import ch.alpenflight.flights.domain.FlightProcessState;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Startup gate: the {@link FlightProcessState} enum constants embed the
 * canonical UUID for each {@code flight_process_state} row (identity-bearing
 * per ADR 0019). This verifier asserts at boot that the seeded rows match
 * — a future migration that drifts a UUID fails fast on app start rather
 * than producing silently incorrect transitions in production.
 */
@Component
class FlightProcessStateSeedVerifier {

    private final EntityManager em;
    private final TransactionTemplate tx;

    FlightProcessStateSeedVerifier(EntityManager em, TransactionTemplate tx) {
        this.em = em;
        this.tx = tx;
    }

    @PostConstruct
    void verifySeedMatchesEnum() {
        tx.executeWithoutResult(status -> {
            List<FlightProcessStateProjection> rows = em.createQuery(
                            "select p from FlightProcessStateProjection p", FlightProcessStateProjection.class)
                    .getResultList();
            for (FlightProcessState enumValue : FlightProcessState.values()) {
                UUID expected = enumValue.id();
                boolean matched = rows.stream()
                        .anyMatch(r -> expected.equals(r.getId()));
                if (!matched) {
                    throw new IllegalStateException(
                            "flight_process_state seed missing or drifted for "
                                    + enumValue + " (expected id=" + expected + ")");
                }
            }
        });
    }
}
