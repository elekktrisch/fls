package ch.alpenflight.accounting.web;

import ch.alpenflight.accounting.application.DeliveryCreationTestNotFoundException;
import ch.alpenflight.accounting.domain.InvalidDeliveryCreationTestException;
import ch.alpenflight.platform.web.ProblemResponses;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates DeliveryCreationTest exceptions to RFC 7807 problem responses,
 * scoped to {@link DeliveryCreationTestsController}.
 *
 * <ul>
 *   <li>{@link DeliveryCreationTestNotFoundException} (application) → {@code 404}
 *       — also the cross-tenant case: the {@code @TenantId} filter makes another
 *       club's row invisible, so a cross-tenant id is a uniform 404, never a 403
 *       that would confirm the row exists.</li>
 *   <li>{@link InvalidDeliveryCreationTestException} (domain) → {@code 400} — the
 *       aggregate's invariants (testName non-blank, flightId present, length cap).
 *       Bean-validation ({@code @NotBlank}/{@code @NotNull}/{@code @Size}) catches
 *       these at the boundary in normal flow; this is the defence-in-depth net for
 *       a bypassed validator.</li>
 * </ul>
 *
 * <p>The exception types stay free of {@code @ResponseStatus} (ADR 0023) — the
 * web-layer coupling lives only here.
 */
@RestControllerAdvice(assignableTypes = DeliveryCreationTestsController.class)
class DeliveryCreationTestsExceptionHandler {

    private static final URI TYPE_NOT_FOUND =
            URI.create("urn:alpenflight:problem:delivery-creation-test-not-found");
    private static final URI TYPE_INVALID =
            URI.create("urn:alpenflight:problem:delivery-creation-test-invalid");

    @ExceptionHandler(DeliveryCreationTestNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(DeliveryCreationTestNotFoundException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setType(TYPE_NOT_FOUND);
        pd.setTitle("DeliveryCreationTest not found");
        pd.setDetail(e.getMessage());
        return ProblemResponses.problem(pd);
    }

    @ExceptionHandler(InvalidDeliveryCreationTestException.class)
    ResponseEntity<ProblemDetail> handleInvalid(InvalidDeliveryCreationTestException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setType(TYPE_INVALID);
        pd.setTitle("Invalid DeliveryCreationTest");
        pd.setDetail(e.getMessage());
        return ProblemResponses.problem(pd);
    }
}
