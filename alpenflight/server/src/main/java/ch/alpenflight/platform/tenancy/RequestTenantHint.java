package ch.alpenflight.platform.tenancy;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public final class RequestTenantHint {

    public static final String ATTRIBUTE = RequestTenantHint.class.getName() + ".target";

    private RequestTenantHint() {}

    static @Nullable Object recordIfHttp(UUID clubId) {
        HttpServletRequest req = currentRequest();
        if (req == null) {
            return null;
        }
        Object prior = req.getAttribute(ATTRIBUTE);
        req.setAttribute(ATTRIBUTE, clubId);
        return prior;
    }

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
