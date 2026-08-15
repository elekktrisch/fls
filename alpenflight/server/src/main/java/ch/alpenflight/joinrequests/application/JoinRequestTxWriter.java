package ch.alpenflight.joinrequests.application;

import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
import ch.alpenflight.joinrequests.domain.JoinRequest;
import ch.alpenflight.joinrequests.domain.JoinRequestRepository;
import com.github.f4b6a3.uuid.UuidCreator;
import java.time.Clock;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class JoinRequestTxWriter {

    private static final String AUDIT_ENTITY_TYPE = "JoinRequest";

    private final JoinRequestRepository requests;
    private final AuditTrail auditTrail;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    JoinRequestTxWriter(JoinRequestRepository requests,
                        AuditTrail auditTrail,
                        ApplicationEventPublisher events,
                        Clock clock) {
        this.requests = requests;
        this.auditTrail = auditTrail;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    JoinRequest file(UUID sub, String email, String friendlyName, UUID clubId,
                     @Nullable String note) {
        JoinRequest r = JoinRequest.submit(
                UuidCreator.getTimeOrderedEpoch(), sub, email, friendlyName, clubId, note, clock);
        JoinRequest saved = requests.save(r);
        auditTrail.record(AuditAction.CREATE,
                AuditedTarget.created(AUDIT_ENTITY_TYPE, saved.getId(), saved));
        events.publishEvent(JoinRequestStatusChangedEvent.from(saved));
        return saved;
    }

    @Transactional
    JoinRequest withdraw(UUID requestId, UUID callerSub) {
        JoinRequest r = requests.findById(requestId)
                .orElseThrow(() -> new JoinRequestNotFoundException(requestId));
        if (!r.getKeycloakSub().equals(callerSub)) {
            throw new NotJoinRequestOwnerException();
        }
        r.withdraw(clock);
        JoinRequest saved = requests.save(r);
        auditTrail.record(AuditAction.STATE_TRANSITION,
                AuditedTarget.updated(AUDIT_ENTITY_TYPE, saved.getId(), saved, saved));
        events.publishEvent(JoinRequestStatusChangedEvent.from(saved));
        return saved;
    }
}
