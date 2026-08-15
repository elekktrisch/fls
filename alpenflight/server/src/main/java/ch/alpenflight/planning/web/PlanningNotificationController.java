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
