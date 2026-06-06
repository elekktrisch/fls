package ch.alpenflight.flighttypes.web;

import ch.alpenflight.flighttypes.domain.DuplicateFlightTypeNameException;
import ch.alpenflight.flighttypes.domain.FlightCostBalanceTypeInvariantException;
import ch.alpenflight.flighttypes.domain.FlightTypeNotFoundException;
import ch.alpenflight.platform.web.ProblemResponses;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates FlightType / FlightCostBalanceType domain exceptions to RFC
 * 7807 problem responses. Scoped to the two controllers in this module so
 * a sibling module's NotFound / IllegalArgument keeps its own handler.
 *
 * <p>{@link IllegalArgumentException} is intentionally NOT mapped here:
 * Spring's default handler returns 500, which is correct for "constructor
 * rejected a bad value the DTO validator should have caught" — that's a
 * coding bug, not a client error. Aggregate-thrown validation hits this
 * handler only when the DTO validator was bypassed (direct service call
 * from a test, etc.).
 */
@RestControllerAdvice(assignableTypes = {
        FlightTypesController.class,
        FlightCostBalanceTypesController.class
})
class FlightTypesExceptionHandler {

    private static final URI TYPE_NOT_FOUND =
            URI.create("urn:alpenflight:problem:flight-type-not-found");
    private static final URI TYPE_NAME_CONFLICT =
            URI.create("urn:alpenflight:problem:flight-type-name-conflict");
    private static final URI TYPE_FCBT_INVARIANT =
            URI.create("urn:alpenflight:problem:flight-cost-balance-type-invariant");

    @ExceptionHandler(FlightTypeNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(FlightTypeNotFoundException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setType(TYPE_NOT_FOUND);
        pd.setTitle("FlightType not found");
        pd.setDetail(e.getMessage());
        return problem(pd);
    }

    @ExceptionHandler(DuplicateFlightTypeNameException.class)
    ResponseEntity<ProblemDetail> handleDuplicateName(DuplicateFlightTypeNameException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setType(TYPE_NAME_CONFLICT);
        pd.setTitle("FlightType name already in use");
        pd.setDetail(e.getMessage());
        pd.setProperty("field", "flightTypeName");
        return problem(pd);
    }

    @ExceptionHandler(FlightCostBalanceTypeInvariantException.class)
    ResponseEntity<ProblemDetail> handleFcbtInvariant(FlightCostBalanceTypeInvariantException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setType(TYPE_FCBT_INVARIANT);
        pd.setTitle("FlightCostBalanceType invariant violation");
        pd.setDetail(e.getMessage());
        return problem(pd);
    }

    private static ResponseEntity<ProblemDetail> problem(ProblemDetail pd) {
        return ProblemResponses.problem(pd);
    }
}
