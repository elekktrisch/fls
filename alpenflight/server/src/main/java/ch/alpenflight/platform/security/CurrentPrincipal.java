package ch.alpenflight.platform.security;

import ch.alpenflight.platform.tenancy.UserPrincipalLookup;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Reads the authenticated caller off the thread-local
 * {@link SecurityContextHolder} — the shared security plumbing every
 * tenant-scoped write service uses to stamp "who did this" on an audit /
 * soft-delete trail (ADR 0023: cross-cutting security tech lives in
 * {@code platform/}, the one package every module may depend on).
 *
 * <p>Resolution is fail-soft by construction: an anonymous flow (no
 * {@link JwtAuthenticationToken} in the context) yields {@link Optional#empty()}
 * rather than throwing, so a caller without a bearer token records a null actor
 * instead of failing the mutation it is otherwise authorized for.
 *
 * <p>Thread-local source: usable only on a request-bound thread. A reactive or
 * {@code @Async} hop loses the context (the same constraint every direct
 * {@code SecurityContextHolder} read carries).
 */
@Component
public class CurrentPrincipal {

    private final UserPrincipalLookup principals;

    public CurrentPrincipal(UserPrincipalLookup principals) {
        this.principals = principals;
    }

    /** The current request's authenticated JWT, or empty when the flow is anonymous. */
    public Optional<Jwt> jwt() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth && auth.isAuthenticated()) {
            return Optional.of(jwtAuth.getToken());
        }
        return Optional.empty();
    }

    /**
     * The caller's internal {@code user.id} (JWT sub → {@code t_user.id}), or empty
     * when the flow is anonymous or no active {@code user} row matches the subject.
     */
    public Optional<UUID> userId() {
        return jwt().flatMap(principals::resolveUserIdFor);
    }
}
