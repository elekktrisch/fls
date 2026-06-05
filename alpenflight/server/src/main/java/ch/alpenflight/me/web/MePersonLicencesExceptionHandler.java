package ch.alpenflight.me.web;

import ch.alpenflight.persons.domain.PersonNotFoundException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * HTTP mapping for {@link MePersonLicencesController}.
 *
 * <ul>
 *   <li>{@link IllegalArgumentException} → 400. The Person aggregate's
 *       {@code updateLicences} throws it for an over-length licence number;
 *       without this advice it would surface as 500. Bean-validation failures
 *       ({@code @Valid} on the request DTO) are already 400 via Spring's
 *       default handler.</li>
 *   <li>{@link NoLinkedPersonException} / {@link PersonNotFoundException} →
 *       409. The caller is authenticated but has no linked Person record (or
 *       its linkage resolved to nothing) — a clean conflict, not a 500.</li>
 * </ul>
 */
@RestControllerAdvice(assignableTypes = MePersonLicencesController.class)
class MePersonLicencesExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> handleBadRequest(IllegalArgumentException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setType(URI.create("urn:alpenflight:problem:bad-request"));
        pd.setTitle("Invalid request");
        pd.setDetail(e.getMessage());
        return problem(pd);
    }

    @ExceptionHandler({NoLinkedPersonException.class, PersonNotFoundException.class})
    ResponseEntity<ProblemDetail> handleNoLinkedPerson(RuntimeException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setType(URI.create("urn:alpenflight:problem:no-linked-person"));
        pd.setTitle("No linked Person record");
        pd.setDetail(e.getMessage());
        return problem(pd);
    }

    private static ResponseEntity<ProblemDetail> problem(ProblemDetail pd) {
        return ResponseEntity.status(pd.getStatus())
                .header("Content-Type", "application/problem+json")
                .body(pd);
    }
}
