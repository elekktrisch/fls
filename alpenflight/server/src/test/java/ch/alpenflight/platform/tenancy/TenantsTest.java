package ch.alpenflight.platform.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Push / restore / nesting semantics of {@link Tenants#runAs}. Pure JUnit —
 * no Spring, no DB. The resolver-level contract (Hibernate sees the
 * override) is covered by {@code TenantsRunAsIT}.
 */
class TenantsTest {

    private static final UUID CLUB_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID CLUB_B = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    @AfterEach
    void clearCarrier() {
        TenantContextCarrier.clear();
    }

    @Test
    void runAs_sets_carrier_for_the_duration_of_body() {
        AtomicReference<UUID> seen = new AtomicReference<>();
        Tenants.runAs(CLUB_A, () -> seen.set(TenantContextCarrier.current().orElse(null)));
        assertThat(seen.get()).isEqualTo(CLUB_A);
    }

    @Test
    void runAs_restores_prior_absence_after_body() {
        assertThat(TenantContextCarrier.current()).isEmpty();
        Tenants.runAs(CLUB_A, () -> { /* no-op */ });
        assertThat(TenantContextCarrier.current())
                .as("carrier returns to empty when there was no prior value")
                .isEmpty();
    }

    @Test
    void runAs_restores_prior_value_after_nested_call() {
        TenantContextCarrier.set(CLUB_A);
        Tenants.runAs(CLUB_B, () -> assertThat(TenantContextCarrier.current()).contains(CLUB_B));
        assertThat(TenantContextCarrier.current())
                .as("outer scope's tenant restored after nested runAs returns")
                .contains(CLUB_A);
    }

    @Test
    void runAs_supplier_returns_body_result() {
        String result = Tenants.runAs(CLUB_A, () -> "ok-" + TenantContextCarrier.current().orElseThrow());
        assertThat(result).isEqualTo("ok-" + CLUB_A);
    }

    @Test
    void runAs_clears_carrier_even_if_body_throws() {
        assertThatThrownBy(() -> Tenants.runAs(CLUB_A, () -> {
            assertThat(TenantContextCarrier.current()).contains(CLUB_A);
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(TenantContextCarrier.current())
                .as("carrier is restored even on exceptional exit")
                .isEmpty();
    }

    @Test
    void runAs_rejects_null_clubId() {
        assertThatThrownBy(() -> Tenants.runAs(null, () -> { /* no-op */ }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("clubId");
    }

    @Test
    void runAs_rejects_NO_TENANT_sentinel() {
        // The nil-UUID is the resolver's "no tenant" marker; runAs must rebuke
        // it loud rather than silently degrade to empty-reads + FK-violation-
        // on-write at the bottom of the stack.
        assertThatThrownBy(() ->
                Tenants.runAs(ClubTenantIdentifierResolver.NO_TENANT, () -> { /* no-op */ }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NO_TENANT");
    }
}
