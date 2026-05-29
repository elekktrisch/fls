package ch.alpenflight.platform.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Single owner of writes to {@link JitUserMaterializationFilter#USER_ID_ATTRIBUTE}
 * that originate <em>outside</em> the JIT filter itself.
 *
 * <p>Pre-tenant endpoints (S-140 handshake; the verified-email user has no
 * {@code clubId} claim yet so the JIT filter stashes
 * {@link JitUserMaterializationFilter#ABSENT}) resolve their user id via a
 * sibling JDBC lookup. Cross-cutting consumers — currently
 * {@code ActorResolver} via {@code UserPrincipalLookup} — read the same
 * request attribute, so the lookup MUST stamp the resolved id back here so
 * downstream emitters see the user instead of {@code ABSENT}.
 *
 * <p>Without this stamp, audit rows for pre-tenant endpoints would land
 * with {@code actor_user_id = NULL} despite the principal being a known
 * user.
 */
public final class JitUserAttributeStamp {

    private JitUserAttributeStamp() {}

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
