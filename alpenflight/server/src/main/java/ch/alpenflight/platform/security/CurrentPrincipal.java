package ch.alpenflight.platform.security;

import ch.alpenflight.platform.tenancy.UserPrincipalLookup;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public class CurrentPrincipal {

    private final UserPrincipalLookup principals;

    public CurrentPrincipal(UserPrincipalLookup principals) {
        this.principals = principals;
    }

    public Optional<Jwt> jwt() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth && auth.isAuthenticated()) {
            return Optional.of(jwtAuth.getToken());
        }
        return Optional.empty();
    }

    public Optional<UUID> userId() {
        return jwt().flatMap(principals::resolveUserIdFor);
    }
}
