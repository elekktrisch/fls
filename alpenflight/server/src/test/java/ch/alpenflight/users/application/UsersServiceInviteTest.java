package ch.alpenflight.users.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.platform.mail.TemplatedMailService;
import ch.alpenflight.platform.tenancy.ClubTenantIdentifierResolver;
import ch.alpenflight.users.application.UserDtos.UserInviteRequest;
import ch.alpenflight.users.domain.Role;
import ch.alpenflight.users.domain.User;
import ch.alpenflight.users.domain.UserDirectoryException;
import ch.alpenflight.users.domain.UserDirectoryPort;
import ch.alpenflight.users.domain.UserDirectoryPort.RealmRoleRef;
import ch.alpenflight.users.domain.UserDirectoryPort.UserDirectorySpec;
import ch.alpenflight.users.domain.UserRepository;
import java.lang.reflect.Field;
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
    private final UserDirectoryPort directory = mock(UserDirectoryPort.class);
    private final RoleAssignmentPolicy rolePolicy = new RoleAssignmentPolicy();
    private final ClubTenantIdentifierResolver tenant = mock(ClubTenantIdentifierResolver.class);
    private final AuditTrail audit = mock(AuditTrail.class);
    private final ClubRepository clubs = mock(ClubRepository.class);
    private final TemplatedMailService mail = mock(TemplatedMailService.class);

    private final UsersService service =
            new UsersService(users, directory, rolePolicy, tenant, audit, clubs, mail, CLOCK);

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

    /** Persist-side test stand-in: stamps a UUID on the User so service code
     * can read it back as if Hibernate had just generated it. */
    private static User savedUser() {
        User u = User.register(CLUB, KC_SUB, "jane.doe", "Jane Doe",
                "jane@example.com", LANG, null);
        try {
            Field idField = User.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(u, UUID.randomUUID());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("failed to stamp test User.id", e);
        }
        return u;
    }

    @Test
    void invite_role_grant_rejected_does_not_call_keycloak() {
        when(tenant.resolveCurrentTenantIdentifier()).thenReturn(CLUB);
        UserInviteRequest req = new UserInviteRequest("jane.doe", "Jane Doe",
                "jane@example.com", null, null, LANG, null, Set.of(Role.SYSTEM_ADMINISTRATOR));

        assertThatThrownBy(() -> service.invite(req, clubAdminJwt()))
                .isInstanceOf(ForbiddenRoleGrantException.class);

        verify(directory, never()).createUser(any());
        verify(audit).recordFailed(any(), any(), eq(403), eq("USER_ROLE_GRANT_REJECTED"));
    }

    @Test
    void invite_compensating_delete_fires_when_local_save_throws() {
        when(tenant.resolveCurrentTenantIdentifier()).thenReturn(CLUB);
        when(users.findActiveByUsernameLower("jane.doe")).thenReturn(Optional.empty());
        when(directory.findUserByEmail("jane@example.com")).thenReturn(Optional.empty());
        when(directory.createUser(any(UserDirectorySpec.class))).thenReturn(KC_SUB);
        doThrow(new RuntimeException("simulated DB failure"))
                .when(users).save(any(User.class));

        assertThatThrownBy(() -> service.invite(sampleInvite(), clubAdminJwt()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("simulated DB failure");

        verify(directory, times(1)).deleteUser(KC_SUB);
        verify(directory, never()).grantRealmRoles(any(), anyList());
    }

    @Test
    void invite_username_conflict_rejected_before_keycloak_call() {
        when(tenant.resolveCurrentTenantIdentifier()).thenReturn(CLUB);
        User existing = User.register(CLUB, UUID.randomUUID(), "jane.doe", "Jane",
                "j@example.com", LANG, null);
        when(users.findActiveByUsernameLower("jane.doe")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.invite(sampleInvite(), clubAdminJwt()))
                .isInstanceOf(ch.alpenflight.users.domain.UserConflictException.class);
        verify(directory, never()).createUser(any());
    }

    @Test
    void invite_happy_path_creates_kc_user_then_local_row_then_grants_roles() {
        when(tenant.resolveCurrentTenantIdentifier()).thenReturn(CLUB);
        when(users.findActiveByUsernameLower("jane.doe")).thenReturn(Optional.empty());
        when(directory.findUserByEmail("jane@example.com")).thenReturn(Optional.empty());
        when(directory.createUser(any(UserDirectorySpec.class))).thenReturn(KC_SUB);
        when(users.save(any(User.class))).thenReturn(savedUser());
        when(directory.findRealmRolesByName(anySet())).thenReturn(List.of(
                new RealmRoleRef(null, "PILOT", null)));

        service.invite(sampleInvite(), clubAdminJwt());

        verify(directory).createUser(any(UserDirectorySpec.class));
        verify(users, atLeastOnce()).save(any(User.class));
        verify(directory).grantRealmRoles(eq(KC_SUB), anyList());
        verify(directory).sendExecuteActions(eq(KC_SUB), eq(List.of("UPDATE_PASSWORD")), any(Duration.class));
    }

    @Test
    void invite_kc_email_failure_does_not_roll_back_business_tx() {
        when(tenant.resolveCurrentTenantIdentifier()).thenReturn(CLUB);
        when(users.findActiveByUsernameLower("jane.doe")).thenReturn(Optional.empty());
        when(directory.findUserByEmail("jane@example.com")).thenReturn(Optional.empty());
        when(directory.createUser(any(UserDirectorySpec.class))).thenReturn(KC_SUB);
        when(users.save(any(User.class))).thenReturn(savedUser());
        when(directory.findRealmRolesByName(anySet())).thenReturn(List.of(
                new RealmRoleRef(null, "PILOT", null)));
        doThrow(new UserDirectoryException("smtp down"))
                .when(directory).sendExecuteActions(any(), anyList(), any());

        assertThat(service.invite(sampleInvite(), clubAdminJwt())).isNotNull();
    }
}
