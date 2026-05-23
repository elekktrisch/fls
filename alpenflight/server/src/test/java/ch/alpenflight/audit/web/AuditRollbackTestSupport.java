package ch.alpenflight.audit.web;

import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.clubs.domain.ClubRepository;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only seam that drives REQUIRES_NEW semantics through a real HTTP
 * request. The publishing path mutates inside an {@code @Transactional}
 * method (real Spring-managed tx via {@link ClubRepository}), then throws —
 * proving that
 *
 * <ul>
 *   <li>the AFTER_COMMIT listener does NOT fire (no success row lands),
 *       and</li>
 *   <li>the {@link RequestAuditFilter}'s synthetic-failure path DOES land
 *       a {@code failed=true} row in its own REQUIRES_NEW transaction.</li>
 * </ul>
 *
 * <p>Component-scanned during the test run; {@code @Hidden} keeps the
 * endpoint out of the OpenAPI snapshot. The endpoint is at
 * {@code /api/v1/__test__/audit-rollback/{id}} so no production test
 * accidentally calls it.
 */
final class AuditRollbackTestSupport {

    private AuditRollbackTestSupport() {}

    @RestController
    @RequestMapping("/api/v1/__test__/audit-rollback")
    @Hidden
    static class AuditRollbackTestController {

        private final AuditRollbackTestService service;

        AuditRollbackTestController(AuditRollbackTestService service) {
            this.service = service;
        }

        @PostMapping("/{id}")
        public void triggerRollback(@PathVariable UUID id) {
            service.publishAuditThenExplode(id);
        }
    }

    @Service
    static class AuditRollbackTestService {

        private final AuditTrail auditTrail;
        private final ClubRepository clubs;

        AuditRollbackTestService(AuditTrail auditTrail, ClubRepository clubs) {
            this.auditTrail = auditTrail;
            this.clubs = clubs;
        }

        /**
         * Publish a success audit event then throw. The surrounding
         * {@code @Transactional} ensures we have a real Spring-managed tx:
         * the AFTER_COMMIT phase is conditioned on commit, so the throw
         * here suppresses the audit row from the publish path. A real
         * {@code ClubRepository} query forces the tx to materialise.
         */
        @Transactional
        public void publishAuditThenExplode(UUID targetId) {
            for (Club ignored : clubs.findAllActive()) {
                break;
            }
            auditTrail.record(AuditAction.CREATE,
                    AuditedTarget.created("RollbackProbe", targetId,
                            Map.of("probe", "value", "targetId", targetId.toString())));
            throw new ForcedRollback("rollback after audit publish");
        }
    }

    /** Marker exception so the test can distinguish the forced throw from real bugs. */
    static class ForcedRollback extends RuntimeException {
        ForcedRollback(String msg) {
            super(msg);
        }
    }
}
