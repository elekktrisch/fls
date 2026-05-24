package ch.alpenflight.flights.web;

import ch.alpenflight.flights.domain.DuplicateCrewMemberException;
import ch.alpenflight.flights.domain.FlightNotFoundException;
import ch.alpenflight.flights.domain.InvalidFlightReferenceException;
import ch.alpenflight.flights.domain.InvalidTowLinkException;
import java.net.URI;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates Flight domain exceptions to RFC 7807 problem responses.
 * Scoped to {@link FlightsController}.
 */
@RestControllerAdvice(assignableTypes = FlightsController.class)
class FlightsExceptionHandler {

    private static final URI FLIGHT_NOT_FOUND =
            URI.create("urn:alpenflight:problem:flight-not-found");
    private static final URI INVALID_REFERENCE =
            URI.create("urn:alpenflight:problem:flight-invalid-reference");
    private static final URI INVALID_TOW_LINK =
            URI.create("urn:alpenflight:problem:flight-invalid-tow-link");
    private static final URI DUPLICATE_CREW =
            URI.create("urn:alpenflight:problem:flight-duplicate-crew");
    private static final URI INVALID_CURSOR =
            URI.create("urn:alpenflight:problem:flight-invalid-cursor");

    @ExceptionHandler(FlightNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(FlightNotFoundException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setType(FLIGHT_NOT_FOUND);
        pd.setTitle("Flight not found");
        pd.setDetail(e.getMessage());
        return problem(pd);
    }

    @ExceptionHandler(InvalidFlightReferenceException.class)
    ResponseEntity<ProblemDetail> handleInvalidReference(InvalidFlightReferenceException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setType(INVALID_REFERENCE);
        pd.setTitle("Referenced row not found");
        pd.setDetail(e.getMessage());
        return problem(pd);
    }

    @ExceptionHandler(InvalidTowLinkException.class)
    ResponseEntity<ProblemDetail> handleInvalidTowLink(InvalidTowLinkException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setType(INVALID_TOW_LINK);
        pd.setTitle("Invalid tow-flight link");
        pd.setDetail(e.getMessage());
        return problem(pd);
    }

    @ExceptionHandler(DuplicateCrewMemberException.class)
    ResponseEntity<ProblemDetail> handleDuplicateCrew(DuplicateCrewMemberException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setType(DUPLICATE_CREW);
        pd.setTitle("Duplicate crew member");
        pd.setDetail(e.getMessage());
        return problem(pd);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException e) {
        // Unknown FK references (unknown aircraftId, unknown flightTypeId, …)
        // surface as DB integrity violations. Map to 400 — the client supplied
        // a syntactically valid id that doesn't resolve. We don't echo the SQL
        // detail to avoid leaking schema names.
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setType(INVALID_REFERENCE);
        pd.setTitle("Invalid reference");
        pd.setDetail("One or more referenced rows do not exist or are not valid.");
        return problem(pd);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException e) {
        // The aggregate throws IllegalArgumentException for runway / coupon /
        // temporal-ordering / non-negative invariants. The DTO validator
        // catches most before this fires, but the aggregate is the
        // authoritative source so the handler maps to 400 here too.
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setType(INVALID_CURSOR);
        pd.setTitle("Invalid request");
        pd.setDetail(e.getMessage());
        return problem(pd);
    }

    private static ResponseEntity<ProblemDetail> problem(ProblemDetail pd) {
        return ResponseEntity.status(pd.getStatus())
                .header("Content-Type", "application/problem+json")
                .body(pd);
    }
}
