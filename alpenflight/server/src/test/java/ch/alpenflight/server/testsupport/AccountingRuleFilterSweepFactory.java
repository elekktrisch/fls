package ch.alpenflight.server.testsupport;

import ch.alpenflight.accounting.domain.AccountingRuleFilter;
import ch.alpenflight.accounting.domain.FilterConfig;
import java.util.UUID;

final class AccountingRuleFilterSweepFactory {

    private AccountingRuleFilterSweepFactory() {}

    static AccountingRuleFilter build(SweepFixtureContext ctx) {
        if (ctx == null) {
            throw new IllegalStateException("SweepFixtureContext is required");
        }
        UUID filterTypeId = ctx.seededRecipientFilterTypeId();
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
