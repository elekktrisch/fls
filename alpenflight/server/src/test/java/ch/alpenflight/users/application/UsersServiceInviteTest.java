package ch.alpenflight.users.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.platform.tenancy.ClubTenantIdentifierResolver;
import ch.alpenflight.users.application.UserDtos.UserInviteRequest;
import ch.alpenflight.users.domain.Role;
import ch.alpenflight.users.domain.User;
import ch.alpenflight.users.domain.UserRepository;
import ch.alpenflight.users.infra.keycloak.KeycloakAdminClient;
import ch.alpenflight.users.infra.keycloak.KeycloakAdminClient.KcUserSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Pins the two-phase invite orchestrator's compensating-delete + role-grant
 * rejection paths. WireMock for the full e2e shape lives in the IT layer;
 * this Mockito unit test fixes the contract.
 */
class UsersServiceInviteTest {

    private static final UUID CLUB = UUID.fromString("019e30c3-2c00-7001-8000-000000000001");
    private static final UUID KC_SUB = UUID.fromString("0fa9aaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID LANG = UUID.fromString("019e2e15-2c00-77d0-8000-0000000007d0");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-26T10:00:00Z"), ZoneOffset.UTC);

    private final UserRepository users = mock(UserRepository.class);
    private final KeycloakAdminClient kc = mock(KeycloakAdminClient.class);
    private final RoleAssignmentPolicy rolePolicy = new RoleAssignmentPolicy();
    private final ClubTenantIdentifierResolver tenant = mock(ClubTenantIdentifierResolver.class);
    private final AuditTrail audit = mock(AuditTrail.class);

    private final UsersService service =
            new UsersService(users, kc, rolePolicy, tenant, audit, CLOCK);

    private static Jwt clubAdminJwt() {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR")))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .build();
    }

    private static UserInviteRequest sampleInvite() {
        return new UserInviteRequest("jane.doe", "Jane Doe", "jane@example.com",
                null, null, LANG, null, Set.of(Role.PILOT));
    }

    @Test
    void invite_role_grant_rejected_does_not_call_keycloak() {
        when(tenant.resolveCurrentTenantIdentifier()).thenReturn(CLUB);
        UserInviteRequest req = new UserInviteRequest("jane.doe", "Jane Doe",
                "jane@example.com", null, null, LANG, null, Set.of(Role.SYSTEM_ADMINISTRATOR));

        assertThatThrownBy(() -> service.invite(req, clubAdminJwt()))
                .isInstanceOf(ForbiddenRoleGrantException.class);

        // No KC call AT ALL — rejection short-circuits before we touch the IdP.
        verify(kc, never()).createUser(any());
        verify(audit).recordFailed(any(), any(), eq(403), eq("USER_ROLE_GRANT_REJECTED"));
    }

    @Test
    void invite_compensating_delete_fires_when_local_save_throws() {
        when(tenant.resolveCurrentTenantIdentifier()).thenReturn(CLUB);
        when(users.findActiveByUsernameLower("jane.doe")).thenReturn(Optional.empty());
        when(kc.createUser(any(KcUserSpec.class))).thenReturn(KC_SUB);
        // Force the DB write to fail.
        doThrow(new RuntimeException("simulated DB failure"))
                .when(users).save(any(User.class));

        assertThatThrownBy(() -> service.invite(sampleInvite(), clubAdminJwt()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("simulated DB failure");

        // The orchestrator MUST issue a compensating DELETE so KC doesn't
        // hold an orphaned user that the next invite would 409 on.
        verify(kc, times(1)).deleteUser(KC_SUB);
        verify(kc, never()).grantRealmRoles(any(), anyList());
    }

    @Test
    void invite_username_conflict_rejected_before_keycloak_call() {
        when(tenant.resolveCurrentTenantIdentifier()).thenReturn(CLUB);
        User existing = User.register(CLUB, UUID.randomUUID(), "jane.doe", "Jane",
                "j@example.com", LANG, null);
        when(users.findActiveByUsernameLower("jane.doe")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.invite(sampleInvite(), clubAdminJwt()))
                .isInstanceOf(ch.alpenflight.users.domain.UserConflictException.class);
        verify(kc, never()).createUser(any());
    }

    @Test
    void invite_happy_path_creates_kc_user_then_local_row_then_grants_roles() {
        when(tenant.resolveCurrentTenantIdentifier()).thenReturn(CLUB);
        when(users.findActiveByUsernameLower("jane.doe")).thenReturn(Optional.empty());
        when(kc.createUser(any(KcUserSpec.class))).thenReturn(KC_SUB);
        User saved = User.register(CLUB, KC_SUB, "jane.doe", "Jane Doe",
                "jane@example.com", LANG, null);
        try {
            java.lang.reflect.Field idField = User.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(saved, UUID.randomUUID());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        when(users.save(any(User.class))).thenReturn(saved);
        when(kc.findRealmRolesByName(anySet())).thenReturn(List.of(
                new KeycloakAdminClient.KcRealmRoleRef(null, "PILOT", null)));

        service.invite(sampleInvite(), clubAdminJwt());

        verify(kc).createUser(any(KcUserSpec.class));
        verify(users, atLeastOnce()).save(any(User.class));
        verify(kc).grantRealmRoles(eq(KC_SUB), anyList());
        verify(kc).sendExecuteActions(eq(KC_SUB), eq(List.of("UPDATE_PASSWORD")), any(Duration.class));
    }

    @Test
    void invite_kc_email_failure_does_not_roll_back_business_tx() {
        when(tenant.resolveCurrentTenantIdentifier()).thenReturn(CLUB);
        when(users.findActiveByUsernameLower("jane.doe")).thenReturn(Optional.empty());
        when(kc.createUser(any(KcUserSpec.class))).thenReturn(KC_SUB);
        User saved = User.register(CLUB, KC_SUB, "jane.doe", "Jane Doe",
                "jane@example.com", LANG, null);
        try {
            java.lang.reflect.Field idField = User.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(saved, UUID.randomUUID());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        when(users.save(any(User.class))).thenReturn(saved);
        when(kc.findRealmRolesByName(anySet())).thenReturn(List.of(
                new KeycloakAdminClient.KcRealmRoleRef(null, "PILOT", null)));
        doThrow(new ch.alpenflight.users.infra.keycloak.KeycloakAdminException("smtp down"))
                .when(kc).sendExecuteActions(any(), anyList(), any());

        // The email failure must NOT propagate — operator can resend-invite.
        assertThat(service.invite(sampleInvite(), clubAdminJwt())).isNotNull();
    }
}
