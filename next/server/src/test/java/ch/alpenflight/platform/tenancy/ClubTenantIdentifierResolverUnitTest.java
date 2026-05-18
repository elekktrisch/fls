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

@ExtendWith(MockitoExtension.class)
class ClubTenantIdentifierResolverUnitTest {

    private static final UUID CLAIM_CLUB = UUID.fromString("019e30c3-2c00-7001-8000-000000000010");
    private static final UUID DB_CLUB = UUID.fromString("019e30c3-2c00-7001-8000-000000000011");
    private static final UUID TEST_CTX_CLUB = UUID.fromString("019e30c3-2c00-7001-8000-000000000012");

    @Mock
    private UserTenantLookup userTenantLookup;

    private ClubTenantIdentifierResolver resolver;

    @BeforeEach
    void wire() {
        TenantTestContext.clear();
        SecurityContextHolder.clearContext();
        resolver = new ClubTenantIdentifierResolver(Optional.of(userTenantLookup));
    }

    @AfterEach
    void cleanup() {
        TenantTestContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void test_context_wins_over_security_context() {
        TenantTestContext.set(TEST_CTX_CLUB);
        SecurityContextHolder.getContext().setAuthentication(jwtToken(Map.of("clubId", CLAIM_CLUB.toString())));

        assertThat(resolver.resolveCurrentTenantIdentifier()).isEqualTo(TEST_CTX_CLUB);
        verify(userTenantLookup, never()).resolveTenantFor(any());
    }

    @Test
    void jwt_claim_resolves_directly() {
        SecurityContextHolder.getContext().setAuthentication(jwtToken(Map.of("clubId", CLAIM_CLUB.toString())));

        assertThat(resolver.resolveCurrentTenantIdentifier()).isEqualTo(CLAIM_CLUB);
        verify(userTenantLookup, never()).resolveTenantFor(any());
    }

    @Test
    void claim_absent_falls_back_to_db_lookup() {
        SecurityContextHolder.getContext().setAuthentication(jwtToken(Map.of()));
        when(userTenantLookup.resolveTenantFor(any(Jwt.class))).thenReturn(Optional.of(DB_CLUB));

        assertThat(resolver.resolveCurrentTenantIdentifier()).isEqualTo(DB_CLUB);
    }

    @Test
    void malformed_claim_falls_back_to_db_lookup() {
        SecurityContextHolder.getContext().setAuthentication(jwtToken(Map.of("clubId", "not-a-uuid")));
        when(userTenantLookup.resolveTenantFor(any(Jwt.class))).thenReturn(Optional.of(DB_CLUB));

        assertThat(resolver.resolveCurrentTenantIdentifier()).isEqualTo(DB_CLUB);
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
        SecurityContextHolder.getContext().setAuthentication(jwtToken(Map.of()));
        when(userTenantLookup.resolveTenantFor(any(Jwt.class))).thenReturn(Optional.empty());

        assertThat(resolver.resolveCurrentTenantIdentifier()).isEqualTo(ClubTenantIdentifierResolver.NO_TENANT);
    }

    @Test
    void mock_auth_profile_without_lookup_skips_db_path() {
        ClubTenantIdentifierResolver mockAuthResolver =
                new ClubTenantIdentifierResolver(Optional.empty());
        SecurityContextHolder.getContext().setAuthentication(jwtToken(Map.of("clubId", CLAIM_CLUB.toString())));

        assertThat(mockAuthResolver.resolveCurrentTenantIdentifier()).isEqualTo(CLAIM_CLUB);
    }

    @Test
    void mock_auth_no_claim_no_lookup_falls_through_to_sentinel() {
        ClubTenantIdentifierResolver mockAuthResolver =
                new ClubTenantIdentifierResolver(Optional.empty());
        SecurityContextHolder.getContext().setAuthentication(jwtToken(Map.of()));

        assertThat(mockAuthResolver.resolveCurrentTenantIdentifier())
                .isEqualTo(ClubTenantIdentifierResolver.NO_TENANT);
    }

    @Test
    void validate_existing_sessions_returns_false() {
        assertThat(resolver.validateExistingCurrentSessions()).isFalse();
    }

    private static JwtAuthenticationToken jwtToken(Map<String, Object> extraClaims) {
        Map<String, Object> claims = new java.util.HashMap<>();
        claims.put("iss", "http://test-issuer");
        claims.put("sub", UUID.randomUUID().toString());
        claims.putAll(extraClaims);
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuedAt(Instant.now().minusSeconds(5))
                .expiresAt(Instant.now().plusSeconds(60))
                .claims(c -> c.putAll(claims))
                .build();
        return new JwtAuthenticationToken(jwt, List.of());
    }
}
