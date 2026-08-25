package ch.alpenflight.tenancy.sandbox.application;

import static ch.alpenflight.tenancy.sandbox.application.SandboxClubPurge.APPEND_ONLY_ENTITIES_THE_APP_DATABASE_ROLE_MAY_NOT_DELETE_FROM;
import static ch.alpenflight.tenancy.sandbox.application.SandboxClubPurge.DELETES_IN_FOREIGN_KEY_SAFE_ORDER;
import static ch.alpenflight.tenancy.sandbox.application.SandboxClubPurge.requireEveryTenantScopedEntityIsDeletedOrDeliberatelyKept;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ch.alpenflight.tenancy.sandbox.application.SandboxClubPurge.ClubScopedDelete;
import ch.alpenflight.tenancy.sandbox.application.SandboxClubPurge.TenantScopedEntityOutsideThePurgeException;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SandboxClubPurgeCoverageTest {

    private static final Set<String> ENTITY_NAMES_THE_PURGE_DELETES =
            DELETES_IN_FOREIGN_KEY_SAFE_ORDER.stream()
                    .map(ClubScopedDelete::entityName)
                    .collect(Collectors.toUnmodifiableSet());

    @Test
    void a_tenant_scoped_entity_that_no_delete_step_reaches_stops_the_purge() {
        TenantScopedEntityOutsideThePurgeException refused = assertThrows(
                TenantScopedEntityOutsideThePurgeException.class,
                () -> requireEveryTenantScopedEntityIsDeletedOrDeliberatelyKept(
                        Set.of("Flight", "AnEntityAFutureJourneyAdds"),
                        Set.of("Flight"),
                        Set.of()));

        assertThat(refused).hasMessageContaining("AnEntityAFutureJourneyAdds");
    }

    @Test
    void a_tenant_scoped_entity_a_delete_step_reaches_passes() {
        assertThatCode(() -> requireEveryTenantScopedEntityIsDeletedOrDeliberatelyKept(
                Set.of("Flight"), Set.of("Flight"), Set.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void a_tenant_scoped_entity_the_purge_keeps_on_purpose_passes() {
        assertThatCode(() -> requireEveryTenantScopedEntityIsDeletedOrDeliberatelyKept(
                Set.of("MutationAuditEvent"), Set.of(), Set.of("MutationAuditEvent")))
                .doesNotThrowAnyException();
    }

    @Test
    void the_append_only_audit_entity_is_kept_and_never_deleted() {
        assertThat(APPEND_ONLY_ENTITIES_THE_APP_DATABASE_ROLE_MAY_NOT_DELETE_FROM)
                .containsExactly("MutationAuditEvent");
        assertThat(ENTITY_NAMES_THE_PURGE_DELETES)
                .doesNotContainAnyElementsOf(
                        APPEND_ONLY_ENTITIES_THE_APP_DATABASE_ROLE_MAY_NOT_DELETE_FROM);
    }

    @Test
    void the_two_cross_tenant_entities_a_demo_seat_owns_are_deleted_by_an_explicit_club_predicate() {
        assertThat(ENTITY_NAMES_THE_PURGE_DELETES).contains("Aircraft", "Person");
        assertThat(DELETES_IN_FOREIGN_KEY_SAFE_ORDER)
                .allSatisfy(step -> assertThat(step.jpql()).contains(":clubId"));
    }
}
