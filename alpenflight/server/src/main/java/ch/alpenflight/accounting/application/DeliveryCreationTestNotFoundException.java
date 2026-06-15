package ch.alpenflight.accounting.application;

import java.util.UUID;

/**
 * Thrown when a {@link ch.alpenflight.accounting.domain.DeliveryCreationTest} is
 * requested by id but no <em>active</em> row with that id is visible WITHIN the
 * caller's tenant. The Hibernate {@code @TenantId} discriminator makes a
 * cross-tenant row invisible, so this single exception covers both "no such
 * harness" and "belongs to another club" — the new stack answers a uniform 404,
 * never a 403 that would confirm the row exists.
 *
 * <p>Mapped to HTTP 404 by {@code DeliveryCreationTestsExceptionHandler}.
 */
public class DeliveryCreationTestNotFoundException extends RuntimeException {

    public DeliveryCreationTestNotFoundException(UUID id) {
        super("DeliveryCreationTest not found in the caller's tenant: " + id);
    }
}
