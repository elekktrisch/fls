package ch.alpenflight.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Pure tests for the JIT filter's claim-predicate. Step-3 RED tests for
 * S-169: the filter is supposed to short-circuit on tokens that can't ride
 * the materialise path (sysadmin tokens, malformed clubId, missing
 * preferred_username), and forward those to {@code chain.doFilter}
 * untouched.
 *
 * <p>DB-touching paths (existing-row lookup, soft-delete gate, JIT insert)
 * live in {@code UsersJitFirstLoginIT}.
 */
class JitUserMaterializerShouldMaterialiseTest {

    private static final UUID CLUB = UUID.fromString("019e30c3-2c00-7001-8000-000000000001");

    @Test
    void shouldMaterialise_jwtWithSubAndClubId_returnsTrue() {
        Jwt jwt = realmJwt(UUID.randomUUID().toString(), CLUB.toString(), Map.of(
                "preferred_username", "alice",
                "given_name", "Alice",
                "email", "alice@example.com"));
        assertThat(JitUserMaterializationFilter.shouldMaterialise(jwt)).isTrue();
    }

    @Test
    void shouldMaterialise_missingClubId_returnsFalse() {
        Jwt jwt = realmJwt(UUID.randomUUID().toString(), /*clubId=*/ null, Map.of());
        assertThat(JitUserMaterializationFilter.shouldMaterialise(jwt)).isFalse();
    }

    @Test
    void shouldMaterialise_blankClubId_returnsFalse() {
        Jwt jwt = realmJwt(UUID.randomUUID().toString(), "", Map.of());
        assertThat(JitUserMaterializationFilter.shouldMaterialise(jwt)).isFalse();
    }

    @Test
    void shouldMaterialise_unparseableClubId_returnsFalse() {
        Jwt jwt = realmJwt(UUID.randomUUID().toString(), "not-a-uuid", Map.of());
        assertThat(JitUserMaterializationFilter.shouldMaterialise(jwt)).isFalse();
    }

    @Test
    void shouldMaterialise_nonUuidSub_returnsFalse() {
        // Federated Google IdP carries numeric sub. Out of scope for
        // local-row materialise; falls through to the JWT-only auth path.
        Jwt jwt = realmJwt("117412394827338472301", CLUB.toString(), Map.of());
        assertThat(JitUserMaterializationFilter.shouldMaterialise(jwt)).isFalse();
    }

    @Test
    void shouldMaterialise_missingSub_returnsFalse() {
        Jwt jwt = realmJwt(/*sub=*/ null, CLUB.toString(), Map.of());
        assertThat(JitUserMaterializationFilter.shouldMaterialise(jwt)).isFalse();
    }

    private static Jwt realmJwt(String sub, String clubId, Map<String, Object> extras) {
        Jwt.Builder b = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("realm_access", Map.of("roles", List.of("PILOT")))
                .issuedAt(Instant.now().minusSeconds(5))
                .expiresAt(Instant.now().plusSeconds(60));
        if (sub != null) {
            b = b.subject(sub);
        }
        if (clubId != null) {
            b = b.claim("clubId", clubId);
        }
        extras.forEach(b::claim);
        return b.build();
    }
}
