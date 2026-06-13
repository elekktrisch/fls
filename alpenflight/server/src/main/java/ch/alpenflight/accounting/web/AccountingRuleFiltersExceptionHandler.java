package ch.alpenflight.accounting.web;

import ch.alpenflight.accounting.application.AccountingRuleFilterNotFoundException;
import ch.alpenflight.accounting.domain.InvalidAccountingRuleFilterException;
import ch.alpenflight.platform.web.ProblemResponses;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates AccountingRuleFilter exceptions to RFC 7807 problem responses,
 * scoped to {@link AccountingRuleFiltersController} so a sibling module's
 * NotFound / Invalid keeps its own handler.
 *
 * <ul>
 *   <li>{@link AccountingRuleFilterNotFoundException} (application) → {@code 404}
 *       — also the cross-tenant case: the {@code @TenantId} filter makes another
 *       club's row invisible, so a cross-tenant id is a uniform 404, never a 403
 *       (the legacy tenant-leak fix, oracle).</li>
 *   <li>{@link InvalidAccountingRuleFilterException} (domain) → {@code 400} —
 *       the aggregate's invariants (name non-blank, filter-type present, length
 *       caps). The DTO bean-validation ({@code @NotBlank}/{@code @NotNull}/
 *       {@code @Size}) catches these at the controller boundary in normal flow;
 *       this handler is the defence-in-depth net for a bypassed validator (a
 *       direct service call, a future endpoint), mirroring
 *       {@code FlightTypesExceptionHandler}.</li>
 * </ul>
 *
 * <p>The exception types themselves stay free of {@code @ResponseStatus}
 * (ADR 0023) — the web-layer coupling lives only here.
 */
@RestControllerAdvice(assignableTypes = AccountingRuleFiltersController.class)
class AccountingRuleFiltersExceptionHandler {

    private static final URI TYPE_NOT_FOUND =
            URI.create("urn:alpenflight:problem:accounting-rule-filter-not-found");
    private static final URI TYPE_INVALID =
            URI.create("urn:alpenflight:problem:accounting-rule-filter-invalid");

    @ExceptionHandler(AccountingRuleFilterNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(AccountingRuleFilterNotFoundException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setType(TYPE_NOT_FOUND);
        pd.setTitle("AccountingRuleFilter not found");
        pd.setDetail(e.getMessage());
        return ProblemResponses.problem(pd);
    }

    @ExceptionHandler(InvalidAccountingRuleFilterException.class)
    ResponseEntity<ProblemDetail> handleInvalid(InvalidAccountingRuleFilterException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setType(TYPE_INVALID);
        pd.setTitle("Invalid AccountingRuleFilter");
        pd.setDetail(e.getMessage());
        return ProblemResponses.problem(pd);
    }
}
