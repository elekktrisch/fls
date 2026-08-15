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
