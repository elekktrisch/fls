package ch.alpenflight.deployments.web;

import ch.alpenflight.deployments.domain.DeploymentNotFoundException;
import ch.alpenflight.deployments.domain.IllegalLifecycleTransitionException;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = DeploymentsAdminController.class)
class DeploymentsExceptionHandler {

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
