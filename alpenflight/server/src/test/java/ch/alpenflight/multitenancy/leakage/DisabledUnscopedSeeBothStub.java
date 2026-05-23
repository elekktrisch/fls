package ch.alpenflight.multitenancy.leakage;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Placeholder for S-024's literal AC4 — "explicitly running unscoped should
 * return both tenants' data." Today's reality:
 * {@code TenantTestContext.runUnscoped()} resolves to {@code NO_TENANT}
 * (the nil-UUID sentinel) and is fail-closed (zero rows on read, FK
 * rejection on insert). The "see both" semantics — Hibernate filter bypass
 * gated by a per-call whitelist + role check — is S-023's design surface
 * (Phase G).
 *
 * <p>S-024 instead asserts today's fail-closed contract in
 * {@link LeakageSweepIT}'s {@code tenant_scoped_no_tenant_sentinel_*} cases.
 * This disabled stub keeps the literal AC4 wording asserted-to-be-asserted
 * — the moment S-023 wires the bypass mechanism, removing
 * {@code @Disabled} on this test class makes the parity explicit.
 */
@Disabled("unblocks: S-023 — see-both-tenants requires Hibernate filter bypass with whitelist + role gate")
class DisabledUnscopedSeeBothStub {

    @Test
    void disabled_unscoped_see_both_tenants() {
        // Expected post-S-023 shape:
        //   Tenants.runUnscoped(WHITELIST_ENTRY, SYSTEM_ADMIN, () -> {
        //       assertThat(repo.findAll())
        //               .extracting(/* tenant column */)
        //               .containsExactlyInAnyOrder(CLUB_A, CLUB_B);
        //   });
        throw new UnsupportedOperationException(
                "S-023 unblocks this assertion; S-024 ships it disabled as a contract witness.");
    }
}
