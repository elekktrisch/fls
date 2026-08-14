package ch.alpenflight.audit.web;

import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.clubs.domain.ClubRepository;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

final class AuditRollbackTestSupport {

    private AuditRollbackTestSupport() {}

    @RestController
    @RequestMapping("/api/v1/__test__/audit-rollback")
    @Hidden
    @Profile("test")
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
    @Profile("test")
    static class AuditRollbackTestService {

        private final AuditTrail auditTrail;
        private final ClubRepository clubs;

        AuditRollbackTestService(AuditTrail auditTrail, ClubRepository clubs) {
            this.auditTrail = auditTrail;
            this.clubs = clubs;
        }

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

    static class ForcedRollback extends RuntimeException {
        ForcedRollback(String msg) {
            super(msg);
        }
    }
}
