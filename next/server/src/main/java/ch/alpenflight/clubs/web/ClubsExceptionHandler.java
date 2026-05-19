package ch.alpenflight.clubs.web;

import ch.alpenflight.clubs.domain.ClubNotFoundException;
import ch.alpenflight.clubs.domain.InvalidClubReferenceException;
import ch.alpenflight.clubs.domain.SlugAlreadyExistsException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates Clubs domain exceptions to HTTP responses. Holds the only
 * Spring-web coupling of the Clubs error vocabulary — the exception types
 * themselves stay in {@code clubs.domain} free of {@code @ResponseStatus}
 * per ADR 0023 (domain depends on the JDK + JPA only).
 */
// basePackageClasses scopes the advice to the Clubs controller's package —
// keeps a future module that throws the same exception type by mistake
// from inheriting Clubs' HTTP status mapping. Module-local error
// vocabulary, module-local advice.
@RestControllerAdvice(basePackageClasses = ClubsController.class)
class ClubsExceptionHandler {

    /** Typed error body for 400 / 409. RFC-7807-shaped enough to evolve later. */
    public record ApiError(String field, String message) {}

    @ExceptionHandler(ClubNotFoundException.class)
    ResponseEntity<Void> handleNotFound(ClubNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @ExceptionHandler(SlugAlreadyExistsException.class)
    ResponseEntity<Void> handleSlugConflict(SlugAlreadyExistsException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    @ExceptionHandler(InvalidClubReferenceException.class)
    ResponseEntity<ApiError> handleInvalidReference(InvalidClubReferenceException e) {
        String message = e.getMessage();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(e.getField(), message == null ? "Invalid reference" : message));
    }
}
