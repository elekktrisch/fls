package ch.alpenflight.planning.web;

import ch.alpenflight.planning.domain.InvalidPlanningDateException;
import ch.alpenflight.planning.domain.PlanningDayConflictException;
import ch.alpenflight.planning.domain.PlanningDayNotFoundException;
import ch.alpenflight.planning.domain.PlanningRuleRangeException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates {@code PlanningDay} domain exceptions to RFC 7807 problem
 * responses (J-6 T-04). Holds the only Spring-web coupling of the planning error
 * vocabulary — the domain exception types stay free of {@code @ResponseStatus}
 * (ADR 0023). Mirrors {@code AircraftReservationsExceptionHandler}.
 *
 * <ul>
 *   <li>{@link PlanningDayConflictException} → <strong>409</strong>
 *       (key {@code planning.day.duplicate}) — duplicate club+date+location.</li>
 *   <li>{@link InvalidPlanningDateException} → <strong>422</strong>
 *       (key {@code planning.day.date}) — planning date outside the sane range.</li>
 *   <li>{@link PlanningRuleRangeException} → <strong>422</strong>
 *       (key {@code planning.rule.range}) — bulk rule range over the span cap.</li>
 *   <li>{@link PlanningDayNotFoundException} → 404 (also the cross-tenant case).</li>
 *   <li>{@link IllegalArgumentException} → 400 (missing/invalid factory args,
 *       a crew role with no seeded club type).</li>
 * </ul>
 *
 * <p>The admin-or-creator gate throws {@code AccessDeniedException} from the
 * service; that is intentionally NOT handled here — Spring Security's
 * {@code ExceptionTranslationFilter} maps it to 403 downstream of MVC.
 */
@RestControllerAdvice(assignableTypes = PlanningDaysController.class)
class PlanningDaysExceptionHandler {

    private static final URI TYPE_NOT_FOUND =
            URI.create("urn:alpenflight:problem:planning-day-not-found");
    private static final URI TYPE_DUPLICATE =
            URI.create("urn:alpenflight:problem:planning-day-duplicate");
    private static final URI TYPE_DATE =
            URI.create("urn:alpenflight:problem:planning-day-date");
    private static final URI TYPE_RULE_RANGE =
            URI.create("urn:alpenflight:problem:planning-rule-range");

    @ExceptionHandler(PlanningDayNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(PlanningDayNotFoundException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setType(TYPE_NOT_FOUND);
        pd.setTitle("Planning day not found");
        pd.setDetail(e.getMessage());
        return problem(pd);
    }

    @ExceptionHandler(PlanningDayConflictException.class)
    ResponseEntity<ProblemDetail> handleConflict(PlanningDayConflictException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setType(TYPE_DUPLICATE);
        pd.setTitle("Duplicate planning day");
        pd.setDetail(e.getMessage());
        pd.setProperty("key", "planning.day.duplicate");
        return problem(pd);
    }

    @ExceptionHandler(InvalidPlanningDateException.class)
    ResponseEntity<ProblemDetail> handleInvalidDate(InvalidPlanningDateException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        pd.setType(TYPE_DATE);
        pd.setTitle("Invalid planning date");
        pd.setDetail(e.getMessage());
        pd.setProperty("key", "planning.day.date");
        return problem(pd);
    }

    @ExceptionHandler(PlanningRuleRangeException.class)
    ResponseEntity<ProblemDetail> handleRuleRange(PlanningRuleRangeException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        pd.setType(TYPE_RULE_RANGE);
        pd.setTitle("Planning rule range too large");
        pd.setDetail(e.getMessage());
        pd.setProperty("key", "planning.rule.range");
        return problem(pd);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setType(URI.create("urn:alpenflight:problem:bad-request"));
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
