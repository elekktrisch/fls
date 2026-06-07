package ch.alpenflight.planning.web;

import ch.alpenflight.planning.application.PlanningDayNotificationJob;
import ch.alpenflight.planning.application.PlanningDayNotificationJob.RunSummary;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Guarded run-now affordance for the planning-day notification job (J-6 T-10c).
 * Triggers {@link PlanningDayNotificationJob#runForCurrentClub()} for the
 * caller's <em>own</em> club so the e2e fires the job deterministically and
 * asserts mailpit (the J-15 jobs console is not built yet).
 *
 * <p><strong>Guard.</strong> {@code @Profile({"dev","test"})} keeps the bean out
 * of production contexts (a real club can't trigger the batch by hand), and
 * {@code @PreAuthorize} restricts it to a {@code CLUB_ADMINISTRATOR}. The run is
 * tenant-scoped: the job reads the current tenant, so a caller only ever mails
 * for their own club. {@code @Hidden} keeps the route out of the OpenAPI
 * snapshot (a test/dev-only surface). The audit event is emitted inside the job
 * ({@link PlanningDayNotificationJob} → {@code AuditTrail.record}), satisfying
 * the mutating-endpoint audit-coverage guard transitively.
 */
@RestController
@RequestMapping("/api/v1/planning-days/notifications")
@Profile({"dev", "test"})
@Hidden
public class PlanningNotificationController {

    private final PlanningDayNotificationJob job;

    public PlanningNotificationController(PlanningDayNotificationJob job) {
        this.job = job;
    }

    @Operation(operationId = "runPlanningDayNotifications",
            summary = "Run the planning-day notification job for the current club (dev/test only).")
    @PostMapping("/run")
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public ResponseEntity<RunSummary> runPlanningDayNotifications() {
        return ResponseEntity.ok(job.runForCurrentClub());
    }
}
