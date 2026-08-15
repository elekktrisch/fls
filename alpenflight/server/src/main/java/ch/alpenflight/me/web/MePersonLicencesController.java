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
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(MePersonLicencesResponse.from(personsService.getOwnLicences(personId)));
    }

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
