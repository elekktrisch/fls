package ch.alpenflight.clubs.web;

import ch.alpenflight.clubs.domain.ClubKeyAlreadyExistsException;
import ch.alpenflight.clubs.domain.ClubNotFoundException;
import ch.alpenflight.clubs.domain.InvalidClubReferenceException;
import ch.alpenflight.clubs.domain.SlugAlreadyExistsException;
import ch.alpenflight.platform.web.ProblemResponses;
import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates Clubs domain exceptions to HTTP responses. Holds the only
 * Spring-web coupling of the Clubs error vocabulary — the exception types
 * themselves stay in {@code clubs.domain} free of {@code @ResponseStatus}
 * per ADR 0023.
 */
// basePackageClasses scopes the advice to the Clubs controller's package —
// keeps a future module that throws the same exception type by mistake
// from inheriting Clubs' HTTP status mapping. Module-local error
// vocabulary, module-local advice.
@RestControllerAdvice(basePackageClasses = ClubsController.class)
class ClubsExceptionHandler {

    /** Typed error body for 400 / 409. RFC-7807-shaped enough to evolve later. */
    public record ApiError(String field, String message) {}

    private static final URI TYPE_CLUB_KEY_CONFLICT =
            URI.create("urn:alpenflight:problem:club-key-conflict");

    private static final URI TYPE_INVALID_REFERENCE =
            URI.create("urn:alpenflight:problem:invalid-club-reference");

    @ExceptionHandler(ClubNotFoundException.class)
    ResponseEntity<Void> handleNotFound(ClubNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @ExceptionHandler(SlugAlreadyExistsException.class)
    ResponseEntity<Void> handleSlugConflict(SlugAlreadyExistsException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    @ExceptionHandler(ClubKeyAlreadyExistsException.class)
    ResponseEntity<ProblemDetail> handleClubKeyConflict(ClubKeyAlreadyExistsException e) {
        return ProblemResponses.problem(clubKeyConflict(e.getMessage()));
    }

    /**
     * Race net for {@code ClubsService#persist}'s DIVE discrimination (J-26
     * T-07): Hibernate defers the INSERT to the transaction flush, so the
     * common duplicate-key path surfaces the {@link
     * DataIntegrityViolationException} at COMMIT — after the service's try
     * block. Discriminate the violated constraint here the same way:
     * {@code ux_club_key} → 409 problem-detail {@code field=clubKey};
     * {@code ux_club_slug} → the bare 409 the slug conflict always produced;
     * anything else propagates as 500 (a genuine bug deserves its 500, not a
     * slug mislabel — before this, duplicate clubKeys were raw 500s). Mirrors
     * {@code FlightTypesExceptionHandler#handleDataIntegrity} (T-05).
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException e) {
        String message = String.valueOf(e.getMostSpecificCause().getMessage());
        if (message.contains("ux_club_key")) {
            return ProblemResponses.problem(clubKeyConflict("Club key already in use."));
        }
        if (message.contains("ux_club_slug")) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        // FlightType is @TenantId-scoped, so the sysadmin path cannot pre-check
        // the reference through its repository the way countryId / clubStateId
        // are pre-checked; the FK is the only arbiter and its violation is a
        // caller error, not a 500.
        if (message.contains("fk_club_discovery_flight_type_id")) {
            return ProblemResponses.problem(invalidReference(
                    "discoveryFlightTypeId", "No flight type with that id exists in this club."));
        }
        throw e;
    }

    /**
     * Aggregate-enforced input rules (ADR 0022 directive 2) — e.g. a malformed
     * organiser email in {@code Club.setRegistrationOperatorEmails} — reach the
     * web layer as {@link IllegalArgumentException}; they are caller errors.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException e) {
        return ProblemResponses.badRequest(e);
    }

    @ExceptionHandler(InvalidClubReferenceException.class)
    ResponseEntity<ApiError> handleInvalidReference(InvalidClubReferenceException e) {
        String message = e.getMessage();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(e.getField(), message == null ? "Invalid reference" : message));
    }

    private static ProblemDetail invalidReference(String field, String detail) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setType(TYPE_INVALID_REFERENCE);
        pd.setTitle("Invalid club reference");
        pd.setDetail(detail);
        pd.setProperty("field", field);
        return pd;
    }

    private static ProblemDetail clubKeyConflict(@Nullable String detail) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setType(TYPE_CLUB_KEY_CONFLICT);
        pd.setTitle("Club key already in use");
        pd.setDetail(detail);
        pd.setProperty("field", "clubKey");
        return pd;
    }
}
