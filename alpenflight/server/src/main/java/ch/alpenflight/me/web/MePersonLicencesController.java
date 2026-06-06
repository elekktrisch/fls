package ch.alpenflight.me.web;

import ch.alpenflight.me.application.MeService;
import ch.alpenflight.me.application.MeView;
import ch.alpenflight.persons.application.PersonsService;
import ch.alpenflight.persons.application.SelfLicencesUpdate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET + PATCH /api/v1/me/person/licences} — the caller-scoped Person
 * licence/medical self-edit (J-4 T-08, the FADP-sensitive Pilot tab). A
 * logged-in principal reads + edits their OWN Person aggregate's licence flags,
 * licence number, medical / instructor / part-M expiry dates and glider
 * start-permission flags.
 *
 * <p><strong>No {@code :id} path param.</strong> The Person is resolved from the
 * JWT {@code sub} → the caller's {@code t_user} row → its {@code person_id} (via
 * {@link MeService#resolve}), never from the request — so a caller can only ever
 * read / mutate their own linked Person. Contact / name / membership fields are
 * NOT on the request DTO; they are preserved unchanged.
 *
 * <p>The PATCH emits a {@code person.licences_updated} audit event with a
 * READABLE before/after diff (AC4): {@link PersonsService#updateOwnLicences}
 * records it under the {@code PersonLicences} audit entity type, which carries
 * an explicit allow-list (unlike the deny-all {@code Person} type), so a
 * sysadmin reading {@code t_mutation_audit_event} sees the changed licence /
 * medical fields — the FADP-sensitive provenance the journey requires.
 *
 * <p>If the caller's user row has no linked Person ({@code person_id} null) both
 * surfaces fail closed with {@link NoLinkedPersonException} → 409 (the SPA shell
 * also gates the tab, but the endpoint is safe on its own).
 *
 * <p>Authz: any authenticated principal — the surface is principal-scoped by
 * construction, like {@code GET /api/v1/me}.
 */
@RestController
@RequestMapping(path = "/api/v1/me", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "me", description = "Authenticated-principal view")
class MePersonLicencesController {

    private final PersonsService personsService;
    private final MeService meService;

    MePersonLicencesController(PersonsService personsService, MeService meService) {
        this.personsService = personsService;
        this.meService = meService;
    }

    @GetMapping(path = "/person/licences")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            operationId = "getMyLicences",
            summary = "Read the caller's own Person licence/medical fields so the Pilot tab hydrates. "
                    + "Caller's Person resolved from the JWT → user → person_id — no :id. A caller with "
                    + "no linked Person gets 409.")
    @ApiResponse(responseCode = "200", description = "The caller's editable licence/medical shape.")
    @ApiResponse(responseCode = "409", description = "The caller's user row has no linked Person record.")
    ResponseEntity<MePersonLicencesResponse> getLicences(@AuthenticationPrincipal Jwt jwt) {
        UUID personId = resolveOwnPersonId(jwt);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(MePersonLicencesResponse.from(personsService.getOwnLicences(personId)));
    }

    @PatchMapping(path = "/person/licences", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(
            operationId = "updateMyLicences",
            summary = "Edit the caller's own Person licence/medical fields. Caller's Person resolved "
                    + "from the JWT → user → person_id — no :id. Emits a person.licences_updated audit "
                    + "event with a readable before/after diff (FADP-sensitive provenance). A caller "
                    + "with no linked Person gets 409.")
    @ApiResponse(responseCode = "200", description = "Updated; body is the refreshed licence/medical shape.")
    @ApiResponse(responseCode = "400", description = "Validation failed (over-length licence number).")
    @ApiResponse(responseCode = "409", description = "The caller's user row has no linked Person record.")
    ResponseEntity<MePersonLicencesResponse> updateLicences(
            @Valid @RequestBody MePersonLicencesUpdateRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        UUID personId = resolveOwnPersonId(jwt);
        personsService.updateOwnLicences(personId, new SelfLicencesUpdate(
                f(req.hasMotorPilotLicence()), f(req.hasTowPilotLicence()),
                f(req.hasGliderInstructorLicence()), f(req.hasGliderPilotLicence()),
                f(req.hasGliderTraineeLicence()), f(req.hasGliderPaxLicence()), f(req.hasTmgLicence()),
                f(req.hasWinchOperatorLicence()), f(req.hasMotorInstructorLicence()),
                f(req.hasPartMLicence()),
                req.licenceNumber(),
                req.medicalClass1ExpireDate(), req.medicalClass2ExpireDate(),
                req.medicalLaplExpireDate(),
                req.gliderInstructorLicenceExpireDate(), req.motorInstructorLicenceExpireDate(),
                req.partMLicenceExpireDate(),
                f(req.hasGliderTowingStartPermission()), f(req.hasGliderSelfStartPermission()),
                f(req.hasGliderWinchStartPermission()),
                f(req.receiveOwnedAircraftStatisticReports())));
        // Re-read so the SPA reflects persisted state on the PATCH response.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(MePersonLicencesResponse.from(personsService.getOwnLicences(personId)));
    }

    /** Coerce a nullable wire flag to a primitive — absent / null → false. */
    private static boolean f(@org.jspecify.annotations.Nullable Boolean b) {
        return Boolean.TRUE.equals(b);
    }

    private UUID resolveOwnPersonId(Jwt jwt) {
        MeView view = meService.resolve(jwt);
        UUID personId = view.personId();
        if (personId == null) {
            throw new NoLinkedPersonException();
        }
        return personId;
    }
}
