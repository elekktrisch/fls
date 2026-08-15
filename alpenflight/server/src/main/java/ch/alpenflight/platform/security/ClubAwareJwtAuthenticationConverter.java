package ch.alpenflight.platform.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

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
