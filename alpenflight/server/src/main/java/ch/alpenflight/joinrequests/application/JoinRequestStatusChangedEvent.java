package ch.alpenflight.joinrequests.application;

import ch.alpenflight.joinrequests.domain.JoinRequestStatus;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Published after a {@link ch.alpenflight.joinrequests.domain.JoinRequest}
 * transition commits — the submit/withdraw/approve/deny seams the notification
 * side-effects (email + SSE) hook onto without re-owning the transition logic.
 *
 * <p><strong>Lean payload (the pilot facts only).</strong> The pilot identity +
 * the decision reason are carried because they are stamped on the aggregate at
 * publish time. The club admins (the new-request audience) are NOT carried: the
 * AFTER_COMMIT notification listeners re-establish the club's tenant scope
 * ({@code Tenants.runAs}) and resolve admins there, so a live submit never makes
 * a directory round-trip — admin notification is best-effort and post-commit.
 *
 * @param requestId the aggregate id
 * @param clubId the tenant the request belongs to (the listeners' {@code runAs})
 * @param status the status the request just moved to
 * @param pilotSub the requesting pilot's Keycloak subject — the SSE target for a
 *     decision/withdraw, and the audience the pilot emails address
 * @param pilotEmail the pilot's email (the decision/withdraw mail recipient)
 * @param pilotFriendlyName the pilot's display name (mail greeting)
 * @param decisionReason the deny reason, when present (shown in the deny mail)
 */
public record JoinRequestStatusChangedEvent(
        UUID requestId,
        UUID clubId,
        JoinRequestStatus status,
        UUID pilotSub,
        String pilotEmail,
        String pilotFriendlyName,
        @Nullable String decisionReason) {
}
