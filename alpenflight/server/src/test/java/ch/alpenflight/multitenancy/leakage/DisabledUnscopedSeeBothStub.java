package ch.alpenflight.multitenancy.leakage;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@Disabled("unblocks: S-023 — see-both-tenants requires Hibernate filter bypass with whitelist + role gate")
class DisabledUnscopedSeeBothStub {

    @Test
    void disabled_unscoped_see_both_tenants() {
        throw new UnsupportedOperationException(
                "S-023 unblocks this assertion; S-024 ships it disabled as a contract witness.");
    }
}
