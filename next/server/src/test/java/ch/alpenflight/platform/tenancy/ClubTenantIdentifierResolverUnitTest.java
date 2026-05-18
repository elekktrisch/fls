package ch.alpenflight.platform.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.alpenflight.server.testsupport.TenantTestContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Resolver precedence chain unit tests. The resolver doesn't need Spring or a
 * database to assert the per-call dispatch logic; the integration tests below
 * carry the wire-it-into-Hibernate parts.
 */
@ExtendWith(MockitoExtension.class)
class ClubTenantIdentifierResolverUnitTest {

    private static final String TRUSTED_ISSUER = "http://localhost:8090/realms/alpenflight";
    private static final String FEDERATED_ISSUER = "https://accounts.google.com";

    private static final UUID CLUB_FROM_CLAIM = UUID.fromString("019e30c3-2c00-7001-8000-000000000010");
    private static final UUID CLUB_FROM_DB = UUID.fromString("019e30c3-2c00-7001-8000-000000000011");

    @Mock
    private UserTenantLookup userTenantLookup;

    private TrustedIssuerRegistry trustedIssuers;
    private ClubTenantIdentifierResolver resolver;

    @BeforeEach
    void wire() {
        TenantTestContext.clear();
        SecurityContextHolder.clearContext();
        trustedIssuers = new TrustedIssuerRegistry(List.of(TRUSTED_ISSUER));
        resolver = new ClubTenantIdentifierResolver(trustedIssuers, Optional.of(userTenantLookup));
    }

    @AfterEach
    void cleanup() {
        TenantTestContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void test_context_wins_over_security_context() {
        UUID testCtxClub = UUID.fromString("019e30c3-2c00-7001-8000-000000000020");
        TenantTestContext.set(testCtxClub);
        SecurityContextHolder.getContext().setAuthentication(jwtTokenFrom(TRUSTED_ISSUER,
                Map.of("clubId", CLUB_FROM_CLAIM.toString())));

        assertThat(resolver.resolveCurrentTenantIdentifier()).isEqualTo(testCtxClub);
        verify(userTenantLookup, never()).resolveTenantFor(any());
    }

    @Test
    void trusted_issuer_claim_trusted_directly() {
        SecurityContextHolder.getContext().setAuthentication(jwtTokenFrom(TRUSTED_ISSUER,
                Map.of("clubId", CLUB_FROM_CLAIM.toString())));

        assertThat(resolver.resolveCurrentTenantIdentifier()).isEqualTo(CLUB_FROM_CLAIM);
        verify(userTenantLookup, never()).resolveTenantFor(any());
    }

    @Test
    void federated_issuer_db_verify_even_when_claim_present() {
        SecurityContextHolder.getContext().setAuthentication(jwtTokenFrom(FEDERATED_ISSUER,
                Map.of("clubId", CLUB_FROM_CLAIM.toString())));
        when(userTenantLookup.resolveTenantFor(any(Jwt.class))).thenReturn(Optional.of(CLUB_FROM_DB));

        assertThat(resolver.resolveCurrentTenantIdentifier()).isEqualTo(CLUB_FROM_DB);
        verify(userTenantLookup).resolveTenantFor(any(Jwt.class));
    }

    @Test
    void claim_absent_falls_back_to_db_lookup() {
        SecurityContextHolder.getContext().setAuthentication(jwtTokenFrom(TRUSTED_ISSUER, Map.of()));
        when(userTenantLookup.resolveTenantFor(any(Jwt.class))).thenReturn(Optional.of(CLUB_FROM_DB));

        assertThat(resolver.resolveCurrentTenantIdentifier()).isEqualTo(CLUB_FROM_DB);
    }

    @Test
    void malformed_claim_falls_back_to_db_lookup() {
        SecurityContextHolder.getContext().setAuthentication(jwtTokenFrom(TRUSTED_ISSUER,
                Map.of("clubId", "not-a-uuid")));
        when(userTenantLookup.resolveTenantFor(any(Jwt.class))).thenReturn(Optional.of(CLUB_FROM_DB));

        assertThat(resolver.resolveCurrentTenantIdentifier()).isEqualTo(CLUB_FROM_DB);
    }

    @Test
    void anonymous_authentication_resolves_to_sentinel() {
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("anon", "anon",
                        List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        assertThat(resolver.resolveCurrentTenantIdentifier()).isEqualTo(ClubTenantIdentifierResolver.NO_TENANT);
    }

    @Test
    void no_authentication_resolves_to_sentinel() {
        assertThat(resolver.resolveCurrentTenantIdentifier()).isEqualTo(ClubTenantIdentifierResolver.NO_TENANT);
    }

    @Test
    void db_miss_resolves_to_sentinel() {
        SecurityContextHolder.getContext().setAuthentication(jwtTokenFrom(TRUSTED_ISSUER, Map.of()));
        when(userTenantLookup.resolveTenantFor(any(Jwt.class))).thenReturn(Optional.empty());

        assertThat(resolver.resolveCurrentTenantIdentifier()).isEqualTo(ClubTenantIdentifierResolver.NO_TENANT);
    }

    @Test
    void validate_existing_sessions_returns_false() {
        assertThat(resolver.validateExistingCurrentSessions()).isFalse();
    }

    @Test
    void mock_auth_profile_without_lookup_skips_db_path() {
        // Resolver under @Profile("mock-auth") has Optional.empty() for the lookup.
        ClubTenantIdentifierResolver mockAuthResolver =
                new ClubTenantIdentifierResolver(trustedIssuers, Optional.empty());
        SecurityContextHolder.getContext().setAuthentication(jwtTokenFrom(TRUSTED_ISSUER,
                Map.of("clubId", CLUB_FROM_CLAIM.toString())));

        assertThat(mockAuthResolver.resolveCurrentTenantIdentifier()).isEqualTo(CLUB_FROM_CLAIM);
    }

    @Test
    void mock_auth_no_claim_no_lookup_falls_through_to_sentinel() {
        ClubTenantIdentifierResolver mockAuthResolver =
                new ClubTenantIdentifierResolver(trustedIssuers, Optional.empty());
        SecurityContextHolder.getContext().setAuthentication(jwtTokenFrom(TRUSTED_ISSUER, Map.of()));

        assertThat(mockAuthResolver.resolveCurrentTenantIdentifier())
                .isEqualTo(ClubTenantIdentifierResolver.NO_TENANT);
    }

    private static JwtAuthenticationToken jwtTokenFrom(String issuer, Map<String, Object> extraClaims) {
        Map<String, Object> claims = new java.util.HashMap<>();
        claims.put("iss", issuer);
        claims.put("sub", "test-user-" + UUID.randomUUID());
        claims.putAll(extraClaims);
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuedAt(Instant.now().minusSeconds(5))
                .expiresAt(Instant.now().plusSeconds(60))
                .claims(c -> c.putAll(claims))
                .build();
        // Two-arg constructor flips isAuthenticated() to true; the
        // single-arg ctor leaves it false and the resolver short-circuits.
        return new JwtAuthenticationToken(jwt, List.of());
    }
}
