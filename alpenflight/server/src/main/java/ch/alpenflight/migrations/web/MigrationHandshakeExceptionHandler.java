package ch.alpenflight.migrations.web;

import ch.alpenflight.migrations.application.UnknownPrincipalException;
import ch.alpenflight.migrations.domain.IllegalUploadStateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Module-scoped exception translator. Domain exceptions raised by
 * {@link ch.alpenflight.migrations.domain.MigrationUpload} flow through
 * the application service to the controller; this handler maps them to
 * RFC 7807 problem responses.
 */
@RestControllerAdvice(assignableTypes = MigrationHandshakeController.class)
class MigrationHandshakeExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(MigrationHandshakeExceptionHandler.class);

    @ExceptionHandler(IllegalUploadStateException.class)
    ProblemDetail onIllegalUploadState(IllegalUploadStateException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("Illegal upload state transition");
        return pd;
    }

    @ExceptionHandler(UnknownPrincipalException.class)
    ProblemDetail onUnknownPrincipal(UnknownPrincipalException e) {
        // Authenticated but no t_user row — the JIT materialise filter
        // skips this path (no clubId claim), so a verified-email user who
        // never completed signup never gets a t_user row. The principal
        // is "valid but unknown" — 403 mirrors the JIT soft-delete gate.
        LOG.warn("MigrationHandshake: authenticated principal with no t_user row");
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
        pd.setTitle("Unknown principal");
        return pd;
    }
}
