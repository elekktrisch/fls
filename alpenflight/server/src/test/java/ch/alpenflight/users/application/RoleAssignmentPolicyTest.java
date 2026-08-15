package ch.alpenflight.users.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.users.domain.Role;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class RoleAssignmentPolicyTest {

    private final RoleAssignmentPolicy policy = new RoleAssignmentPolicy();

    private static Jwt jwtWithRoles(String... roles) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("realm_access", Map.of("roles", List.of(roles)))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }

    @Test
    void club_admin_may_grant_application_realm_roles_excluding_sysadmin() {
        Jwt jwt = jwtWithRoles("CLUB_ADMINISTRATOR");
        for (Role grantable : Set.of(Role.CLUB_ADMINISTRATOR, Role.FLIGHT_OPERATOR,
                Role.PILOT, Role.OFFICE_USER, Role.GUEST)) {
            assertThat(policy.isGrantable(jwt, Set.of(grantable)))
                    .as("CLUB_ADMINISTRATOR must be able to grant %s", grantable)
                    .isTrue();
        }
    }

    @Test
    void club_admin_must_not_grant_sysadmin() {
        Jwt jwt = jwtWithRoles("CLUB_ADMINISTRATOR");
        assertThat(policy.isGrantable(jwt, Set.of(Role.SYSTEM_ADMINISTRATOR))).isFalse();
        assertThat(policy.rejectedRoles(jwt, Set.of(Role.SYSTEM_ADMINISTRATOR, Role.PILOT)))
                .containsExactly(Role.SYSTEM_ADMINISTRATOR);
    }

    @Test
    void non_club_admin_caller_can_grant_nothing() {
        Jwt pilot = jwtWithRoles("PILOT");
        assertThat(policy.isGrantable(pilot, Set.of(Role.PILOT))).isFalse();
    }

    @Test
    void sysadmin_caller_is_outside_the_club_admin_grant_cabin_and_may_grant_nothing() {
        Jwt sysadmin = jwtWithRoles("SYSTEM_ADMINISTRATOR");
        assertThat(policy.isGrantable(sysadmin, Set.of(Role.PILOT))).isFalse();
    }

    @Test
    void null_jwt_or_empty_roles_rejected() {
        assertThat(policy.isGrantable(null, Set.of(Role.PILOT))).isFalse();
        assertThat(policy.isGrantable(jwtWithRoles(), Set.of(Role.PILOT))).isFalse();
    }
}
