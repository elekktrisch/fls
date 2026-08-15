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
