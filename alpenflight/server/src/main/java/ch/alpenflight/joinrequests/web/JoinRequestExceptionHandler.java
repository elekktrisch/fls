package ch.alpenflight.joinrequests.web;

import ch.alpenflight.joinrequests.application.AlreadyClubMemberException;
import ch.alpenflight.joinrequests.application.JoinRequestNotFoundException;
import ch.alpenflight.joinrequests.application.MissingPrincipalIdentityException;
import ch.alpenflight.joinrequests.application.NotJoinRequestOwnerException;
import ch.alpenflight.joinrequests.application.UnknownJoinCodeException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates join-request domain / application exceptions to HTTP responses
 * (S-178). Scoped to {@link JoinRequestController}'s package so another module
 * throwing the same Spring exception type inherits no status mapping. The
 * domain/application exception types stay free of Spring-web imports (ADR 0023).
 */
@RestControllerAdvice(basePackageClasses = JoinRequestController.class)
class JoinRequestExceptionHandler {

    @ExceptionHandler(UnknownJoinCodeException.class)
    ResponseEntity<Void> handleUnknownCode(UnknownJoinCodeException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @ExceptionHandler(JoinRequestNotFoundException.class)
    ResponseEntity<Void> handleNotFound(JoinRequestNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @ExceptionHandler(AlreadyClubMemberException.class)
    ResponseEntity<Void> handleAlreadyMember(AlreadyClubMemberException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    @ExceptionHandler(NotJoinRequestOwnerException.class)
    ResponseEntity<Void> handleNotOwner(NotJoinRequestOwnerException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    @ExceptionHandler(MissingPrincipalIdentityException.class)
    ResponseEntity<Void> handleMissingIdentity(MissingPrincipalIdentityException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }

    @ExceptionHandler(UnsupportedJoinRequestStatusFilterException.class)
    ResponseEntity<Void> handleBadStatusFilter(UnsupportedJoinRequestStatusFilterException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }

    /**
     * The partial UNIQUE {@code ux_join_request_alive} wins the race for a
     * second open request per (sub, club): Hibernate surfaces it as a
     * {@link DataIntegrityViolationException} at flush — map it to 409. A
     * violation of any other constraint propagates (a real bug keeps its 500).
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Void> handleDataIntegrity(DataIntegrityViolationException e) {
        String message = String.valueOf(e.getMostSpecificCause().getMessage());
        if (message.contains("ux_join_request_alive")) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        throw e;
    }
}
