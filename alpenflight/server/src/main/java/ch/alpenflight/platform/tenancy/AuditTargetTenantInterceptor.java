package ch.alpenflight.platform.tenancy;

import ch.alpenflight.platform.id.ClubId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Parameter;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Publishes the {@link RequestTenantHint} for cross-tenant admin endpoints
 * BEFORE controller-argument validation fires. Without this preHandle the
 * hint is only set inside {@link Tenants#runAs} — too late if validation
 * 400s the request before the controller body executes (the original
 * audit row would then land on the JWT-resolved caller tenant instead of
 * the path-variable target).
 *
 * <p>The interceptor walks the matched handler method's parameters looking
 * for one annotated {@link AuditTargetTenant}, extracts its value from
 * the URI template variables, and records the hint.
 *
 * <p><strong>No production caller today.</strong> The S-049c admin
 * impersonation surface that originally used this was withdrawn in S-159;
 * this interceptor remains as scaffolding for future cutover / bulk-import
 * endpoints. DO NOT WIRE WITHOUT SECURITY REVIEW — the original threat
 * model assumed a hosted SYSTEM_ADMIN impersonation surface, which the
 * S-159 strip explicitly removed.
 */
@Component
public class AuditTargetTenantInterceptor implements HandlerInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(AuditTargetTenantInterceptor.class);

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        MethodParameter[] params = handlerMethod.getMethodParameters();
        for (MethodParameter mp : params) {
            Parameter raw = mp.getParameter();
            if (raw.isAnnotationPresent(AuditTargetTenant.class)) {
                String name = pathVariableName(mp);
                if (name == null) {
                    continue;
                }
                Object uriVars = request.getAttribute(
                        HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
                if (uriVars instanceof Map<?, ?> map) {
                    Object value = map.get(name);
                    if (value instanceof String s) {
                        try {
                            ClubId clubId = ClubId.parse(s);
                            RequestTenantHint.recordIfHttp(clubId.value());
                        } catch (IllegalArgumentException e) {
                            // Malformed path variable normally produces a 4xx
                            // via the path converter chain BEFORE this
                            // interceptor; arriving here means the ordering
                            // shifted. Log at WARN so the gap is visible
                            // (otherwise synthetic-failure rows would silently
                            // fall back to the actor's home tenant).
                            LOG.warn("Could not parse path-variable {} = '{}' as ClubId: {}",
                                    name, s, e.getMessage());
                        }
                    }
                }
            }
        }
        return true;
    }

    private static @Nullable String pathVariableName(MethodParameter mp) {
        PathVariable pv = mp.getParameterAnnotation(PathVariable.class);
        if (pv == null) {
            return null;
        }
        if (!pv.value().isEmpty()) {
            return pv.value();
        }
        if (!pv.name().isEmpty()) {
            return pv.name();
        }
        mp.initParameterNameDiscovery(new org.springframework.core.DefaultParameterNameDiscoverer());
        return mp.getParameterName();
    }
}
