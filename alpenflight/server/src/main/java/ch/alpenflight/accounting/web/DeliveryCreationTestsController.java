package ch.alpenflight.accounting.web;

import ch.alpenflight.accounting.application.DeliveryCreationTestDtos.DeliveryCreationTestDetail;
import ch.alpenflight.accounting.application.DeliveryCreationTestDtos.DeliveryCreationTestListItem;
import ch.alpenflight.accounting.application.DeliveryCreationTestDtos.DeliveryCreationTestWriteRequest;
import ch.alpenflight.accounting.application.DeliveryCreationTestsService;
import ch.alpenflight.platform.tenancy.UserPrincipalLookup;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for the {@code DeliveryCreationTest} aggregate — the rules-engine
 * regression harness (J-9, replacing legacy {@code masterdata/deliveryCreationTests/}).
 * The path keeps the legacy single-token form {@code /api/v1/deliverycreationtests}.
 *
 * <p>DeliveryCreationTest is <strong>tenant-scoped</strong> via Hibernate's
 * {@code @TenantId} discriminator on {@code DeliveryCreationTest.operatingClubId}
 * (ADR 0008). Reads and writes are filtered structurally to the caller's tenant;
 * a CLUB_ADMINISTRATOR of A asking for B's harness receives a {@code 404 Not
 * Found} — the row is invisible under A's scope, not a {@code 403} that would
 * confirm it exists.
 *
 * <p>Role gate: CLUB_ADMINISTRATOR for every endpoint (the harness is a club-admin
 * masterdata surface). Audit: every mutating method reaches
 * {@code AuditTrail.record} transitively through {@link DeliveryCreationTestsService}
 * (CREATE / UPDATE / DELETE), so {@code ControllerAuditCoverageTest} traces the
 * call chain without an explicit {@code @AuditedBy} escape.
 *
 * <p>The dry-run (capture expected) + run-test (run engine + diff) endpoints are
 * T-15; they extend this controller. {@code @Operation(operationId=...)} pins a
 * stable generated-client method name (orval) on every endpoint.
 */
@RestController
@RequestMapping(path = "/api/v1/deliverycreationtests", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "DeliveryCreationTests",
        description = "DeliveryCreationTest CRUD (per-club tenant-scoped rules-engine regression harness).")
public class DeliveryCreationTestsController {

    private final DeliveryCreationTestsService service;
    private final UserPrincipalLookup userLookup;

    public DeliveryCreationTestsController(DeliveryCreationTestsService service,
                                          UserPrincipalLookup userLookup) {
        this.service = service;
        this.userLookup = userLookup;
    }

    @Operation(operationId = "listDeliveryCreationTest",
            summary = "List the caller's tenant DeliveryCreationTests, ordered by name.")
    @ApiResponse(responseCode = "200", description = "Array of DeliveryCreationTest list-row projections.")
    @GetMapping
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public List<DeliveryCreationTestListItem> listDeliveryCreationTest() {
        return service.listTests();
    }

    @Operation(operationId = "getDeliveryCreationTest",
            summary = "Read a single DeliveryCreationTest by id.")
    @ApiResponse(responseCode = "200", description = "DeliveryCreationTest detail projection.")
    @ApiResponse(responseCode = "404",
            description = "No active harness with that id (includes cross-tenant lookup).")
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public DeliveryCreationTestDetail getDeliveryCreationTest(@PathVariable UUID id) {
        return service.getDetail(id);
    }

    @Operation(operationId = "createDeliveryCreationTest",
            summary = "Create a new DeliveryCreationTest in the caller's tenant.")
    @ApiResponse(responseCode = "201", description = "Created.")
    @ApiResponse(responseCode = "400", description = "Validation failed.")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public ResponseEntity<DeliveryCreationTestDetail> createDeliveryCreationTest(
            @Valid @RequestBody DeliveryCreationTestWriteRequest req) {
        DeliveryCreationTestDetail created = service.create(req);
        return ResponseEntity.created(URI.create("/api/v1/deliverycreationtests/" + created.id()))
                .body(created);
    }

    @Operation(operationId = "updateDeliveryCreationTest",
            summary = "Update a DeliveryCreationTest.")
    @ApiResponse(responseCode = "200", description = "Updated.")
    @ApiResponse(responseCode = "400", description = "Validation failed.")
    @ApiResponse(responseCode = "404",
            description = "No active harness with that id (includes cross-tenant lookup).")
    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public DeliveryCreationTestDetail updateDeliveryCreationTest(
            @PathVariable UUID id,
            @Valid @RequestBody DeliveryCreationTestWriteRequest req) {
        return service.update(id, req);
    }

    @Operation(operationId = "deleteDeliveryCreationTest",
            summary = "Soft-delete a DeliveryCreationTest.")
    @ApiResponse(responseCode = "204", description = "Deleted.")
    @ApiResponse(responseCode = "404",
            description = "No active harness with that id (includes cross-tenant lookup).")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public ResponseEntity<Void> deleteDeliveryCreationTest(@PathVariable UUID id,
                                                           @AuthenticationPrincipal @Nullable Jwt jwt) {
        service.delete(id, principalUserId(jwt));
        return ResponseEntity.noContent().build();
    }

    private @Nullable UUID principalUserId(@Nullable Jwt jwt) {
        if (jwt == null) {
            return null;
        }
        return userLookup.resolveUserIdFor(jwt).orElse(null);
    }
}
