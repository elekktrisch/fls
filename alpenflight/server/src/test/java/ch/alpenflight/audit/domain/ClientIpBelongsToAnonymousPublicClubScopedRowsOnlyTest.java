package ch.alpenflight.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class ClientIpBelongsToAnonymousPublicClubScopedRowsOnlyTest {

    private static final String SUBMITTER_IP = "198.51.100.7";

    private static final UUID CLUB_OF_THE_REGISTRATION = UUID.randomUUID();

    @Test
    void an_anonymous_public_row_of_a_club_keeps_the_submitter_ip() {
        MutationAuditEvent event =
                rowOf(AuditActorKind.ANONYMOUS_PUBLIC, CLUB_OF_THE_REGISTRATION, SUBMITTER_IP);

        assertThat(event.getClientIp()).isEqualTo(SUBMITTER_IP);
        assertThat(event.getActorKind()).isEqualTo(AuditActorKind.ANONYMOUS_PUBLIC);
        assertThat(event.getTenantClubId()).isEqualTo(CLUB_OF_THE_REGISTRATION);
    }

    @Test
    void every_other_actor_kind_refuses_a_client_ip() {
        for (AuditActorKind kind : AuditActorKind.values()) {
            if (kind == AuditActorKind.ANONYMOUS_PUBLIC) {
                continue;
            }
            assertThatThrownBy(() -> rowOf(kind, CLUB_OF_THE_REGISTRATION, SUBMITTER_IP))
                    .as("actor kind %s", kind)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ANONYMOUS_PUBLIC")
                    .hasMessageContaining(kind.name());
        }
    }

    @Test
    void the_one_actor_kind_that_may_carry_a_client_ip_refuses_it_on_a_row_no_club_owns() {
        assertThatThrownBy(() -> rowOf(AuditActorKind.ANONYMOUS_PUBLIC, null, SUBMITTER_IP))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("club-scoped")
                .hasMessageContaining("for ever");
    }

    @Test
    void no_actor_kind_at_all_carries_a_client_ip_on_a_row_no_club_owns() {
        for (AuditActorKind kind : AuditActorKind.values()) {
            assertThatThrownBy(() -> rowOf(kind, null, SUBMITTER_IP))
                    .as("actor kind %s without a club", kind)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void every_actor_kind_builds_without_a_client_ip_whether_a_club_owns_the_row_or_not() {
        for (AuditActorKind kind : AuditActorKind.values()) {
            assertThatCode(() -> rowOf(kind, CLUB_OF_THE_REGISTRATION, null))
                    .as("actor kind %s of a club", kind)
                    .doesNotThrowAnyException();
            assertThatCode(() -> rowOf(kind, null, null))
                    .as("actor kind %s without a club", kind)
                    .doesNotThrowAnyException();
        }
    }

    private static MutationAuditEvent rowOf(AuditActorKind kind,
                                            @Nullable UUID tenantClubId,
                                            @Nullable String clientIp) {
        return MutationAuditEvent.builder()
                .action(AuditAction.CREATE)
                .targetEntityType("PublicFlightRegistration")
                .targetEntityId(UUID.randomUUID())
                .tenantClubId(tenantClubId)
                .actorKind(kind)
                .clientIp(clientIp)
                .build();
    }
}
