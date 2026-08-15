package ch.alpenflight.persons.web;

import ch.alpenflight.persons.domain.CrossTenantMembershipBlockedException;
import ch.alpenflight.persons.domain.DuplicateClubMembershipException;
import ch.alpenflight.persons.domain.PersonNotFoundException;
import ch.alpenflight.platform.web.ProblemResponses;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {PersonsController.class, MemberStatesController.class})
class PersonsExceptionHandler {

    private static final URI TYPE_NOT_FOUND =
            URI.create("urn:alpenflight:problem:person-not-found");
    private static final URI TYPE_DUPLICATE_MEMBERSHIP =
            URI.create("urn:alpenflight:problem:person-club-duplicate-membership");
    private static final URI TYPE_CROSS_TENANT_BLOCKED =
            URI.create("urn:alpenflight:problem:person-cross-tenant-membership-blocked");

    @ExceptionHandler(PersonNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(PersonNotFoundException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setType(TYPE_NOT_FOUND);
        pd.setTitle("Person not found");
        pd.setDetail(e.getMessage());
        return problem(pd);
    }

    @ExceptionHandler(DuplicateClubMembershipException.class)
    ResponseEntity<ProblemDetail> handleDuplicateMembership(DuplicateClubMembershipException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setType(TYPE_DUPLICATE_MEMBERSHIP);
        pd.setTitle("Person already has an active membership in this club");
        pd.setDetail(e.getMessage());
        return problem(pd);
    }

    @ExceptionHandler(CrossTenantMembershipBlockedException.class)
    ResponseEntity<ProblemDetail> handleCrossTenantBlocked(CrossTenantMembershipBlockedException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setType(TYPE_CROSS_TENANT_BLOCKED);
        pd.setTitle("Person has active memberships in other tenants");
        pd.setDetail(e.getMessage());
        return problem(pd);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException e) {
        return ProblemResponses.badRequest(e);
    }

    private static ResponseEntity<ProblemDetail> problem(ProblemDetail pd) {
        return ProblemResponses.problem(pd);
    }
}
