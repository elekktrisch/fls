package ch.alpenflight.articles.web;

import ch.alpenflight.articles.domain.ArticleNotFoundException;
import ch.alpenflight.articles.domain.DuplicateArticleNumberException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates Article domain exceptions to RFC 7807 problem responses. Scoped
 * to {@link ArticlesController} so a sibling module's NotFound / IllegalArgument
 * keeps its own handler.
 *
 * <p>{@link IllegalArgumentException} is intentionally NOT mapped here: the
 * default 500 is correct for "aggregate constructor rejected a bad value the
 * DTO validator should have caught" — a coding bug, not a client error.
 */
@RestControllerAdvice(assignableTypes = ArticlesController.class)
class ArticlesExceptionHandler {

    private static final URI TYPE_NOT_FOUND =
            URI.create("urn:alpenflight:problem:article-not-found");
    private static final URI TYPE_NUMBER_CONFLICT =
            URI.create("urn:alpenflight:problem:article-number-conflict");

    @ExceptionHandler(ArticleNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(ArticleNotFoundException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setType(TYPE_NOT_FOUND);
        pd.setTitle("Article not found");
        pd.setDetail(e.getMessage());
        return problem(pd);
    }

    @ExceptionHandler(DuplicateArticleNumberException.class)
    ResponseEntity<ProblemDetail> handleDuplicateNumber(DuplicateArticleNumberException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setType(TYPE_NUMBER_CONFLICT);
        pd.setTitle("Article number already in use");
        pd.setDetail(e.getMessage());
        pd.setProperty("field", "articleNumber");
        return problem(pd);
    }

    private static ResponseEntity<ProblemDetail> problem(ProblemDetail pd) {
        return ResponseEntity.status(pd.getStatus())
                .header("Content-Type", "application/problem+json")
                .body(pd);
    }
}
