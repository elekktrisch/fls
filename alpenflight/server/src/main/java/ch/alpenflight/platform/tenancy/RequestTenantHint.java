package ch.alpenflight.platform.tenancy;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Per-request "target tenant" hint, published as an
 * {@link HttpServletRequest} attribute whenever {@link Tenants#runAs} fires
 * inside a servlet request. Consumed by
 * {@code ch.alpenflight.audit.web.RequestAuditFilter} so a synthetic
 * failure row records the path-variable target tenant of a sysadmin
 * cross-tenant operation rather than the actor's home tenant.
 *
 * <p>The thread-local {@link TenantContextCarrier} is already unwound by
 * the time the filter's {@code finally} block runs (the controller method
 * has returned), so the carrier alone can't carry the operating tenant
 * out to the filter. The HTTP request attribute survives because the
 * filter still owns the request object.
 *
 * <p>Outside an HTTP context (scheduled jobs, ingestion), all calls are
 * no-ops — those code paths never reach the filter.
 */
public final class RequestTenantHint {

    /** Attribute key on the current {@link HttpServletRequest}. */
    public static final String ATTRIBUTE = RequestTenantHint.class.getName() + ".target";

    private RequestTenantHint() {}

    /**
     * Set {@code clubId} as the request's target tenant if an HTTP request
     * is currently bound to the thread. Returns the prior attribute value
     * (or {@code null}) so the caller can restore it when its block unwinds.
     */
    static @Nullable Object recordIfHttp(UUID clubId) {
        HttpServletRequest req = currentRequest();
        if (req == null) {
            return null;
        }
        Object prior = req.getAttribute(ATTRIBUTE);
        req.setAttribute(ATTRIBUTE, clubId);
        return prior;
    }

    /**
     * Restore the prior attribute value captured by {@link #recordIfHttp}.
     * {@code null} clears the attribute; any non-null value re-publishes
     * the outer-block tenant (the nested-{@code runAs} case).
     */
    static void restoreIfHttp(@Nullable Object prior) {
        HttpServletRequest req = currentRequest();
        if (req == null) {
            return;
        }
        if (prior == null) {
            req.removeAttribute(ATTRIBUTE);
        } else {
            req.setAttribute(ATTRIBUTE, prior);
        }
    }

    /** Public read for the filter — null outside HTTP or when no hint was set. */
    public static @Nullable UUID currentForRequest(HttpServletRequest request) {
        Object v = request.getAttribute(ATTRIBUTE);
        return v instanceof UUID uuid ? uuid : null;
    }

    private static @Nullable HttpServletRequest currentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            return sra.getRequest();
        }
        return null;
    }
}
