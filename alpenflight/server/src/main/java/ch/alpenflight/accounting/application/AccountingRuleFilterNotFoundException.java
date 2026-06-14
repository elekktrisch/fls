package ch.alpenflight.accounting.application;

import java.util.UUID;

/**
 * Thrown when an {@link ch.alpenflight.accounting.domain.AccountingRuleFilter}
 * is requested by id but no <em>active</em> row with that id is visible WITHIN
 * the caller's tenant. The Hibernate {@code @TenantId} discriminator makes a
 * cross-tenant row invisible, so this single exception covers both "no such
 * filter" and "belongs to another club" — closing the legacy cross-tenant
 * Update/Delete tenant-leak (oracle): the new stack answers a uniform 404, never
 * a 403 that would confirm the row exists.
 *
 * <p>Mapped to HTTP 404 by the accounting feature's exception handler (T-06).
 */
public class AccountingRuleFilterNotFoundException extends RuntimeException {

    public AccountingRuleFilterNotFoundException(UUID id) {
        super("AccountingRuleFilter not found in the caller's tenant: " + id);
    }
}
