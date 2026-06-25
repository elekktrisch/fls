package ch.alpenflight.accounting.web;

import ch.alpenflight.accounting.application.DeliveryNotFoundException;
import ch.alpenflight.accounting.domain.DeliveryDeletionConflictException;
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
 * {@link DeliveryDeletionConflictException} → {@code 409} — the {@code >1-delivery-
 * per-flight} delete guard (legacy's unmapped 500 modernized to a clean conflict).
 * The exception types stay free of {@code @ResponseStatus} (ADR 0023) — the
 * web-layer coupling lives only here.
 */
@RestControllerAdvice(assignableTypes = DeliveriesController.class)
class DeliveriesExceptionHandler {

    private static final URI TYPE_NOT_FOUND =
            URI.create("urn:alpenflight:problem:delivery-not-found");
    private static final URI TYPE_DELETE_CONFLICT =
            URI.create("urn:alpenflight:problem:delivery-delete-conflict");

    @ExceptionHandler(DeliveryNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(DeliveryNotFoundException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setType(TYPE_NOT_FOUND);
        pd.setTitle("Delivery not found");
        pd.setDetail(e.getMessage());
        return ProblemResponses.problem(pd);
    }

    @ExceptionHandler(DeliveryDeletionConflictException.class)
    ResponseEntity<ProblemDetail> handleDeleteConflict(DeliveryDeletionConflictException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setType(TYPE_DELETE_CONFLICT);
        pd.setTitle("Delivery cannot be deleted");
        pd.setDetail(e.getMessage());
        pd.setProperty("key", "delivery.delete.shared-flight");
        return ProblemResponses.problem(pd);
    }
}
