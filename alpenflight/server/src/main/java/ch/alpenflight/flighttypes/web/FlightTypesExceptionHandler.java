package ch.alpenflight.flighttypes.web;

import ch.alpenflight.flighttypes.domain.DuplicateFlightTypeCodeException;
import ch.alpenflight.flighttypes.domain.DuplicateFlightTypeNameException;
import ch.alpenflight.flighttypes.domain.FlightCostBalanceTypeInvariantException;
import ch.alpenflight.flighttypes.domain.FlightTypeNotFoundException;
import ch.alpenflight.flighttypes.domain.InstructorObserverExclusionException;
import ch.alpenflight.platform.web.ProblemResponses;
import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
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
    private static final URI TYPE_CODE_CONFLICT =
            URI.create("urn:alpenflight:problem:flight-type-code-conflict");
    private static final URI TYPE_FCBT_INVARIANT =
            URI.create("urn:alpenflight:problem:flight-cost-balance-type-invariant");
    private static final URI TYPE_INSTRUCTOR_OBSERVER_EXCLUSION =
            URI.create("urn:alpenflight:problem:flight-type-instructor-observer-exclusion");
    private static final String CODE_UNIQUE_CONSTRAINT = "ux_flight_type_club_code";

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

    @ExceptionHandler(DuplicateFlightTypeCodeException.class)
    ResponseEntity<ProblemDetail> handleDuplicateCode(DuplicateFlightTypeCodeException e) {
        return problem(codeConflict(e.getMessage()));
    }

    /**
     * Race net for the {@code findActiveByCode} pre-check in
     * {@code FlightTypesService}: a concurrent insert can still trip the V3
     * partial UNIQUE {@code ux_flight_type_club_code} — translate that to the
     * same 409 {@code field=flightCode} envelope instead of a raw 500 (the
     * legacy-reproducing bug J-26 T-05 fixes). Other integrity violations
     * propagate as 500 (genuine server bugs). Mirrors
     * {@code LocationsExceptionHandler#handleDataIntegrity}.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException e) {
        String message = String.valueOf(e.getMostSpecificCause().getMessage());
        if (message.contains(CODE_UNIQUE_CONSTRAINT)) {
            return problem(codeConflict("FlightType code already in use."));
        }
        throw e;
    }

    /**
     * The aggregate's instructor×observer mutual-exclusion guard
     * ({@code FlightType.updateFlags}, J-26 T-06) → 400 with the same
     * problem-detail {@code field} envelope the duplicate 409s use. The
     * client cross-field validator blocks this before submit; a raw API
     * caller (or a bypassed client) gets the clean 400 instead of a 500.
     */
    @ExceptionHandler(InstructorObserverExclusionException.class)
    ResponseEntity<ProblemDetail> handleInstructorObserverExclusion(
            InstructorObserverExclusionException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setType(TYPE_INSTRUCTOR_OBSERVER_EXCLUSION);
        pd.setTitle("Instructor and observer requirements are mutually exclusive");
        pd.setDetail(e.getMessage());
        pd.setProperty("field", "isObserverPilotOrInstructorRequired");
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

    private static ProblemDetail codeConflict(@Nullable String detail) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setType(TYPE_CODE_CONFLICT);
        pd.setTitle("FlightType code already in use");
        pd.setDetail(detail);
        pd.setProperty("field", "flightCode");
        return pd;
    }

    private static ResponseEntity<ProblemDetail> problem(ProblemDetail pd) {
        return ProblemResponses.problem(pd);
    }
}
