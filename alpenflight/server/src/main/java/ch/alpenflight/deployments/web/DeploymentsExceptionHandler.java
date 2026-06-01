package ch.alpenflight.deployments.web;

import ch.alpenflight.deployments.domain.DeploymentNotFoundException;
import ch.alpenflight.deployments.domain.IllegalLifecycleTransitionException;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates the deployments domain exception vocabulary to HTTP. Holds
 * the only Spring-web coupling of the deployments error vocabulary —
 * the exception types themselves stay in {@code deployments.domain}
 * free of {@code @ResponseStatus} per ADR 0023.
 *
 * <p>Scope-narrowed to the admin controller's package so a future module
 * that throws the same exception type by mistake doesn't inherit this
 * mapping. Module-local error vocabulary, module-local advice.
 */
@RestControllerAdvice(basePackageClasses = DeploymentsAdminController.class)
class DeploymentsExceptionHandler {

    /**
     * Tight error body — exposes the offending transition for the
     * operator UI's "you can't go there from here" feedback, never any
     * Deployment-internal field.
     */
    public record ApiError(String code, String message) {}

    @ExceptionHandler(DeploymentNotFoundException.class)
    ResponseEntity<Void> handleNotFound(DeploymentNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    @ExceptionHandler(IllegalLifecycleTransitionException.class)
    ResponseEntity<ApiError> handleIllegalTransition(IllegalLifecycleTransitionException e) {
        String message = e.getMessage();
        String code = resolveCode(e, message);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError(code, message == null ? "Illegal transition" : message));
    }

    /**
     * Stable error codes the operator UI parses. {@code sandbox_immutable}
     * is pinned by the security plan; other illegal transitions surface
     * as {@code illegal_transition_<from>_to_<target>} so a UI can render
     * a specific "can't go there from here" affordance.
     */
    private static String resolveCode(IllegalLifecycleTransitionException e,
                                      @org.jspecify.annotations.Nullable String message) {
        if (message != null && message.contains("sandbox_immutable")) {
            return "sandbox_immutable";
        }
        if (e.getFromState() == null) {
            return "illegal_transition";
        }
        return "illegal_transition_"
                + e.getFromState().name().toLowerCase(Locale.ROOT)
                + "_to_"
                + e.getTargetState().name().toLowerCase(Locale.ROOT);
    }
}
