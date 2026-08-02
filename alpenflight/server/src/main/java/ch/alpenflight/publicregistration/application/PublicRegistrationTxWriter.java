package ch.alpenflight.publicregistration.application;

import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
import ch.alpenflight.publicregistration.application.PublicClubResolver.PublicClub;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The tenant-scoped transactional unit for an accepted public registration. A
 * separate bean so the {@code @Transactional} boundary nests INSIDE
 * {@link ch.alpenflight.platform.tenancy.Tenants#runAs}: the tenant carrier must
 * stay set across open, flush AND commit, and self-invocation would not apply
 * the proxy advice. Audit emission lives here so the AFTER_COMMIT event carries
 * the same tenant as the write.
 *
 * <p>The actor resolves to anonymous — no {@code JwtAuthenticationToken} in the
 * security context — so the row lands with {@code system_actor=true} and both
 * actor identifiers null, scoped to the club the URL named.
 */
@Component
public class PublicRegistrationTxWriter {

    private static final String AUDIT_ENTITY_TYPE = "PublicFlightRegistration";

    private final AuditTrail auditTrail;

    PublicRegistrationTxWriter(AuditTrail auditTrail) {
        this.auditTrail = auditTrail;
    }

    @Transactional
    public void recordAccepted(PublicClub club, PublicRegistrationKind kind) {
        auditTrail.record(AuditAction.CREATE,
                AuditedTarget.created(AUDIT_ENTITY_TYPE, club.clubId(),
                        new AcceptedRegistration(kind, club.clubId())));
    }

    /** Non-PII audit snapshot: which flow, which club. */
    public record AcceptedRegistration(PublicRegistrationKind kind, UUID clubId) {}
}
