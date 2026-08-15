package ch.alpenflight.emailtemplates.web;

import ch.alpenflight.emailtemplates.domain.EmailTemplateOverrideNotFoundException;
import ch.alpenflight.platform.web.ProblemResponses;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = EmailTemplatesController.class)
class EmailTemplatesExceptionHandler {

    private static final URI TYPE_OVERRIDE_NOT_FOUND =
            URI.create("urn:alpenflight:problem:email-template-override-not-found");

    @ExceptionHandler(EmailTemplateOverrideNotFoundException.class)
    ResponseEntity<ProblemDetail> handleOverrideNotFound(EmailTemplateOverrideNotFoundException e) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setType(TYPE_OVERRIDE_NOT_FOUND);
        pd.setTitle("Email-template override not found");
        pd.setDetail(e.getMessage());
        return ProblemResponses.problem(pd);
    }
}
