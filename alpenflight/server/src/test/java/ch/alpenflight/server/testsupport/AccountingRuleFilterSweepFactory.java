package ch.alpenflight.server.testsupport;

import ch.alpenflight.accounting.domain.AccountingRuleFilter;
import ch.alpenflight.accounting.domain.FilterConfig;
import java.util.UUID;

/**
 * Minimal-object factory for {@link AccountingRuleFilter} consumed by the S-024
 * leakage sweep (J-8 T-04). The aggregate carries one mandatory non-tenant FK —
 * {@code filter_type_id} → the global {@code t_accounting_rule_filter_type}
 * reference table (V4-seeded) — and the optional {@code accounting_unit_type_id}
 * (left null here). The filter-type is reference data shared by every club, so
 * the factory references its pinned V4 seed UUID directly (the same posture as
 * {@code FlightSweepFactory} using {@code FlightProcessState.id()} — a stable
 * canonical reference id, no DB read).
 *
 * <p>Because the only FK that the {@code @TenantId} resolver fills is
 * {@code operating_club_id}, that is the ONLY FK left unsatisfiable under the
 * NO_TENANT sentinel — so the sweep's fail-closed write assertion trips at
 * {@code fk_accounting_rule_filter_operating_club_id} (the
 * {@code V41__accounting_tenant_fk_naming_convention} migration realigned the
 * V4 {@code fk_arf_operating_club_id} to the convention name the sweep
 * reconstructs, exactly as V32/V33 did for the reservation/planning aggregates).
 */
final class AccountingRuleFilterSweepFactory {

    private AccountingRuleFilterSweepFactory() {}

    static AccountingRuleFilter build(SweepFixtureContext ctx) {
        if (ctx == null) {
            throw new IllegalStateException("SweepFixtureContext is required");
        }
        UUID filterTypeId = ctx.firstAccountingRuleFilterTypeId();
        // operatingClubId is filled by Hibernate's @TenantId resolver on INSERT,
        // not by this factory — create() does not accept it (it is the
        // discriminator). A unique name keeps the row distinct across re-runs.
        return AccountingRuleFilter.create(
                filterTypeId,
                TenantScopedRowBuilders.SWEEP_PREFIX + "ARF_" + Long.toString(System.nanoTime(), 36),
                null,
                null,
                true,
                false,
                false,
                null,
                null,
                FilterConfig.empty());
    }
}
