package ch.alpenflight.accounting.web;

import ch.alpenflight.accounting.application.DeliveryNotFoundException;
import ch.alpenflight.accounting.domain.DeliveryBookedTerminalException;
import ch.alpenflight.accounting.domain.DeliveryDeletionConflictException;
import ch.alpenflight.platform.web.ProblemResponses;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = DeliveriesController.class)
class DeliveriesExceptionHandler {

    private static final URI TYPE_NOT_FOUND =
            URI.create("urn:alpenflight:problem:delivery-not-found");
    private static final URI TYPE_DELETE_CONFLICT =
            URI.create("urn:alpenflight:problem:delivery-delete-conflict");
    private static final URI TYPE_BOOKED_TERMINAL =
            URI.create("urn:alpenflight:problem:delivery-booked-terminal");

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

    @ExceptionHandler(DeliveryBookedTerminalException.class)
    ResponseEntity<ProblemDetail> handleBookedTerminal(DeliveryBookedTerminalException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setType(TYPE_BOOKED_TERMINAL);
        pd.setTitle("Delivery is booked");
        pd.setDetail(e.getMessage());
        pd.setProperty("key", "delivery.booked.terminal");
        return ProblemResponses.problem(pd);
    }
}
