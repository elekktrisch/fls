package ch.alpenflight.joinrequests.web;

import ch.alpenflight.joinrequests.application.AlreadyClubMemberException;
import ch.alpenflight.joinrequests.application.CrossTenantPersonLinkException;
import ch.alpenflight.joinrequests.application.JoinRequestNotFoundException;
import ch.alpenflight.joinrequests.application.MissingPrincipalIdentityException;
import ch.alpenflight.joinrequests.application.NotJoinRequestOwnerException;
import ch.alpenflight.joinrequests.application.SubmitThrottledException;
import ch.alpenflight.joinrequests.application.UnknownJoinCodeException;
import ch.alpenflight.joinrequests.domain.IllegalJoinRequestStateException;
import ch.alpenflight.users.application.ForbiddenRoleGrantException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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

    @ExceptionHandler(IllegalJoinRequestStateException.class)
    ResponseEntity<Void> handleIllegalState(IllegalJoinRequestStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    @ExceptionHandler(CrossTenantPersonLinkException.class)
    ResponseEntity<Void> handleCrossTenantPerson(CrossTenantPersonLinkException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    @ExceptionHandler(ForbiddenRoleGrantException.class)
    ResponseEntity<Void> handleForbiddenRole(ForbiddenRoleGrantException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    @ExceptionHandler(NotJoinRequestOwnerException.class)
    ResponseEntity<Void> handleNotOwner(NotJoinRequestOwnerException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    @ExceptionHandler(MissingPrincipalIdentityException.class)
    ResponseEntity<Void> handleMissingIdentity(MissingPrincipalIdentityException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }

    @ExceptionHandler(SubmitThrottledException.class)
    ResponseEntity<Void> handleThrottled(SubmitThrottledException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()))
                .build();
    }

    @ExceptionHandler(UnsupportedJoinRequestStatusFilterException.class)
    ResponseEntity<Void> handleBadStatusFilter(UnsupportedJoinRequestStatusFilterException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Void> handleDataIntegrity(DataIntegrityViolationException e) {
        String message = String.valueOf(e.getMostSpecificCause().getMessage());
        if (message.contains("ux_join_request_alive")) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        throw e;
    }
}
