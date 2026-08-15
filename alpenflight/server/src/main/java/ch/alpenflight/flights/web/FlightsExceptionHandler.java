package ch.alpenflight.flights.web;

import ch.alpenflight.flights.application.InvalidCursorException;
import ch.alpenflight.flights.domain.DuplicateCrewMemberException;
import ch.alpenflight.flights.domain.FlightGateNotReachedException;
import ch.alpenflight.flights.domain.FlightNotFoundException;
import ch.alpenflight.flights.domain.FlightStateGateException;
import ch.alpenflight.flights.domain.FlightVersionMismatchException;
import ch.alpenflight.flights.domain.IllegalFlightTransitionException;
import ch.alpenflight.flights.domain.InvalidTowLinkException;
import ch.alpenflight.platform.web.ProblemResponses;
import java.net.URI;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
    private static final URI INVALID_REQUEST =
            URI.create("urn:alpenflight:problem:flight-invalid-request");
    private static final URI ILLEGAL_TRANSITION =
            URI.create("urn:alpenflight:problem:flight-illegal-transition");
    private static final URI CONCURRENT_MODIFICATION =
            URI.create("urn:alpenflight:problem:flight-concurrent-modification");
    private static final URI STATE_GATE_TERMINAL =
            URI.create("urn:alpenflight:problem:flight-state-terminal");
    private static final URI STATE_GATE_ADMIN_REQUIRED =
            URI.create("urn:alpenflight:problem:flight-state-admin-required");
    private static final URI VERSION_MISMATCH =
            URI.create("urn:alpenflight:problem:flight-version-mismatch");
    private static final URI GATE_NOT_REACHED =
            URI.create("urn:alpenflight:problem:flight-gate-not-reached");

    @ExceptionHandler(FlightNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(FlightNotFoundException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setType(FLIGHT_NOT_FOUND);
        pd.setTitle("Flight not found");
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
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setType(INVALID_REFERENCE);
        pd.setTitle("Invalid reference");
        pd.setDetail("One or more referenced rows do not exist or are not valid.");
        return problem(pd);
    }

    @ExceptionHandler(InvalidCursorException.class)
    ResponseEntity<ProblemDetail> handleInvalidCursor(InvalidCursorException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setType(INVALID_CURSOR);
        pd.setTitle("Invalid cursor");
        pd.setDetail(e.getMessage());
        return problem(pd);
    }

    @ExceptionHandler(IllegalFlightTransitionException.class)
    ResponseEntity<ProblemDetail> handleIllegalTransition(IllegalFlightTransitionException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setType(ILLEGAL_TRANSITION);
        pd.setTitle("Illegal flight-state transition");
        pd.setDetail(e.getMessage());
        pd.setProperty("from", e.from().name());
        pd.setProperty("to", e.to().name());
        pd.setProperty("trigger", e.trigger().name());
        if (!e.allowed().isEmpty()) {
            pd.setProperty("allowed", e.allowed().stream().map(Enum::name).toList());
        }
        return problem(pd);
    }

    @ExceptionHandler(FlightGateNotReachedException.class)
    ResponseEntity<ProblemDetail> handleGateNotReached(FlightGateNotReachedException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setType(GATE_NOT_REACHED);
        pd.setTitle("Flight time-gate not yet reached");
        pd.setDetail(e.getMessage());
        pd.setProperty("gate", e.gate().name());
        return problem(pd);
    }

    @ExceptionHandler(FlightStateGateException.class)
    ResponseEntity<ProblemDetail> handleStateGate(FlightStateGateException e) {
        HttpStatus status = e.reason() == FlightStateGateException.Reason.TERMINAL
                ? HttpStatus.CONFLICT
                : HttpStatus.FORBIDDEN;
        URI type = e.reason() == FlightStateGateException.Reason.TERMINAL
                ? STATE_GATE_TERMINAL
                : STATE_GATE_ADMIN_REQUIRED;
        ProblemDetail pd = ProblemDetail.forStatus(status);
        pd.setType(type);
        pd.setTitle(e.reason() == FlightStateGateException.Reason.TERMINAL
                ? "Flight is in a terminal state"
                : "Mutation requires CLUB_ADMINISTRATOR");
        pd.setDetail(e.getMessage());
        pd.setProperty("state", e.state().name());
        return problem(pd);
    }

    @ExceptionHandler(FlightVersionMismatchException.class)
    ResponseEntity<ProblemDetail> handleVersionMismatch(FlightVersionMismatchException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.PRECONDITION_FAILED);
        pd.setType(VERSION_MISMATCH);
        pd.setTitle("If-Match version does not match");
        pd.setDetail(e.getMessage());
        pd.setProperty("expected", e.expected());
        pd.setProperty("serverVersion", e.actual());
        return problem(pd);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> handleOptimisticLock(ObjectOptimisticLockingFailureException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setType(CONCURRENT_MODIFICATION);
        pd.setTitle("Flight was modified concurrently");
        pd.setDetail("Flight was modified by another transaction; reload and retry.");
        return problem(pd);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> handleUnreadable(HttpMessageNotReadableException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setType(INVALID_REQUEST);
        pd.setTitle("Invalid request");
        pd.setDetail("Request body could not be parsed.");
        return problem(pd);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException e) {
        return ProblemResponses.badRequest(e);
    }

    private static ResponseEntity<ProblemDetail> problem(ProblemDetail pd) {
        return ProblemResponses.problem(pd);
    }
}
