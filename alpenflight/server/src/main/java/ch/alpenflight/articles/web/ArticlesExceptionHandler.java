package ch.alpenflight.articles.web;

import ch.alpenflight.articles.domain.ArticleNotFoundException;
import ch.alpenflight.articles.domain.DuplicateArticleNumberException;
import ch.alpenflight.platform.web.ProblemResponses;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
        return ProblemResponses.problem(pd);
    }
}
