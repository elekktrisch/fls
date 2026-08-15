package ch.alpenflight.me.web;

import ch.alpenflight.me.application.MeService;
import ch.alpenflight.me.application.MeView;
import ch.alpenflight.persons.application.PersonsService;
import ch.alpenflight.persons.application.SelfNotificationPrefsUpdate;
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
class MeNotificationPrefsController {

    private final PersonsService personsService;
    private final MeService meService;

    MeNotificationPrefsController(PersonsService personsService, MeService meService) {
        this.personsService = personsService;
        this.meService = meService;
    }

    @GetMapping(path = "/club-membership/notification-prefs")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            operationId = "getMyNotificationPrefs",
            summary = "Read the caller's own per-club notification preferences so the Notifications tab "
                    + "hydrates. Caller's Person + club resolved from the JWT → user → person_id / club_id "
                    + "— no :id. A caller with no linked Person, or no membership in the current club, gets 409.")
    @ApiResponse(responseCode = "200", description = "The caller's three notification preferences.")
    @ApiResponse(responseCode = "409", description = "No linked Person or no membership in the current club.")
    ResponseEntity<MeNotificationPrefsResponse> getNotificationPrefs(@AuthenticationPrincipal Jwt jwt) {
        Resolved r = resolveOwn(jwt);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(MeNotificationPrefsResponse.from(
                        personsService.getOwnNotificationPrefs(r.personId(), r.clubId())));
    }

    @PatchMapping(path = "/club-membership/notification-prefs", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(
            operationId = "updateMyNotificationPrefs",
            summary = "Edit the caller's own per-club notification preferences. Caller's Person + club "
                    + "resolved from the JWT → user → person_id / club_id — no :id. Changes ONLY the three "
                    + "toggles; admin-only membership fields (memberNumber / memberState / roles) stay "
                    + "untouched. Emits an audit event. A caller with no linked Person, or no membership in "
                    + "the current club, gets 409.")
    @ApiResponse(responseCode = "200", description = "Updated; body is the refreshed notification preferences.")
    @ApiResponse(responseCode = "409", description = "No linked Person or no membership in the current club.")
    ResponseEntity<MeNotificationPrefsResponse> updateNotificationPrefs(
            @Valid @RequestBody MeNotificationPrefsUpdateRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        Resolved r = resolveOwn(jwt);
        personsService.updateOwnNotificationPrefs(r.personId(), r.clubId(),
                new SelfNotificationPrefsUpdate(
                        f(req.receiveFlightReports()),
                        f(req.receiveAircraftReservationNotifications()),
                        f(req.receivePlanningDayRoleReminder())));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(MeNotificationPrefsResponse.from(
                        personsService.getOwnNotificationPrefs(r.personId(), r.clubId())));
    }

    private static boolean f(@org.jspecify.annotations.Nullable Boolean b) {
        return Boolean.TRUE.equals(b);
    }

    private Resolved resolveOwn(Jwt jwt) {
        MeView view = meService.resolve(jwt);
        UUID personId = view.personId();
        UUID clubId = view.clubId();
        if (personId == null || clubId == null) {
            throw new NoLinkedPersonException();
        }
        return new Resolved(personId, clubId);
    }

    private record Resolved(UUID personId, UUID clubId) {}
}
