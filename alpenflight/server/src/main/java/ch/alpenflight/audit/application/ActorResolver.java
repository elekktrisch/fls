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
 *   <li>{@code actor_keycloak_sub} — the JWT subject as a UUID, if present.
 *       Immutable forensic key; survives GDPR/FADP erasure (which nulls
 *       {@code actor_user_id}).</li>
 *   <li>{@code actor_user_id} — the internal {@code user.id} for that sub.
 *       Resolved via {@link UserPrincipalLookup#resolveUserIdFor(Jwt)} and
 *       cached for 60s in a Caffeine cache (Performance plan: avoid one
 *       SELECT per audit row).</li>
 * </ul>
 *
 * <p>Anonymous flows (no {@code JwtAuthenticationToken}) return both as
 * {@code null}. Public-flow stories (S-153 trial flight, etc.) are
 * legitimately anonymous.
 */
@Component
public class ActorResolver {

    private static final Duration CACHE_TTL = Duration.ofSeconds(60);
    private static final int CACHE_MAX = 10_000;

    private final UserPrincipalLookup userPrincipalLookup;
    private final Cache<UUID, UUID> subToUserIdCache;

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
        UUID sub = parseUuid(jwt.getSubject());
        if (sub == null) {
            // Federated subs (Google's numeric IDs etc.) — record only that
            // an authenticated principal acted; user.id lookup not feasible
            // until those IdPs onboard with a UUID sub.
            return new Actor(null, null);
        }
        UUID userId = subToUserIdCache.get(sub, key -> {
            Optional<UUID> looked = userPrincipalLookup.resolveUserIdFor(jwt);
            return looked.orElse(null);
        });
        return new Actor(userId, sub);
    }

    private static @Nullable UUID parseUuid(@Nullable String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Carrier for the two actor identifiers (both may be null for anonymous). */
    public record Actor(@Nullable UUID userId, @Nullable UUID keycloakSub) {
        public static Actor anonymous() {
            return new Actor(null, null);
        }
    }
}
