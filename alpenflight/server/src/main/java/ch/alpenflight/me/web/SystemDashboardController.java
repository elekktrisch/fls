package ch.alpenflight.me.web;

import ch.alpenflight.me.application.SystemDashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "me", description = "Authenticated-principal view")
class SystemDashboardController {

    private final SystemDashboardService systemDashboard;

    SystemDashboardController(SystemDashboardService systemDashboard) {
        this.systemDashboard = systemDashboard;
    }

    @GetMapping(path = "/api/v1/me/system-dashboard", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('SYSTEM_ADMINISTRATOR')")
    @Operation(summary = "Sysadmin dashboard cross-tenant totals — total clubs / users / "
            + "flights across ALL tenants (deliberately tenant-unscoped). "
            + "SYSTEM_ADMINISTRATOR only.")
    ResponseEntity<SystemDashboardResponse> get() {
        SystemDashboardResponse body = SystemDashboardResponse.from(systemDashboard.totals());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(body);
    }
}
