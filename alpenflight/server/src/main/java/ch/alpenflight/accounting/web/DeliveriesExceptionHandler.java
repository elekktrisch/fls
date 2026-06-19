package ch.alpenflight.accounting.web;

import ch.alpenflight.accounting.application.DeliveryNotFoundException;
import ch.alpenflight.platform.web.ProblemResponses;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates Delivery read-surface exceptions to RFC 7807 problem responses,
 * scoped to {@link DeliveriesController}.
 *
 * <p>{@link DeliveryNotFoundException} → {@code 404} — also the cross-tenant case:
 * the {@code @TenantId} filter makes another club's row invisible, so a
 * cross-tenant id is a uniform 404, never a 403 that would confirm the row exists.
 * The exception type stays free of {@code @ResponseStatus} (ADR 0023) — the
 * web-layer coupling lives only here.
 */
@RestControllerAdvice(assignableTypes = DeliveriesController.class)
class DeliveriesExceptionHandler {

    private static final URI TYPE_NOT_FOUND =
            URI.create("urn:alpenflight:problem:delivery-not-found");

    @ExceptionHandler(DeliveryNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(DeliveryNotFoundException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setType(TYPE_NOT_FOUND);
        pd.setTitle("Delivery not found");
        pd.setDetail(e.getMessage());
        return ProblemResponses.problem(pd);
    }
}
