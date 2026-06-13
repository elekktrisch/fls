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
        throw e;
    }

    @ExceptionHandler(InvalidClubReferenceException.class)
    ResponseEntity<ApiError> handleInvalidReference(InvalidClubReferenceException e) {
        String message = e.getMessage();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(e.getField(), message == null ? "Invalid reference" : message));
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
