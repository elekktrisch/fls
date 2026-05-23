package ch.alpenflight.platform.tenancy;

import ch.alpenflight.platform.id.ClubId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Parameter;
import java.util.Map;
import org.jspecify.annotations.Nullable;
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
 * audit row would then land on the JWT-resolved sysadmin tenant instead
 * of the path-variable target).
 *
 * <p>The interceptor walks the matched handler method's parameters looking
 * for one annotated {@link AuditTargetTenant}, extracts its value from
 * the URI template variables, and records the hint.
 */
@Component
public class AuditTargetTenantInterceptor implements HandlerInterceptor {

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
                        } catch (IllegalArgumentException ignored) {
                            // Malformed path variable becomes a 4xx via the
                            // normal converter chain; nothing to hint with.
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
