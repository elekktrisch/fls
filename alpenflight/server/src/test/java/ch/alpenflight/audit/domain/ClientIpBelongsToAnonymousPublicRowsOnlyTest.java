package ch.alpenflight.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class ClientIpBelongsToAnonymousPublicRowsOnlyTest {

    private static final String SUBMITTER_IP = "198.51.100.7";

    @Test
    void an_anonymous_public_row_keeps_the_submitter_ip() {
        MutationAuditEvent event = rowOf(AuditActorKind.ANONYMOUS_PUBLIC, SUBMITTER_IP);

        assertThat(event.getClientIp()).isEqualTo(SUBMITTER_IP);
        assertThat(event.getActorKind()).isEqualTo(AuditActorKind.ANONYMOUS_PUBLIC);
    }

    @Test
    void every_other_actor_kind_refuses_a_client_ip() {
        for (AuditActorKind kind : AuditActorKind.values()) {
            if (kind == AuditActorKind.ANONYMOUS_PUBLIC) {
                continue;
            }
            assertThatThrownBy(() -> rowOf(kind, SUBMITTER_IP))
                    .as("actor kind %s", kind)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ANONYMOUS_PUBLIC")
                    .hasMessageContaining(kind.name());
        }
    }

    @Test
    void every_actor_kind_builds_without_a_client_ip() {
        for (AuditActorKind kind : AuditActorKind.values()) {
            assertThatCode(() -> rowOf(kind, null)).as("actor kind %s", kind).doesNotThrowAnyException();
        }
    }

    private static MutationAuditEvent rowOf(AuditActorKind kind, @Nullable String clientIp) {
        return MutationAuditEvent.builder()
                .action(AuditAction.CREATE)
                .targetEntityType("PublicFlightRegistration")
                .targetEntityId(UUID.randomUUID())
                .actorKind(kind)
                .clientIp(clientIp)
                .build();
    }
}
