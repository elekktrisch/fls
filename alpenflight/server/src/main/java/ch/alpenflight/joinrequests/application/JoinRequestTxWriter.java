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

/**
 * The tenant-scoped transactional unit for {@link JoinRequestsService}'s write
 * paths. A separate bean so the {@code @Transactional} boundary nests INSIDE
 * {@link ch.alpenflight.platform.tenancy.Tenants#runAs}: the tenant carrier must
 * stay set across the whole transaction — open, flush, AND commit — because
 * Hibernate re-resolves the {@code @TenantId} discriminator at flush time, and a
 * carrier cleared before commit would resolve {@code NO_TENANT} and reject the
 * row's assigned {@code club_id}. Self-invocation across a {@code @Transactional}
 * proxy wouldn't apply the advice, hence the dedicated bean. Audit emission lives
 * here too so the event joins the same transaction + tenant context.
 */
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
        // The @AuditRedact fields (note, email, friendlyName) land "[redacted]"
        // in the snapshot — the S-027 PII redaction is intrinsic to the field.
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
