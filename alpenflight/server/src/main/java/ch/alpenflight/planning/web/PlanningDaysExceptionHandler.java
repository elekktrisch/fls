package ch.alpenflight.planning.web;

import ch.alpenflight.planning.domain.InvalidPlanningDateException;
import ch.alpenflight.planning.domain.PlanningDayConflictException;
import ch.alpenflight.planning.domain.PlanningDayNotFoundException;
import ch.alpenflight.planning.domain.PlanningRuleRangeException;
import ch.alpenflight.platform.web.ProblemResponses;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
        return ProblemResponses.badRequest(e);
    }

    private static ResponseEntity<ProblemDetail> problem(ProblemDetail pd) {
        return ProblemResponses.problem(pd);
    }
}
