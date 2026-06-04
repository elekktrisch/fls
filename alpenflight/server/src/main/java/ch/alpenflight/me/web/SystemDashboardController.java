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

/**
 * {@code GET /api/v1/me/system-dashboard} — the sysadmin dashboard variant's
 * cross-tenant tile totals (J-3 T-10, consumed by T-11's sysadmin variant
 * component).
 *
 * <p>Mirrors {@link ClubDashboardController}'s shape but is the deliberate
 * opposite of it: the totals span ALL clubs/tenants rather than the caller's.
 * Lives in the {@code me} module and composes the numbers through the owning
 * modules' published application APIs ({@link SystemDashboardService}), never
 * their internals.
 *
 * <p>Authz: {@code SYSTEM_ADMINISTRATOR}-gated — a club-admin or pilot is
 * rejected 403. Works for a tenant-less sysadmin principal (no {@code clubId}
 * claim, per the J-2 audit work): the totals derive from enumerating all clubs,
 * not from the caller's tenant.
 */
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
        // Cross-tenant aggregate behind a bearer-bound endpoint — no-store so
        // an intermediary cache can't serve it onward.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(body);
    }
}
