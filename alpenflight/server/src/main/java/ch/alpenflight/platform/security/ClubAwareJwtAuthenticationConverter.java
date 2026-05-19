package ch.alpenflight.platform.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * Bridges a Keycloak-shaped JWT into Spring Security's authority model.
 *
 * <ul>
 *   <li>Reads roles from {@code realm_access.roles[]} (Keycloak convention).</li>
 *   <li>Prefixes each role with {@code ROLE_} so {@code hasRole('X')} matches.</li>
 *   <li>Leaves the {@code clubId} claim accessible via the standard
 *       {@link Jwt#getClaim(String)} path — that's what
 *       {@code @PreAuthorize("principal.claims['clubId']")} reads.</li>
 * </ul>
 *
 * <p>The mapping is exercised end-to-end by {@code ClubsAuthorizationTest}
 * (synthetic JWTs via Spring Security's test post-processor) and the live
 * {@code JwtDecoder} path is covered by {@code SecurityFilterChainIT}.
 */
@Configuration
public class ClubAwareJwtAuthenticationConverter extends JwtAuthenticationConverter {

    public ClubAwareJwtAuthenticationConverter() {
        setJwtGrantedAuthoritiesConverter(ClubAwareJwtAuthenticationConverter::extractAuthorities);
    }

    private static Collection<GrantedAuthority> extractAuthorities(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess == null) {
            return List.of();
        }
        Object roles = realmAccess.get("roles");
        if (!(roles instanceof Collection<?> raw)) {
            return List.of();
        }
        return raw.stream()
                .filter(r -> r instanceof String)
                .map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r))
                .toList();
    }
}
