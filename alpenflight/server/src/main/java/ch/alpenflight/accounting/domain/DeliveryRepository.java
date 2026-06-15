package ch.alpenflight.accounting.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;

/**
 * Domain port for {@link Delivery} READ persistence (J-10 is read-only; the write
 * repo methods land at J-10b). Implemented by
 * {@code ch.alpenflight.accounting.infra.JpaDeliveryRepository}.
 *
 * <p>Delivery is tenant-scoped via Hibernate's {@code @TenantId} discriminator on
 * {@code Delivery.operatingClubId} (ADR 0008). The discriminator rides every read
 * automatically; the service layer (T-04) trusts it and adds only role-within-tenant
 * checks. There is intentionally NO by-id-only finder — every read is tenant-scoped,
 * so a cross-tenant id is invisible and the service surfaces it as 404.
 *
 * <p>Soft-delete (V4 {@code deleted_on}) is filtered at the query layer. JPA-first
 * per ADR 0027 — no native SQL.
 */
public interface DeliveryRepository {

    /**
     * One page of the caller's tenant's active (non-deleted) deliveries, ordered
     * {@code batch_id desc, recipient lastname asc, firstname asc, id asc} — the
     * legacy list order (newest batch first, recipient-alphabetical within).
     */
    List<Delivery> findActivePage(Pageable pageable);

    /** Total active deliveries in the caller's tenant — the page's {@code totalRows}. */
    long countActive();

    /**
     * The active delivery with this id WITHIN the caller's tenant (line items
     * fetched), or empty when it does not exist OR belongs to another club (the
     * {@code @TenantId} discriminator makes a cross-tenant row invisible). The
     * cross-tenant-404 foundation — there is deliberately no by-id-only variant.
     */
    Optional<Delivery> findActiveById(UUID id);
}
