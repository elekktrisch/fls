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

    public Actor resolve() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwtAuth) || !auth.isAuthenticated()) {
            return Actor.anonymous();
        }
        Jwt jwt = jwtAuth.getToken();
        String sub = jwt.getSubject();
        if (sub == null || sub.isBlank()) {
            return new Actor(null, null);
        }
        UUID userId = subToUserIdCache.get(sub, key -> {
            Optional<UUID> looked = userPrincipalLookup.resolveUserIdFor(jwt);
            return looked.orElse(null);
        });
        return new Actor(userId, sub);
    }

    public void evict(String sub) {
        subToUserIdCache.invalidate(sub);
    }

    public record Actor(@Nullable UUID userId, @Nullable String keycloakSub) {
        public static Actor anonymous() {
            return new Actor(null, null);
        }
    }
}
