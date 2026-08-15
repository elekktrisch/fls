package ch.alpenflight.me.web;

import ch.alpenflight.me.application.MeService;
import ch.alpenflight.me.application.MeView;
import ch.alpenflight.persons.application.PersonsService;
import ch.alpenflight.persons.application.SelfContactUpdate;
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
class MePersonController {

    private final PersonsService personsService;
    private final MeService meService;

    MePersonController(PersonsService personsService, MeService meService) {
        this.personsService = personsService;
        this.meService = meService;
    }

    @GetMapping(path = "/person")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            operationId = "getMyPerson",
            summary = "Read the caller's own Person contact / address fields (plus the read-only "
                    + "name fields for display) so the Personal tab hydrates. Caller's Person "
                    + "resolved from the JWT → user → person_id — no :id. A caller with no linked "
                    + "Person gets 409.")
    @ApiResponse(responseCode = "200", description = "The caller's editable contact / address shape.")
    @ApiResponse(responseCode = "409", description = "The caller's user row has no linked Person record.")
    ResponseEntity<MePersonResponse> getPerson(@AuthenticationPrincipal Jwt jwt) {
        UUID personId = resolveOwnPersonId(jwt);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(MePersonResponse.from(personsService.getOwnContact(personId)));
    }

    @PatchMapping(path = "/person", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(
            operationId = "updateMyPerson",
            summary = "Edit the caller's own Person contact / address fields (address, zip/city/region, "
                    + "country, phones, private/business email, birthday). Caller's Person resolved from "
                    + "the JWT → user → person_id — no :id. Name fields are admin-only and preserved; "
                    + "a caller with no linked Person gets 409.")
    @ApiResponse(responseCode = "200", description = "Updated; body is the refreshed /me projection.")
    @ApiResponse(responseCode = "400", description = "Validation failed (bad email, over-length).")
    @ApiResponse(responseCode = "409", description = "The caller's user row has no linked Person record.")
    ResponseEntity<MeResponse> updatePerson(@Valid @RequestBody MePersonUpdateRequest req,
                                            @AuthenticationPrincipal Jwt jwt) {
        UUID personId = resolveOwnPersonId(jwt);
        personsService.updateOwnContact(personId, new SelfContactUpdate(
                req.addressLine1(), req.addressLine2(), req.zip(), req.city(), req.region(),
                req.countryId(),
                req.privatePhone(), req.mobilePhone(), req.businessPhone(), req.faxNumber(),
                req.emailPrivate(), req.emailBusiness(),
                Boolean.TRUE.equals(req.preferMailToBusinessMail()),
                req.birthday()));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(MeResponse.from(meService.resolve(jwt)));
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
