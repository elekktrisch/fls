package ch.alpenflight.aircraft.web;

import ch.alpenflight.aircraft.domain.AircraftNotFoundException;
import ch.alpenflight.aircraft.domain.AircraftStateConflictException;
import ch.alpenflight.aircraft.domain.CounterMonotonicityException;
import ch.alpenflight.aircraft.domain.DuplicateImmatriculationException;
import ch.alpenflight.aircraft.domain.InvalidAircraftReferenceException;
import ch.alpenflight.platform.web.ProblemResponses;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates Aircraft domain exceptions to RFC 7807 problem responses.
 * Holds the only Spring-web coupling of the Aircraft error vocabulary —
 * the exception types themselves stay in {@code aircraft.domain} free of
 * {@code @ResponseStatus} per ADR 0023.
 */
@RestControllerAdvice(assignableTypes = AircraftsController.class)
class AircraftsExceptionHandler {

    private static final URI TYPE_NOT_FOUND =
            URI.create("urn:alpenflight:problem:aircraft-not-found");
    private static final URI TYPE_IMMAT_CONFLICT =
            URI.create("urn:alpenflight:problem:aircraft-immatriculation-conflict");
    private static final URI TYPE_INVALID_REF =
            URI.create("urn:alpenflight:problem:aircraft-invalid-reference");
    private static final URI TYPE_STATE_CONFLICT =
            URI.create("urn:alpenflight:problem:aircraft-state-conflict");
    private static final URI TYPE_COUNTER_CONFLICT =
            URI.create("urn:alpenflight:problem:aircraft-counter-conflict");

    @ExceptionHandler(AircraftNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(AircraftNotFoundException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setType(TYPE_NOT_FOUND);
        pd.setTitle("Aircraft not found");
        pd.setDetail(e.getMessage());
        return problem(pd);
    }

    @ExceptionHandler(DuplicateImmatriculationException.class)
    ResponseEntity<ProblemDetail> handleDuplicateImmat(DuplicateImmatriculationException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setType(TYPE_IMMAT_CONFLICT);
        pd.setTitle("Aircraft immatriculation already in use");
        pd.setDetail(e.getMessage());
        pd.setProperty("field", "immatriculation");
        return problem(pd);
    }

    @ExceptionHandler(InvalidAircraftReferenceException.class)
    ResponseEntity<ProblemDetail> handleInvalidReference(InvalidAircraftReferenceException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setType(TYPE_INVALID_REF);
        pd.setTitle("Invalid reference");
        pd.setDetail(e.getMessage());
        pd.setProperty("field", e.getField());
        return problem(pd);
    }

    @ExceptionHandler(AircraftStateConflictException.class)
    ResponseEntity<ProblemDetail> handleStateConflict(AircraftStateConflictException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setType(TYPE_STATE_CONFLICT);
        pd.setTitle("Aircraft state conflict");
        pd.setDetail(e.getMessage());
        return problem(pd);
    }

    @ExceptionHandler(CounterMonotonicityException.class)
    ResponseEntity<ProblemDetail> handleCounterConflict(CounterMonotonicityException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setType(TYPE_COUNTER_CONFLICT);
        pd.setTitle("Aircraft counter conflict");
        pd.setDetail(e.getMessage());
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
