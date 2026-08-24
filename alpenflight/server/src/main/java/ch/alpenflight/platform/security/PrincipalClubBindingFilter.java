package ch.alpenflight.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

public class PrincipalClubBindingFilter extends OncePerRequestFilter {

    static final URI PROBLEM_TYPE_PRINCIPAL_NOT_BOUND_TO_CLUB =
            URI.create("urn:alpenflight:problem:principal-not-bound-to-club");

    private static final String PII_FREE_REFUSAL_MESSAGE =
            "The principal is not bound to the club it carries";

    private final List<PrincipalClubBindingRule> rules;
    private final ForbiddenProblemDetailResponse forbidden;

    public PrincipalClubBindingFilter(List<PrincipalClubBindingRule> rules,
                                      ObjectMapper objectMapper) {
        this.rules = List.copyOf(rules);
        this.forbidden = new ForbiddenProblemDetailResponse(objectMapper);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Jwt jwt = AuthenticatedJwtInTheSecurityContext.current();
        if (jwt == null) {
            chain.doFilter(request, response);
            return;
        }
        UUID carriedClubId = parsedClubIdClaim(jwt);
        if (carriedClubId == null) {
            chain.doFilter(request, response);
            return;
        }
        String preferredUsername = jwt.getClaimAsString("preferred_username");
        String username = preferredUsername == null ? "" : preferredUsername;
        for (PrincipalClubBindingRule rule : rules) {
            if (rule.refusesPrincipalCarryingClub(username, carriedClubId)) {
                forbidden.write(response, PROBLEM_TYPE_PRINCIPAL_NOT_BOUND_TO_CLUB,
                        PII_FREE_REFUSAL_MESSAGE);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static @Nullable UUID parsedClubIdClaim(Jwt jwt) {
        String claim = jwt.getClaimAsString("clubId");
        if (claim == null || claim.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(claim);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

}
