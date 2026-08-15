package ch.alpenflight.accounting.web;

import ch.alpenflight.accounting.application.DeliveryCreationTestNotFoundException;
import ch.alpenflight.accounting.domain.InvalidDeliveryCreationTestException;
import ch.alpenflight.flights.domain.FlightNotFoundException;
import ch.alpenflight.platform.web.ProblemResponses;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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

    @ExceptionHandler(FlightNotFoundException.class)
    ResponseEntity<ProblemDetail> handleFlightNotFound(FlightNotFoundException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setType(TYPE_NOT_FOUND);
        pd.setTitle("Flight not found");
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
