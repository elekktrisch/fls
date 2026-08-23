package ch.alpenflight.tenancy.sandbox.application;

import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
import ch.alpenflight.tenancy.sandbox.application.DemoSeatLeaseService.LeasedDemoSeat;
import ch.alpenflight.tenancy.sandbox.domain.DemoSeatTokenIssuer;
import ch.alpenflight.tenancy.sandbox.domain.DemoSeatTokenIssuer.IssuedAccessToken;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DemoSessionStarter {

    public record StartedDemoSession(
            String accessToken,
            long expiresInSeconds,
            Instant leaseExpiresAt) {
    }

    public record LeasedSeatAuditPayload(int seatNumber, UUID clubId) {
    }

    static final String AUDITED_ENTITY_TYPE = "DemoSeatLease";

    private final DemoSeatLeaseService leases;
    private final DemoSeatTokenIssuer seatTokens;
    private final AuditTrail auditTrail;
    private final TransactionTemplate oneTransactionForTheAuditRow;

    public DemoSessionStarter(DemoSeatLeaseService leases,
                              DemoSeatTokenIssuer seatTokens,
                              AuditTrail auditTrail,
                              PlatformTransactionManager transactionManager) {
        this.leases = leases;
        this.seatTokens = seatTokens;
        this.auditTrail = auditTrail;
        this.oneTransactionForTheAuditRow = new TransactionTemplate(transactionManager);
        this.oneTransactionForTheAuditRow.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public StartedDemoSession startFor(String visitorAddress) {
        LeasedDemoSeat seat = leases.leaseFreeSeatFor(visitorAddress);
        IssuedAccessToken token = issueOrGiveTheSeatBackToThePool(seat);
        recordTheLease(seat, visitorAddress);
        return new StartedDemoSession(
                token.accessToken(), token.expiresInSeconds(), seat.leaseExpiresAt());
    }

    private IssuedAccessToken issueOrGiveTheSeatBackToThePool(LeasedDemoSeat seat) {
        try {
            return seatTokens.issueAccessTokenForSeatPrincipal(seat.keycloakUsername());
        } catch (RuntimeException theIdentityProviderIssuedNoToken) {
            leases.returnSeatToPool(seat.seatId());
            throw theIdentityProviderIssuedNoToken;
        }
    }

    private void recordTheLease(LeasedDemoSeat seat, String visitorAddress) {
        oneTransactionForTheAuditRow.executeWithoutResult(status ->
                auditTrail.recordAnonymousPublicSubmission(
                        AuditAction.CREATE,
                        AuditedTarget.created(
                                AUDITED_ENTITY_TYPE,
                                seat.seatId(),
                                new LeasedSeatAuditPayload(
                                        seat.seatNumber(), seat.clubId().value())),
                        visitorAddress));
    }
}
