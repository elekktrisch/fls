package ch.alpenflight.audit.web;

import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Synthesises a {@code failed=true} audit row when a mutating request
 * returns a non-2xx status. Covers the two gaps the success-path
 * AFTER_COMMIT listener leaves:
 *
 * <ul>
 *   <li>4xx validation / security errors that never entered the service
 *       layer (so the AFTER_COMMIT listener never published anything).</li>
 *   <li>5xx exceptions that rolled the business transaction back — the
 *       success-path event published inside the rolled-back tx is
 *       discarded by Spring's transaction synchronisation, never reaching
 *       AFTER_COMMIT.</li>
 * </ul>
 *
 * <p>Runs after Spring Security so 401 / 403 responses get an audit trail
 * (which is itself useful for incident response — Actuator owns the
 * auth-event trail, but a failed business-mutation attempt by an
 * authenticated user still belongs in {@code mutation_audit_event}).
 *
 * <p>Per the refinement: the entity-type recorded for the synthetic row
 * is the request path's resource segment ({@code "Club"} for
 * {@code POST /api/v1/clubs}). Coarse but cheap; S-056 surfaces the
 * path + status combo in the UI for filtering.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
class RequestAuditFilter extends OncePerRequestFilter {

    private static final String API_PREFIX = "/api/v1/";
    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final AuditTrail auditTrail;

    RequestAuditFilter(AuditTrail auditTrail) {
        this.auditTrail = auditTrail;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        @Nullable Throwable thrown = null;
        try {
            chain.doFilter(request, response);
        } catch (RuntimeException | ServletException | IOException e) {
            thrown = e;
            throw e;
        } finally {
            if (isMutatingApiCall(request) && (thrown != null || isFailureStatus(response.getStatus()))) {
                recordSyntheticFailure(request, response, thrown);
            }
        }
    }

    private void recordSyntheticFailure(HttpServletRequest request,
                                        HttpServletResponse response,
                                        @Nullable Throwable thrown) {
        int status = thrown != null ? 500 : response.getStatus();
        String entityType = entityTypeFromPath(request.getRequestURI());
        String reason = thrown != null
                ? thrown.getClass().getSimpleName()
                : "http-" + status;
        AuditAction action = inferAction(request.getMethod());
        try {
            auditTrail.recordFailed(
                    action,
                    new AuditedTarget(entityType, null, null, null),
                    status,
                    reason);
        } catch (RuntimeException ignored) {
            // The audit trail's own failure must not turn a 4xx into a 500.
            // Already-set status / exception propagates unchanged.
        }
    }

    private static boolean isMutatingApiCall(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path != null
                && path.startsWith(API_PREFIX)
                && MUTATING_METHODS.contains(request.getMethod());
    }

    private static boolean isFailureStatus(int status) {
        return status >= 400 && status < 600;
    }

    private static AuditAction inferAction(String method) {
        return switch (method) {
            case "POST" -> AuditAction.CREATE;
            case "PUT", "PATCH" -> AuditAction.UPDATE;
            case "DELETE" -> AuditAction.DELETE;
            default -> AuditAction.UPDATE;
        };
    }

    /**
     * Derive a coarse entity-type from the request path: the segment after
     * {@code /api/v1/} (and after an optional {@code admin/}), capitalised
     * to match the convention {@code AuditedTarget.entityType} uses.
     * Falls back to {@code "Unknown"} if the path doesn't match.
     */
    static String entityTypeFromPath(String path) {
        if (path == null || !path.startsWith(API_PREFIX)) {
            return "Unknown";
        }
        String tail = path.substring(API_PREFIX.length());
        if (tail.startsWith("admin/")) {
            tail = tail.substring("admin/".length());
        }
        int slash = tail.indexOf('/');
        String segment = slash < 0 ? tail : tail.substring(0, slash);
        if (segment.isEmpty()) {
            return "Unknown";
        }
        return capitalize(segment);
    }

    private static String capitalize(String s) {
        StringBuilder out = new StringBuilder(s.length());
        out.append(Character.toUpperCase(s.charAt(0)));
        if (s.length() > 1) {
            out.append(s.substring(1));
        }
        // Drop trailing 's' for the common collection-resource pattern
        // (`clubs` → `Club`). Conservative — if a non-plural ever surfaces,
        // promote to an explicit mapping table; for now Clubs + Locations
        // are the only mutating resources.
        int last = out.length() - 1;
        if (last > 0 && out.charAt(last) == 's') {
            out.deleteCharAt(last);
        }
        return out.toString();
    }
}
