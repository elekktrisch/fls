package ch.alpenflight.audit.web;

import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
import ch.alpenflight.platform.tenancy.ClubTenantIdentifierResolver;
import ch.alpenflight.platform.tenancy.RequestTenantHint;
import ch.alpenflight.platform.tenancy.Tenants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
class RequestAuditFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(RequestAuditFilter.class);

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
            int status = thrown != null ? 500 : response.getStatus();
            if (isMutatingApiCall(request) && shouldRecordFailure(thrown, status)) {
                recordSyntheticFailure(request, response, thrown);
            }
        }
    }

    private static boolean shouldRecordFailure(@Nullable Throwable thrown, int status) {
        if (thrown != null) {
            return true;
        }
        if (isAuthStatusOwnedByActuatorAuditTrail(status)) {
            return false;
        }
        return isFailureStatus(status);
    }

    private static boolean isAuthStatusOwnedByActuatorAuditTrail(int status) {
        return status == 401 || status == 403;
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
        UUID targetTenantHint = RequestTenantHint.currentForRequest(request);
        try {
            if (targetTenantHint != null
                    && !ClubTenantIdentifierResolver.NO_TENANT.equals(targetTenantHint)) {
                Tenants.runAs(targetTenantHint, () -> {
                    auditTrail.recordFailed(
                            action,
                            new AuditedTarget(entityType, null, null, null),
                            status,
                            reason);
                });
            } else {
                auditTrail.recordFailed(
                        action,
                        new AuditedTarget(entityType, null, null, null),
                        status,
                        reason);
            }
        } catch (RuntimeException e) {
            LOG.error("audit-filter failed to record synthetic failure uri={} status={} reason={}",
                    request.getRequestURI(), status, reason, e);
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
        return capitalizedSingular(segment);
    }

    private static String capitalizedSingular(String s) {
        StringBuilder out = new StringBuilder(s.length());
        out.append(Character.toUpperCase(s.charAt(0)));
        if (s.length() > 1) {
            out.append(s.substring(1));
        }
        int last = out.length() - 1;
        if (last > 0 && out.charAt(last) == 's') {
            out.deleteCharAt(last);
        }
        return out.toString();
    }
}
