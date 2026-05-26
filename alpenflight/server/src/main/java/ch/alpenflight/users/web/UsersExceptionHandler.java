package ch.alpenflight.users.web;

import ch.alpenflight.users.application.ForbiddenRoleGrantException;
import ch.alpenflight.users.domain.UserConflictException;
import ch.alpenflight.users.domain.UserNotFoundException;
import ch.alpenflight.users.infra.keycloak.KeycloakAdminException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = UsersController.class)
class UsersExceptionHandler {

    private static final URI TYPE_NOT_FOUND =
            URI.create("urn:alpenflight:problem:user-not-found");
    private static final URI TYPE_CONFLICT =
            URI.create("urn:alpenflight:problem:user-conflict");
    private static final URI TYPE_FORBIDDEN_ROLE =
            URI.create("urn:alpenflight:problem:user-role-grant-rejected");
    private static final URI TYPE_KC_UPSTREAM =
            URI.create("urn:alpenflight:problem:user-keycloak-upstream");

    @ExceptionHandler(UserNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(UserNotFoundException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setType(TYPE_NOT_FOUND);
        pd.setTitle("User not found");
        pd.setDetail(e.getMessage());
        return problem(pd);
    }

    @ExceptionHandler(UserConflictException.class)
    ResponseEntity<ProblemDetail> handleConflict(UserConflictException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setType(TYPE_CONFLICT);
        pd.setTitle("User mutation refused");
        pd.setDetail(e.getMessage());
        return problem(pd);
    }

    @ExceptionHandler(ForbiddenRoleGrantException.class)
    ResponseEntity<ProblemDetail> handleForbidden(ForbiddenRoleGrantException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        pd.setType(TYPE_FORBIDDEN_ROLE);
        pd.setTitle("Role grant rejected");
        pd.setDetail("Caller is not permitted to assign roles: " + e.rejectedRoles());
        return problem(pd);
    }

    @ExceptionHandler(KeycloakAdminException.class)
    ResponseEntity<ProblemDetail> handleKc(KeycloakAdminException e) {
        // 502: the upstream IdP failed mid-flow. Don't bubble the cause text —
        // KC error payloads occasionally include the user's email.
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_GATEWAY);
        pd.setType(TYPE_KC_UPSTREAM);
        pd.setTitle("Keycloak admin call failed");
        pd.setDetail(e.getMessage());
        return problem(pd);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> handleBadRequest(IllegalArgumentException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setType(URI.create("urn:alpenflight:problem:bad-request"));
        pd.setTitle("Invalid request");
        pd.setDetail(e.getMessage());
        return problem(pd);
    }

    private static ResponseEntity<ProblemDetail> problem(ProblemDetail pd) {
        return ResponseEntity.status(pd.getStatus())
                .header("Content-Type", "application/problem+json")
                .body(pd);
    }
}
