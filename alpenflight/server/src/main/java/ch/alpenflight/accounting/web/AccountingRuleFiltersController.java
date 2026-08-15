package ch.alpenflight.accounting.web;

import ch.alpenflight.accounting.application.AccountingRuleFilterDtos.AccountingRuleFilterDetail;
import ch.alpenflight.accounting.application.AccountingRuleFilterDtos.AccountingRuleFilterListItem;
import ch.alpenflight.accounting.application.AccountingRuleFilterDtos.AccountingRuleFilterWriteRequest;
import ch.alpenflight.accounting.application.AccountingRuleFiltersService;
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

@RestController
@RequestMapping(path = "/api/v1/accounting-rule-filters", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "AccountingRuleFilters",
        description = "AccountingRuleFilter CRUD (per-club tenant-scoped billing-rule config).")
public class AccountingRuleFiltersController {

    private final AccountingRuleFiltersService service;
    private final UserPrincipalLookup userLookup;

    public AccountingRuleFiltersController(AccountingRuleFiltersService service,
                                           UserPrincipalLookup userLookup) {
        this.service = service;
        this.userLookup = userLookup;
    }

    @Operation(operationId = "listAccountingRuleFilters",
            summary = "List the caller's tenant AccountingRuleFilters, ordered by sort indicator.")
    @ApiResponse(responseCode = "200", description = "Array of AccountingRuleFilter list-row projections.")
    @GetMapping
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public List<AccountingRuleFilterListItem> listAccountingRuleFilters() {
        return service.listFilters();
    }

    @Operation(operationId = "getAccountingRuleFilter",
            summary = "Read a single AccountingRuleFilter by id.")
    @ApiResponse(responseCode = "200", description = "AccountingRuleFilter detail projection.")
    @ApiResponse(responseCode = "404",
            description = "No active filter with that id (includes cross-tenant lookup).")
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public AccountingRuleFilterDetail getAccountingRuleFilter(@PathVariable UUID id) {
        return service.getDetail(id);
    }

    @Operation(operationId = "createAccountingRuleFilter",
            summary = "Create a new AccountingRuleFilter in the caller's tenant.")
    @ApiResponse(responseCode = "201", description = "Created.")
    @ApiResponse(responseCode = "400", description = "Validation failed.")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public ResponseEntity<AccountingRuleFilterDetail> createAccountingRuleFilter(
            @Valid @RequestBody AccountingRuleFilterWriteRequest req) {
        AccountingRuleFilterDetail created = service.create(req);
        return ResponseEntity.created(URI.create("/api/v1/accounting-rule-filters/" + created.id()))
                .body(created);
    }

    @Operation(operationId = "updateAccountingRuleFilter",
            summary = "Update an AccountingRuleFilter.")
    @ApiResponse(responseCode = "200", description = "Updated.")
    @ApiResponse(responseCode = "400", description = "Validation failed.")
    @ApiResponse(responseCode = "404",
            description = "No active filter with that id (includes cross-tenant lookup).")
    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public AccountingRuleFilterDetail updateAccountingRuleFilter(
            @PathVariable UUID id,
            @Valid @RequestBody AccountingRuleFilterWriteRequest req) {
        return service.update(id, req);
    }

    @Operation(operationId = "deleteAccountingRuleFilter",
            summary = "Soft-delete an AccountingRuleFilter.")
    @ApiResponse(responseCode = "204", description = "Deleted.")
    @ApiResponse(responseCode = "404",
            description = "No active filter with that id (includes cross-tenant lookup).")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public ResponseEntity<Void> deleteAccountingRuleFilter(@PathVariable UUID id,
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
