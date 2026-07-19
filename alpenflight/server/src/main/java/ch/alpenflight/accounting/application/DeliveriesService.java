package ch.alpenflight.accounting.application;

import ch.alpenflight.accounting.application.DeliveryDtos.DeliveryDetail;
import ch.alpenflight.accounting.application.DeliveryDtos.DeliveryOverview;
import ch.alpenflight.accounting.application.DeliveryDtos.DeliveryPage;
import ch.alpenflight.accounting.domain.Delivery;
import ch.alpenflight.accounting.domain.DeliveryRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only service for the {@link Delivery} aggregate — the invoice-draft
 * viewer. Tenant scoping (ADR 0008) is structural via Hibernate's {@code @TenantId}
 * discriminator on {@code Delivery.operatingClubId}; the role-within-tenant gate
 * lives on the controller as {@code @PreAuthorize}. No write path this iteration
 * (create / book / delete are deferred), so the service only maps aggregate → DTO
 * and emits no audit event.
 *
 * <p>Cross-tenant 404: {@link #getDetail} loads via
 * {@link DeliveryRepository#findActiveById}, which the {@code @TenantId} filter
 * scopes to the caller's club — a cross-tenant id is invisible and surfaces as
 * {@link DeliveryNotFoundException} (→ 404, never a 403 that would confirm the row
 * exists).
 */
@Service
@Transactional(readOnly = true)
public class DeliveriesService {

    /** Legacy default paged-list size. A non-positive size falls back to this. */
    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final int MAX_PAGE_SIZE = 500;

    private final DeliveryRepository deliveries;

    public DeliveriesService(DeliveryRepository deliveries) {
        this.deliveries = deliveries;
    }

    /**
     * One SPA page of the caller's tenant's active deliveries, sorted
     * {@code batch_id desc, recipient lastname asc} (the legacy list order — the
     * repository owns the ordering). {@code pageStart} is a 0-based ROW offset (the
     * house {@code page/{start}/{size}} shape); {@code totalRows} is the unpaged
     * tenant-scoped count.
     */
    public DeliveryPage page(int pageStart, int pageSize) {
        int safeSize = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        int safeStart = Math.max(pageStart, 0);
        int pageNumber = safeStart / safeSize;
        List<DeliveryOverview> items = deliveries.findActivePage(PageRequest.of(pageNumber, safeSize)).stream()
                .map(DeliveryDetailMapper::toOverview)
                .toList();
        return new DeliveryPage(items, safeStart, safeSize, deliveries.countActive());
    }

    public DeliveryDetail getDetail(UUID id) {
        return DeliveryDetailMapper.toDetail(deliveries.findActiveById(id)
                .orElseThrow(() -> new DeliveryNotFoundException(id)));
    }
}
