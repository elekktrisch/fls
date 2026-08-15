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
