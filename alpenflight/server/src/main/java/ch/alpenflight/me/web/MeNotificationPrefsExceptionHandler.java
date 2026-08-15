package ch.alpenflight.me.web;

import ch.alpenflight.persons.domain.PersonNotFoundException;
import ch.alpenflight.platform.web.ProblemResponses;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = MeNotificationPrefsController.class)
class MeNotificationPrefsExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> handleBadRequest(IllegalArgumentException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setType(URI.create("urn:alpenflight:problem:bad-request"));
        pd.setTitle("Invalid request");
        pd.setDetail(e.getMessage());
        return problem(pd);
    }

    @ExceptionHandler({NoLinkedPersonException.class, PersonNotFoundException.class})
    ResponseEntity<ProblemDetail> handleNoMembership(RuntimeException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setType(URI.create("urn:alpenflight:problem:no-club-membership"));
        pd.setTitle("No club membership");
        pd.setDetail(e.getMessage());
        return problem(pd);
    }

    private static ResponseEntity<ProblemDetail> problem(ProblemDetail pd) {
        return ProblemResponses.problem(pd);
    }
}
