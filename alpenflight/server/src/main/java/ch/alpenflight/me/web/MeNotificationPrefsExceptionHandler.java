package ch.alpenflight.me.web;

import ch.alpenflight.persons.domain.PersonNotFoundException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * HTTP mapping for {@link MeNotificationPrefsController}.
 *
 * <ul>
 *   <li>{@link IllegalArgumentException} → 400. The aggregate's
 *       {@code updateNotificationPrefs} throws it for a null prefs object;
 *       without this advice it would surface as 500. Bean-validation failures
 *       ({@code @Valid} on the request DTO) are already 400 via Spring's default
 *       handler.</li>
 *   <li>{@link NoLinkedPersonException} / {@link PersonNotFoundException} → 409.
 *       The caller is authenticated but has no linked Person, or no alive
 *       membership in the current club — a clean conflict (the no-membership
 *       banner case), not a 500.</li>
 * </ul>
 */
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
        return ResponseEntity.status(pd.getStatus())
                .header("Content-Type", "application/problem+json")
                .body(pd);
    }
}
