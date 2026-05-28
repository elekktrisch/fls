package ch.alpenflight.persons.web;

import ch.alpenflight.persons.application.MemberStateSlice;
import ch.alpenflight.persons.application.PersonDtos.MemberStateListItem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant-scoped listitem endpoint for the {@code t_member_state} picker on
 * the Person form. Counterpart to S-047's cross-tenant reference-data
 * endpoints — same slim shape, but per-club scope (Hibernate's
 * {@code @TenantId} on {@code MemberState.clubId} filters to the caller).
 *
 * <p>First per-club listitem endpoint in the system — establishes the
 * {@code /api/v1/club/<thing>} URL convention. Full {@code t_member_state}
 * admin CRUD lands in a follow-up.
 */
@RestController
@RequestMapping(path = "/api/v1/club/member-states", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "MemberStates", description = "Per-club member-state listitem catalog (tenant-scoped reference data).")
public class MemberStatesController {

    private final MemberStateSlice slice;

    public MemberStatesController(MemberStateSlice slice) {
        this.slice = slice;
    }

    @Operation(summary = "List member-states in the caller's tenant, sorted alphabetically.")
    @ApiResponse(responseCode = "200", description = "Array of {id, name} listitems.")
    @GetMapping
    @PreAuthorize("hasRole('CLUB_ADMINISTRATOR')")
    public List<MemberStateListItem> listMemberStates() {
        return slice.listInCurrentTenant();
    }
}
