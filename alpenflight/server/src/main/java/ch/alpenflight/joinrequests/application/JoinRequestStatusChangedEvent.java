package ch.alpenflight.joinrequests.application;

import ch.alpenflight.joinrequests.domain.JoinRequest;
import ch.alpenflight.joinrequests.domain.JoinRequestStatus;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record JoinRequestStatusChangedEvent(
        UUID requestId,
        UUID clubId,
        JoinRequestStatus status,
        UUID pilotSub,
        String pilotEmail,
        String pilotFriendlyName,
        @Nullable String decisionReason) {

    static JoinRequestStatusChangedEvent from(JoinRequest saved) {
        return new JoinRequestStatusChangedEvent(
                saved.getId(), saved.getClubId(), saved.getStatus(), saved.getKeycloakSub(),
                saved.getEmail(), saved.getFriendlyName(), saved.getDecisionReason());
    }
}
