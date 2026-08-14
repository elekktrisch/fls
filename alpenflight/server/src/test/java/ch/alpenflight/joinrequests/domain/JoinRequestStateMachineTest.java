package ch.alpenflight.joinrequests.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class JoinRequestStateMachineTest {

    private static final Instant SUBMIT_AT = Instant.parse("2026-06-23T10:00:00Z");
    private static final Instant DECIDE_AT = Instant.parse("2026-06-23T11:30:00Z");

    private final Clock submitClock = Clock.fixed(SUBMIT_AT, ZoneOffset.UTC);
    private final Clock decideClock = Clock.fixed(DECIDE_AT, ZoneOffset.UTC);

    private final UUID id = UUID.fromString("019e30c3-2c00-7001-8000-0000000000a1");
    private final UUID sub = UUID.fromString("019e30c3-2c00-7001-8000-0000000000a2");
    private final UUID clubId = UUID.fromString("019e30c3-2c00-7001-8000-0000000000a3");
    private final UUID adminUserId = UUID.fromString("019e30c3-2c00-7001-8000-0000000000a4");

    @Test
    void submit_opens_a_pending_request_with_identity_and_timestamp() {
        JoinRequest r = pending(" please add me ");

        assertThat(r.getId()).isEqualTo(id);
        assertThat(r.getKeycloakSub()).isEqualTo(sub);
        assertThat(r.getEmail()).isEqualTo("pilot@example.com");
        assertThat(r.getFriendlyName()).isEqualTo("Test Pilot");
        assertThat(r.getClubId()).isEqualTo(clubId);
        assertThat(r.getStatus()).isEqualTo(JoinRequestStatus.PENDING);
        assertThat(r.getStatus().isTerminal()).isFalse();
        assertThat(r.getCreatedOn()).isEqualTo(SUBMIT_AT);
        assertThat(r.getNote()).isEqualTo("please add me");
        assertThat(r.getDecidedOn()).isNull();
        assertThat(r.getDecidedByUserId()).isNull();
        assertThat(r.getDecisionReason()).isNull();
    }

    @Test
    void submit_normalizes_blank_note_to_null() {
        assertThat(pending("   ").getNote()).isNull();
        assertThat(pending(null).getNote()).isNull();
    }

    @Test
    void submit_rejects_a_note_over_500_chars() {
        assertThatThrownBy(() -> pending("x".repeat(501)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void submit_rejects_a_blank_email_or_friendly_name() {
        assertThatThrownBy(() -> JoinRequest.submit(id, sub, "  ", "Test Pilot", clubId, null, submitClock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JoinRequest.submit(id, sub, "pilot@example.com", " ", clubId, null, submitClock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void approve_moves_pending_to_approved_and_stamps_the_decision() {
        JoinRequest r = pending(null);

        r.approve(adminUserId, decideClock);

        assertThat(r.getStatus()).isEqualTo(JoinRequestStatus.APPROVED);
        assertThat(r.getStatus().isTerminal()).isTrue();
        assertThat(r.getDecidedOn()).isEqualTo(DECIDE_AT);
        assertThat(r.getDecidedByUserId()).isEqualTo(adminUserId);
        assertThat(r.getDecisionReason()).isNull();
    }

    @Test
    void deny_moves_pending_to_denied_with_the_reason() {
        JoinRequest r = pending(null);

        r.deny("  not a member of this club ", adminUserId, decideClock);

        assertThat(r.getStatus()).isEqualTo(JoinRequestStatus.DENIED);
        assertThat(r.getDecidedOn()).isEqualTo(DECIDE_AT);
        assertThat(r.getDecidedByUserId()).isEqualTo(adminUserId);
        assertThat(r.getDecisionReason()).isEqualTo("not a member of this club");
    }

    @Test
    void deny_rejects_a_reason_over_500_chars() {
        JoinRequest r = pending(null);
        assertThatThrownBy(() -> r.deny("x".repeat(501), adminUserId, decideClock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(r.getStatus()).isEqualTo(JoinRequestStatus.PENDING);
    }

    @Test
    void withdraw_moves_pending_to_withdrawn_with_no_deciding_user() {
        JoinRequest r = pending(null);

        r.withdraw(decideClock);

        assertThat(r.getStatus()).isEqualTo(JoinRequestStatus.WITHDRAWN);
        assertThat(r.getDecidedOn()).isEqualTo(DECIDE_AT);
        assertThat(r.getDecidedByUserId()).isNull();
        assertThat(r.getDecisionReason()).isNull();
    }

    @Test
    void a_terminal_request_rejects_every_further_transition() {
        for (JoinRequestStatus terminal : terminalStates()) {
            assertIllegalFrom(terminal, r -> r.withdraw(decideClock));
            assertIllegalFrom(terminal, r -> r.approve(adminUserId, decideClock));
            assertIllegalFrom(terminal, r -> r.deny("x", adminUserId, decideClock));
        }
    }

    private void assertIllegalFrom(JoinRequestStatus terminal, Consumer<JoinRequest> transition) {
        JoinRequest r = into(terminal);
        assertThatExceptionOfType(IllegalJoinRequestStateException.class)
                .as("from terminal %s", terminal)
                .isThrownBy(() -> transition.accept(r))
                .withMessageContaining(terminal.name());
    }

    private JoinRequest into(JoinRequestStatus terminal) {
        JoinRequest r = pending(null);
        switch (terminal) {
            case APPROVED -> r.approve(adminUserId, decideClock);
            case DENIED -> r.deny(null, adminUserId, decideClock);
            case WITHDRAWN -> r.withdraw(decideClock);
            case PENDING -> throw new IllegalArgumentException("PENDING is not terminal");
        }
        return r;
    }

    private static JoinRequestStatus[] terminalStates() {
        return new JoinRequestStatus[] {
                JoinRequestStatus.APPROVED, JoinRequestStatus.DENIED, JoinRequestStatus.WITHDRAWN};
    }

    private JoinRequest pending(String note) {
        return JoinRequest.submit(id, sub, "pilot@example.com", "Test Pilot", clubId, note, submitClock);
    }
}
