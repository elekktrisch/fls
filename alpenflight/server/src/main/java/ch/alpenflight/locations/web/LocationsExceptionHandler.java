package ch.alpenflight.locations.web;

import ch.alpenflight.locations.domain.IcaoCodeAlreadyExistsException;
import ch.alpenflight.locations.domain.IcaoCodeInvalidException;
import ch.alpenflight.locations.domain.InvalidLocationReferenceException;
import ch.alpenflight.locations.domain.LocationNotFoundException;
import java.net.URI;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates Locations domain exceptions to RFC 7807 problem responses. Holds
 * the only Spring-web coupling of the Locations error vocabulary — the
 * exception types themselves stay in {@code locations.domain} free of
 * {@code @ResponseStatus} per ADR 0023.
 *
 * <p>{@code assignableTypes} pins the advice to {@link LocationsController}
 * so a future module that throws the same exception type by mistake does
 * not inherit Locations' HTTP status mapping. Module-local error vocabulary,
 * module-local advice.
 */
@RestControllerAdvice(assignableTypes = {LocationsController.class})
class LocationsExceptionHandler {

    private static final URI TYPE_NOT_FOUND = URI.create("urn:alpenflight:problem:location-not-found");
    private static final URI TYPE_CLUB_NOT_FOUND = URI.create("urn:alpenflight:problem:club-not-found");
    private static final URI TYPE_ICAO_INVALID = URI.create("urn:alpenflight:problem:icao-invalid");
    private static final URI TYPE_ICAO_CONFLICT = URI.create("urn:alpenflight:problem:icao-conflict");
    private static final URI TYPE_INVALID_REF = URI.create("urn:alpenflight:problem:invalid-reference");
    private static final String FK_LOCATION_CLUB_ID = "fk_location_club_id";

    @ExceptionHandler(LocationNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(LocationNotFoundException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setType(TYPE_NOT_FOUND);
        pd.setTitle("Location not found");
        pd.setDetail(e.getMessage());
        return problem(pd);
    }

    @ExceptionHandler(IcaoCodeInvalidException.class)
    ResponseEntity<ProblemDetail> handleIcaoInvalid(IcaoCodeInvalidException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setType(TYPE_ICAO_INVALID);
        pd.setTitle("Invalid ICAO code");
        pd.setDetail(e.getMessage());
        pd.setProperty("field", "icaoCode");
        return problem(pd);
    }

    @ExceptionHandler(IcaoCodeAlreadyExistsException.class)
    ResponseEntity<ProblemDetail> handleIcaoConflict(IcaoCodeAlreadyExistsException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setType(TYPE_ICAO_CONFLICT);
        pd.setTitle("ICAO code already in use");
        pd.setDetail(e.getMessage());
        pd.setProperty("field", "icaoCode");
        return problem(pd);
    }

    @ExceptionHandler(InvalidLocationReferenceException.class)
    ResponseEntity<ProblemDetail> handleInvalidReference(InvalidLocationReferenceException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setType(TYPE_INVALID_REF);
        pd.setTitle("Invalid reference");
        pd.setDetail(e.getMessage());
        pd.setProperty("field", e.getField());
        return problem(pd);
    }

    /**
     * Translate a foreign-key violation on {@code fk_location_club_id} (the
     * tenant FK) to a 404 — surfaces the rare case where the {@code @TenantId}
     * resolver returns a {@code club_id} that does not have a {@code club}
     * row (e.g. a stale JWT after a club was deleted). Other FK violations
     * propagate as 500 (genuine server bugs).
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException e) {
        String message = String.valueOf(e.getMostSpecificCause().getMessage());
        if (message.contains(FK_LOCATION_CLUB_ID)) {
            ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
            pd.setType(TYPE_CLUB_NOT_FOUND);
            pd.setTitle("Club not found");
            pd.setDetail("No active club with the given id.");
            return problem(pd);
        }
        throw e;
    }

    private static ResponseEntity<ProblemDetail> problem(ProblemDetail pd) {
        return ResponseEntity.status(pd.getStatus())
                .header("Content-Type", "application/problem+json")
                .body(pd);
    }
}
