package ch.alpenflight.platform.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public final class JitUserAttributeStamp {

    private JitUserAttributeStamp() {}

    // RENAME: stampResolvedUserId -> stampResolvedUserIdForAuditActor
    public static void stampResolvedUserId(UUID userId) {
        HttpServletRequest request = currentRequest();
        if (request != null) {
            request.setAttribute(JitUserMaterializationFilter.USER_ID_ATTRIBUTE, userId);
        }
    }

    private static @Nullable HttpServletRequest currentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            return sra.getRequest();
        }
        return null;
    }
}
