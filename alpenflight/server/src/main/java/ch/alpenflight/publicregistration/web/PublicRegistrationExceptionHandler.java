package ch.alpenflight.publicregistration.web;

import ch.alpenflight.publicregistration.application.PublicClubUnavailableException;
import ch.alpenflight.publicregistration.application.PublicRegistrationInvalidException;
import ch.alpenflight.publicregistration.application.PublicRegistrationThrottledException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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

    @ExceptionHandler(PublicRegistrationInvalidException.class)
    ResponseEntity<Void> handleInvalid(PublicRegistrationInvalidException e) {
        return ResponseEntity.badRequest().build();
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Void> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().build();
    }

    @ExceptionHandler(PublicRegistrationThrottledException.class)
    ResponseEntity<Void> handleThrottled(PublicRegistrationThrottledException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()))
                .build();
    }
}
