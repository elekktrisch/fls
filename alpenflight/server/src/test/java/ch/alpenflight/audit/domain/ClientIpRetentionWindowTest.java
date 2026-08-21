package ch.alpenflight.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class ClientIpRetentionWindowTest {

    private static final String SUBMITTER_IP = "198.51.100.7";

    private final Instant now = Instant.now();

    @Test
    void the_window_is_ninety_days() {
        assertThat(MutationAuditEvent.CLIENT_IP_RETENTION).isEqualTo(Duration.ofDays(90));
        assertThat(MutationAuditEvent.clientIpRetentionCutoff(now))
                .isEqualTo(now.minus(Duration.ofDays(90)));
    }

    @Test
    void a_row_exactly_ninety_days_old_falls_on_the_redact_side() {
        MutationAuditEvent row = rowOccurredAt(now.minus(Duration.ofDays(90)), SUBMITTER_IP);

        assertThat(row.clientIpRetentionHasElapsedAt(now)).isTrue();
    }

    @Test
    void a_row_one_second_younger_than_ninety_days_keeps_its_client_ip() {
        MutationAuditEvent row = rowOccurredAt(
                now.minus(Duration.ofDays(90)).plusSeconds(1), SUBMITTER_IP);

        assertThat(row.clientIpRetentionHasElapsedAt(now)).isFalse();
    }

    @Test
    void a_row_that_carries_no_client_ip_never_reports_an_elapsed_window() {
        MutationAuditEvent row = rowOccurredAt(now.minus(Duration.ofDays(3650)), null);

        assertThat(row.clientIpRetentionHasElapsedAt(now)).isFalse();
    }

    @Test
    void redaction_clears_the_client_ip_and_the_row_keeps_every_other_cell() {
        MutationAuditEvent row = rowOccurredAt(now.minus(Duration.ofDays(91)), SUBMITTER_IP);
        UUID target = row.getTargetEntityId();

        row.redactClientIpKeepingEveryOtherCell();

        assertThat(row.getClientIp()).isNull();
        assertThat(row.getActorKind()).isEqualTo(AuditActorKind.ANONYMOUS_PUBLIC);
        assertThat(row.getAction()).isEqualTo(AuditAction.CREATE);
        assertThat(row.getTargetEntityType()).isEqualTo("PublicFlightRegistration");
        assertThat(row.getTargetEntityId()).isEqualTo(target);
        assertThat(row.getOccurredAt()).isEqualTo(now.minus(Duration.ofDays(91)));
    }

    private static MutationAuditEvent rowOccurredAt(Instant occurredAt, @Nullable String clientIp) {
        return MutationAuditEvent.builder()
                .action(AuditAction.CREATE)
                .targetEntityType("PublicFlightRegistration")
                .targetEntityId(UUID.randomUUID())
                .occurredAt(occurredAt)
                .actorKind(AuditActorKind.ANONYMOUS_PUBLIC)
                .clientIp(clientIp)
                .build();
    }
}
