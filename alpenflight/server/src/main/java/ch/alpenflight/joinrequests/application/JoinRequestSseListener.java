package ch.alpenflight.joinrequests.application;

import ch.alpenflight.joinrequests.application.JoinRequestAdminRecipients.AdminRecipient;
import ch.alpenflight.joinrequests.domain.JoinRequestStatus;
import ch.alpenflight.me.application.MePrincipalEventBus;
import ch.alpenflight.platform.tenancy.Tenants;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Publishes a {@code "join-request.status-changed"} Server-Sent Event on each
 * committed transition (S-178), reusing J-3's principal-scoped
 * {@link MePrincipalEventBus} channel.
 *
 * <p><strong>Audience.</strong> A decision or withdraw nudges the PILOT (their
 * {@code /join/pending} page reacts — approved → token-refresh → {@code /start},
 * denied → reason, withdrawn → {@code /join}). A new PENDING submit nudges the
 * club ADMINS (their pending list gains a row), resolved inside the club's
 * tenant window. The bus is keyed by Keycloak {@code sub} and no-ops for a
 * principal with no open stream.
 *
 * <p><strong>Module direction.</strong> This producer calls the OPEN
 * {@code me.application} publish port directly — the dependency direction the
 * bus's contract documents (producer&rarr;{@code me}); {@code me} stays ignorant
 * of join-requests. AFTER_COMMIT so a rolled-back transition publishes nothing.
 */
@Component
class JoinRequestSseListener {

    static final String EVENT_KIND = "join-request.status-changed";

    private static final Logger LOG = LoggerFactory.getLogger(JoinRequestSseListener.class);

    private final MePrincipalEventBus bus;
    private final JoinRequestAdminRecipients adminRecipients;

    JoinRequestSseListener(MePrincipalEventBus bus, JoinRequestAdminRecipients adminRecipients) {
        this.bus = bus;
        this.adminRecipients = adminRecipients;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onStatusChanged(JoinRequestStatusChangedEvent event) {
        Map<String, Object> payload = Map.of(
                "requestId", event.requestId().toString(),
                "status", event.status().name());
        // Best-effort, post-commit: a directory outage resolving admin subs must
        // not surface as an error on the already-committed transition.
        try {
            if (event.status() == JoinRequestStatus.PENDING) {
                for (AdminRecipient admin : Tenants.runAs(
                        event.clubId(), () -> adminRecipients.forClub(event.clubId()))) {
                    bus.publish(admin.sub().toString(), EVENT_KIND, payload);
                }
            } else {
                bus.publish(event.pilotSub().toString(), EVENT_KIND, payload);
            }
        } catch (RuntimeException e) {
            LOG.error("Join-request {} SSE notification failed", event.requestId(), e);
        }
    }
}
