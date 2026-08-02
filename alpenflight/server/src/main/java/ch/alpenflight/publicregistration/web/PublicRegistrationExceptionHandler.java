package ch.alpenflight.publicregistration.web;

import ch.alpenflight.publicregistration.application.PublicClubUnavailableException;
import ch.alpenflight.publicregistration.application.PublicRegistrationThrottledException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Anonymous error contract for the public-registration surface. Scoped to
 * {@link PublicRegistrationController}'s package so no other module inherits
 * the mapping.
 *
 * <p>Bodies are empty by design: the status is the only thing an unauthenticated
 * caller learns. The 404-vs-403 split itself is justified on
 * {@link PublicClubUnavailableException.Reason}.
 */
@RestControllerAdvice(basePackageClasses = PublicRegistrationController.class)
class PublicRegistrationExceptionHandler {

    @ExceptionHandler(PublicClubUnavailableException.class)
    ResponseEntity<Void> handleUnavailable(PublicClubUnavailableException e) {
        HttpStatus status = switch (e.reason()) {
            case UNKNOWN -> HttpStatus.NOT_FOUND;
            case REGISTRATION_CLOSED -> HttpStatus.FORBIDDEN;
        };
        return ResponseEntity.status(status).build();
    }

    @ExceptionHandler(PublicRegistrationThrottledException.class)
    ResponseEntity<Void> handleThrottled(PublicRegistrationThrottledException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()))
                .build();
    }
}
