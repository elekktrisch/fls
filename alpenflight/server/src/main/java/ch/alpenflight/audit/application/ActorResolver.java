package ch.alpenflight.audit.application;

import ch.alpenflight.platform.tenancy.UserPrincipalLookup;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Resolves the audit-event actor from the current Spring Security context.
 * Two fields land on the row:
 *
 * <ul>
 *   <li>{@code actor_keycloak_sub} — the JWT subject as a raw string.
 *       Whatever the issuer minted: a UUID for our Keycloak realm, a
 *       numeric ID for Google, an arbitrary token for Auth0. The audit
 *       trail records the principal verbatim; parsing (if any) is S-056's
 *       and forensic tooling's concern. Immutable forensic key; survives
 *       GDPR/FADP erasure (which nulls {@code actor_user_id}).</li>
 *   <li>{@code actor_user_id} — the internal {@code user.id} for that sub.
 *       Resolved via {@link UserPrincipalLookup#resolveUserIdFor(Jwt)} and
 *       cached for 60s in a Caffeine cache (Performance plan: avoid one
 *       SELECT per audit row). Lookup may legitimately yield empty for
 *       federated users without a {@code user} row.</li>
 * </ul>
 *
 * <p>Authenticated principal → {@code system_actor=false}, regardless of
 * sub shape. Anonymous flows (no {@code JwtAuthenticationToken}) →
 * {@code system_actor=true}, both identifiers null. The gate is the
 * authentication type, never the parse-shape of the sub claim — federated
 * IdPs would otherwise be silently misclassified as scheduled-job writes.
 */
@Component
public class ActorResolver {

    private static final Duration CACHE_TTL = Duration.ofSeconds(60);
    private static final int CACHE_MAX = 10_000;

    private final UserPrincipalLookup userPrincipalLookup;
    private final Cache<String, UUID> subToUserIdCache;

    public ActorResolver(UserPrincipalLookup userPrincipalLookup) {
        this.userPrincipalLookup = userPrincipalLookup;
        this.subToUserIdCache = Caffeine.newBuilder()
                .maximumSize(CACHE_MAX)
                .expireAfterWrite(CACHE_TTL)
                .build();
    }

    /** Active record for the current thread's audit identity, ready to land on a row. */
    public Actor resolve() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwtAuth) || !auth.isAuthenticated()) {
            return Actor.anonymous();
        }
        Jwt jwt = jwtAuth.getToken();
        String sub = jwt.getSubject();
        if (sub == null || sub.isBlank()) {
            // JWT with no sub claim — broken token; record what we can
            // (authenticated principal, no forensic key) and surface
            // system_actor=false because an authenticated principal IS in
            // the security context.
            return new Actor(null, null);
        }
        UUID userId = subToUserIdCache.get(sub, key -> {
            Optional<UUID> looked = userPrincipalLookup.resolveUserIdFor(jwt);
            return looked.orElse(null);
        });
        return new Actor(userId, sub);
    }

    /**
     * Invalidate a single sub from the principal-lookup cache. Called by
     * the user-deactivation flow so deactivated users' subsequent attempts
     * (a still-valid JWT before logout) are re-resolved and reflect any
     * downstream effects (e.g. their user row being scrubbed).
     */
    public void evict(String sub) {
        subToUserIdCache.invalidate(sub);
    }

    /** Carrier for the two actor identifiers (both may be null for anonymous). */
    public record Actor(@Nullable UUID userId, @Nullable String keycloakSub) {
        public static Actor anonymous() {
            return new Actor(null, null);
        }
    }
}
