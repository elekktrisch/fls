package ch.alpenflight.platform.web;

import ch.alpenflight.audit.domain.AuditedBy;
import ch.alpenflight.platform.scheduling.JobDtos.JobResponse;
import ch.alpenflight.platform.scheduling.JobDtos.JobRunResponse;
import ch.alpenflight.platform.scheduling.JobRegistry;
import ch.alpenflight.platform.scheduling.JobRegistry.JobNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/admin/jobs", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Admin Jobs", description = "System-administrator scheduled-jobs console.")
@AuditedBy("measuredJobAspect")
class JobsAdminController {

    private final JobRegistry registry;

    JobsAdminController(JobRegistry registry) {
        this.registry = registry;
    }

    @Operation(operationId = "listJobs", summary = "List every registered business job with its last run.")
    @ApiResponse(responseCode = "200", description = "The job registry projection.")
    @ApiResponse(responseCode = "403", description = "Caller is not SYSTEM_ADMINISTRATOR.")
    @GetMapping
    @PreAuthorize("hasRole('SYSTEM_ADMINISTRATOR')")
    List<JobResponse> listJobs() {
        return registry.list().stream().map(JobResponse::from).toList();
    }

    @Operation(operationId = "runJob", summary = "Run a business job once and return the resulting run.")
    @ApiResponse(responseCode = "200", description = "The completed (or failed) run projection.")
    @ApiResponse(responseCode = "403", description = "Caller is not SYSTEM_ADMINISTRATOR.")
    @ApiResponse(responseCode = "404", description = "No registered job with that name.")
    @PostMapping("/{name}/run")
    @PreAuthorize("hasRole('SYSTEM_ADMINISTRATOR')")
    JobRunResponse runJob(@PathVariable String name) {
        return JobRunResponse.of(registry.runOnce(name));
    }

    @ExceptionHandler(JobNotFoundException.class)
    ResponseEntity<Void> handleUnknownJob(JobNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
}
