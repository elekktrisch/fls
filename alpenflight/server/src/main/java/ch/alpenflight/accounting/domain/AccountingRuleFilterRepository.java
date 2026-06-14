package ch.alpenflight.accounting.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain port for {@link AccountingRuleFilter} persistence. Implemented by
 * {@code ch.alpenflight.accounting.infra.JpaAccountingRuleFilterRepository}.
 *
 * <p>AccountingRuleFilter is tenant-scoped via Hibernate's {@code @TenantId}
 * discriminator on {@code AccountingRuleFilter.operatingClubId} (ADR 0008). The
 * discriminator rides on every read + write query automatically; the service
 * layer (T-05) trusts it and adds only role-within-tenant checks at the
 * controller (T-06). There is intentionally NO by-id-only finder on this port —
 * every read is tenant-scoped, so a cross-tenant id is simply invisible and the
 * service surfaces it as 404 (legacy cross-tenant Update/Delete was a tenant
 * leak; the new stack closes it structurally).
 *
 * <p>Soft-delete (V4 {@code deleted_on}) is filtered at the query layer.
 */
public interface AccountingRuleFilterRepository {

    /**
     * The caller's tenant's active (non-deleted) filters, ordered by
     * {@code sortIndicator} — the list-view order. Returns the entity (matching
     * the FlightType port's choice: no cross-module join is needed, so a
     * projection would buy nothing).
     */
    List<AccountingRuleFilter> findAllActiveOrderedBySort();

    /**
     * The caller's tenant's active (non-deleted) filters ordered by
     * {@code (sortIndicator, id)} — the engine load order. The {@code id}
     * tie-break makes recipient first-match-wins + FlightTime tier order fully
     * deterministic; legacy had no ORDER BY, so that order silently depended on
     * clustered-PK / GUID order (documented divergence, operator decision).
     */
    List<AccountingRuleFilter> findActiveForEngineOrdered();

    /**
     * The active filter with this id WITHIN the caller's tenant, or empty when
     * it does not exist OR belongs to another club (the {@code @TenantId}
     * discriminator makes a cross-tenant row invisible). This is the
     * cross-tenant-404 foundation — there is deliberately no by-id-only variant.
     */
    Optional<AccountingRuleFilter> findActiveById(UUID id);

    /**
     * The next free {@code sort_indicator} for the caller's tenant =
     * {@code max(sort_indicator) + 1} over the active rows, or {@code 0} when
     * the club has none. Used by the service (T-05) to stamp a new row's
     * position respecting the partial UNIQUE {@code ux_arf_club_sort_partial}
     * on {@code (operating_club_id, sort_indicator) WHERE deleted_on IS NULL}.
     * The UNIQUE remains the structural backstop against a concurrent-insert
     * race this pre-check cannot catch.
     */
    int nextSortIndicator();

    AccountingRuleFilter save(AccountingRuleFilter filter);

    /** Flushes the persistence context — surfaces DB-side UNIQUE races synchronously. */
    void flush();
}
