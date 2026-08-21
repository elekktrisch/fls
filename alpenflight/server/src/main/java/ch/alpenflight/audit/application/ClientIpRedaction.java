package ch.alpenflight.audit.application;

import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
import ch.alpenflight.audit.domain.MutationAuditEvent;
import ch.alpenflight.audit.domain.MutationAuditEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ClientIpRedaction {

    static final String REDACTED_CELL_AUDIT_ENTITY_TYPE = "MutationAuditEventClientIp";

    private final MutationAuditEventRepository events;
    private final AuditTrail auditTrail;
    private final Clock clock;

    public ClientIpRedaction(MutationAuditEventRepository events,
                             AuditTrail auditTrail,
                             Clock clock) {
        this.events = events;
        this.auditTrail = auditTrail;
        this.clock = clock;
    }

    @Transactional
    public int redactEveryClientIpPastRetentionInTheCurrentTenant() {
        Instant now = clock.instant();
        int redacted = 0;
        for (MutationAuditEvent row : events.findClientIpBearingRowsOccurredOnOrBefore(
                MutationAuditEvent.clientIpRetentionCutoff(now))) {
            if (!row.clientIpRetentionHasElapsedAt(now)) {
                continue;
            }
            row.redactClientIpKeepingEveryOtherCell();
            events.saveRedactedRow(row);
            redacted++;
        }
        return redacted;
    }

    @Transactional
    public int redactEveryClientIpPastRetentionOnARowNoClubTenantOwns() {
        return events.redactEveryClientIpNoClubTenantOwnsOccurredOnOrBefore(
                MutationAuditEvent.clientIpRetentionCutoff(clock.instant()));
    }

    @Transactional
    public boolean redactOneClientIpAheadOfTheRetentionWindow(UUID auditEventId) {
        Optional<MutationAuditEvent> targeted = events.findById(auditEventId);
        if (targeted.isEmpty()) {
            return false;
        }
        MutationAuditEvent row = targeted.get();
        row.redactClientIpKeepingEveryOtherCell();
        events.saveRedactedRow(row);
        auditTrail.record(AuditAction.CLIENT_IP_REDACTED,
                new AuditedTarget(REDACTED_CELL_AUDIT_ENTITY_TYPE, auditEventId, null, null));
        return true;
    }
}
