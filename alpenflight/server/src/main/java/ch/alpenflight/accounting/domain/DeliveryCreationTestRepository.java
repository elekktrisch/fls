package ch.alpenflight.accounting.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain port for {@link DeliveryCreationTest} persistence. Implemented by
 * {@code ch.alpenflight.accounting.infra.JpaDeliveryCreationTestRepository}.
 *
 * <p>DeliveryCreationTest is tenant-scoped via Hibernate's {@code @TenantId}
 * discriminator on {@code DeliveryCreationTest.operatingClubId} (ADR 0008). The
 * discriminator rides every read + write automatically; the service layer (T-14)
 * trusts it and adds only role-within-tenant checks at the controller. There is
 * intentionally NO by-id-only finder — every read is tenant-scoped, so a
 * cross-tenant id is simply invisible and the service surfaces it as 404.
 *
 * <p>Soft-delete (V4 {@code deleted_on}) is filtered at the query layer.
 */
public interface DeliveryCreationTestRepository {

    /**
     * The caller's tenant's active (non-deleted) harnesses, ordered by
     * {@code (testName, id)} — the list-view order. (Name rather than the V4
     * {@code created_on DESC} index: {@code created_on} is a DB-default column the
     * aggregate does not map, and the {@code @GeneratedValue} id is random v4 so
     * not time-ordered; name is the deterministic, operator-meaningful order.)
     */
    List<DeliveryCreationTest> findAllActiveOrderedByName();

    /**
     * The active harness with this id WITHIN the caller's tenant, or empty when it
     * does not exist OR belongs to another club (the {@code @TenantId}
     * discriminator makes a cross-tenant row invisible). The cross-tenant-404
     * foundation — there is deliberately no by-id-only variant.
     */
    Optional<DeliveryCreationTest> findActiveById(UUID id);

    DeliveryCreationTest save(DeliveryCreationTest test);

    /** Flushes the persistence context — surfaces DB-side UNIQUE races synchronously. */
    void flush();
}
