package ch.alpenflight.users.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.platform.mail.CapturedMailSender;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import ch.alpenflight.users.application.UserDtos.UserInviteRequest;
import ch.alpenflight.users.application.UserDtos.UserResponse;
import ch.alpenflight.users.domain.Role;
import ch.alpenflight.users.domain.UserConflictException;
import ch.alpenflight.users.domain.UserDirectoryException;
import ch.alpenflight.users.domain.UserDirectoryPort;
import ch.alpenflight.users.domain.UserDirectoryPort.DirectoryUser;
import ch.alpenflight.users.domain.UserDirectoryPort.RealmRoleRef;
import com.github.f4b6a3.uuid.UuidCreator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Import(CapturedMailSender.Config.class)
class UsersInviteRobustnessIT extends PostgresIntegrationTest {

    private static final String NAME_PREFIX = "IT_UIR_";
    private static final String KEY_PREFIX = "IT_UIR_";
    private static final String REDACTED_BRANCH_WITH_THE_SPACING_JSONB_RESERIALISES_WITH =
            "\"branch\": \"[redacted]\"";

    @Autowired UsersService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired ClubRepository clubs;
    @Autowired CountryRepository countries;
    @Autowired ClubStateRepository clubStates;
    @Autowired CapturedMailSender outbox;

    @MockitoBean UserDirectoryPort directory;

    private final Map<UUID, UUID> directoryClubIdAttr = new ConcurrentHashMap<>();

    private UUID clubA;
    private UUID clubB;
    private UUID languageId;

    @BeforeEach
    void seed() {
        TwoClubFixture fixture =
                new TwoClubFixture(jdbc, clubs, countries, clubStates, NAME_PREFIX, KEY_PREFIX);
        fixture.seed();
        clubA = fixture.clubA();
        clubB = fixture.clubB();
        languageId = jdbc.queryForObject("SELECT id FROM t_language WHERE code = 'en' LIMIT 1", UUID.class);
        jdbc.update("DELETE FROM t_mutation_audit_event WHERE target_entity_type = 'InvitedAuditPayload'");
        jdbc.update("DELETE FROM t_user WHERE username LIKE 'uir-%'");
        directoryClubIdAttr.clear();
        outbox.clear();
        reset(directory);
        rewireDirectory();
        TenantTestContext.clear();
    }

    @Test
    void noKcUser_createsUser_grantsRoles_andSendsPasswordReset() {
        UUID newSub = UuidCreator.getTimeOrderedEpoch();
        when(directory.findUserByEmail("new@example.com")).thenReturn(Optional.empty());
        when(directory.createUser(any())).thenReturn(newSub);

        UserResponse created = TenantTestContext.runAs(clubA, () ->
                service.invite(invite("uir-new", "new@example.com"), clubAdminJwt()));

        assertThat(created.clubId()).isEqualTo(clubA);
        verify(directory).createUser(any());
        verify(directory).sendExecuteActions(any(), anyList(), any());
        assertThat(outbox.sent())
                .as("the create path invites through Keycloak's password-reset action, no welcome mail")
                .isEmpty();
        assertThat(rowClub("uir-new")).isEqualTo(clubA);
    }

    @Test
    void unattachedExistingKcUser_bindsToTenant_skipsPasswordReset_sendsWelcomeAttached() {
        UUID existingSub = UuidCreator.getTimeOrderedEpoch();
        when(directory.findUserByEmail("google@example.com"))
                .thenReturn(Optional.of(new DirectoryUser(existingSub, null, "de")));

        UserResponse bound = TenantTestContext.runAs(clubA, () ->
                service.invite(invite("uir-bind", "google@example.com"), clubAdminJwt()));

        assertThat(bound.clubId()).isEqualTo(clubA);
        verify(directory, never()).createUser(any());
        assertThat(directoryClubIdAttr.get(existingSub)).isEqualTo(clubA);
        UUID rowSub = jdbc.queryForObject(
                "SELECT keycloak_sub FROM t_user WHERE username = 'uir-bind' AND deleted_on IS NULL",
                UUID.class);
        assertThat(rowSub).isEqualTo(existingSub);
        verify(directory, never()).sendExecuteActions(any(), anyList(), any());
        assertThat(outbox.sent()).hasSize(1);
        String html = outbox.sent().get(0).htmlBody();
        assertThat(html)
                .as("the welcome-attached mail is localised to the directory user's de locale")
                .contains("Willkommen bei AlpenFlight");
    }

    @Test
    void attachedElsewhereKcUser_rejectedWith409_withoutLeakingOtherClubName() {
        UUID attachedSub = UuidCreator.getTimeOrderedEpoch();
        when(directory.findUserByEmail("member@example.com"))
                .thenReturn(Optional.of(new DirectoryUser(attachedSub, clubB, "en")));
        String clubBName = clubs.findActiveById(clubB).map(Club::getClubname).orElseThrow();

        assertThatThrownBy(() -> TenantTestContext.runAs(clubA, () ->
                service.invite(invite("uir-attached", "member@example.com"), clubAdminJwt())))
                .isInstanceOf(UserConflictException.class)
                .hasMessageContaining("already attached to another club")
                .hasMessageNotContaining(clubBName);

        verify(directory, never()).createUser(any());
        verify(directory, never()).writeClubIdAttribute(any(), any());
        assertThat(rowCount("uir-attached")).isZero();
    }

    @Test
    void invite_auditEvent_exposesBranchDiscriminator_notRedacted() {
        UUID existingSub = UuidCreator.getTimeOrderedEpoch();
        when(directory.findUserByEmail("audit@example.com"))
                .thenReturn(Optional.of(new DirectoryUser(existingSub, null, "en")));

        TenantTestContext.runAs(clubA, () ->
                service.invite(invite("uir-audit", "audit@example.com"), clubAdminJwt()));

        String afterState = jdbc.queryForObject(
                "SELECT after_state::text FROM t_mutation_audit_event "
                        + "WHERE target_entity_type = 'InvitedAuditPayload' "
                        + "ORDER BY occurred_at DESC LIMIT 1",
                String.class);
        assertThat(afterState)
                .as("the branch discriminator must ride verbatim, not as a redacted sentinel")
                .contains("\"attached_existing\"")
                .doesNotContain(REDACTED_BRANCH_WITH_THE_SPACING_JSONB_RESERIALISES_WITH);
    }

    @Test
    void mixedCaseEmail_isLowercasedForTheKeycloakLookup_soTheExistingKcUserBinds_not500() {
        UUID existingSub = UuidCreator.getTimeOrderedEpoch();
        when(directory.findUserByEmail("mixed@example.com"))
                .thenReturn(Optional.of(new DirectoryUser(existingSub, null, "en")));

        UserResponse bound = TenantTestContext.runAs(clubA, () ->
                service.invite(invite("uir-mixed", "Mixed@Example.COM"), clubAdminJwt()));

        assertThat(bound.clubId()).isEqualTo(clubA);
        verify(directory, never()).createUser(any());
        assertThat(directoryClubIdAttr.get(existingSub))
                .as("the mixed-case invite matched the lowercased KC record and bound it")
                .isEqualTo(clubA);
    }

    @Test
    void corruptedClubIdAttribute_treatedAsAttached_rejectedWith409_notBound() {
        UUID corruptedSub = UuidCreator.getTimeOrderedEpoch();
        when(directory.findUserByEmail("corrupt@example.com"))
                .thenReturn(Optional.of(new DirectoryUser(
                        corruptedSub, DirectoryUser.CORRUPTED_CLUB_ID, "en")));

        assertThatThrownBy(() -> TenantTestContext.runAs(clubA, () ->
                service.invite(invite("uir-corrupt", "corrupt@example.com"), clubAdminJwt())))
                .isInstanceOf(UserConflictException.class)
                .hasMessageContaining("already attached to another club");

        verify(directory, never()).createUser(any());
        verify(directory, never()).writeClubIdAttribute(any(), any());
        assertThat(rowCount("uir-corrupt")).isZero();
    }

    @Test
    void bindFailingAfterTheClubIdAttributeWrite_clearsTheStrandedAttribute() {
        UUID existingSub = UuidCreator.getTimeOrderedEpoch();
        when(directory.findUserByEmail("fail@example.com"))
                .thenReturn(Optional.of(new DirectoryUser(existingSub, null, "en")));
        doThrow(new UserDirectoryException("simulated KC grant failure"))
                .when(directory).grantRealmRoles(any(), anyList());

        assertThatThrownBy(() -> TenantTestContext.runAs(clubA, () ->
                service.invite(invite("uir-strand", "fail@example.com"), clubAdminJwt())))
                .isInstanceOf(RuntimeException.class);

        assertThat(directoryClubIdAttr.get(existingSub))
                .as("a rolled-back bind must strand no clubId attribute (tenant-leak guard)")
                .isNull();
        assertThat(rowCount("uir-strand")).isZero();
    }

    private UserInviteRequest invite(String username, String email) {
        return new UserInviteRequest(username, "Test Member", email, null, null,
                languageId, null, Set.of(Role.PILOT));
    }

    private static Jwt clubAdminJwt() {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("realm_access", Map.of("roles", List.of("CLUB_ADMINISTRATOR")))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .build();
    }

    private void rewireDirectory() {
        when(directory.findRealmRolesByName(any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Set<String> names = (Set<String>) inv.getArgument(0);
            return names.stream().map(n -> new RealmRoleRef(UUID.randomUUID().toString(), n, null)).toList();
        });
        org.mockito.Mockito.doAnswer(inv -> {
            directoryClubIdAttr.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(directory).writeClubIdAttribute(any(), any());
        org.mockito.Mockito.doAnswer(inv -> {
            directoryClubIdAttr.remove(inv.getArgument(0));
            return null;
        }).when(directory).clearClubIdAttribute(any());
    }

    private UUID rowClub(String username) {
        return jdbc.queryForObject(
                "SELECT club_id FROM t_user WHERE username = ? AND deleted_on IS NULL",
                UUID.class, username);
    }

    private int rowCount(String username) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM t_user WHERE username = ? AND deleted_on IS NULL",
                Integer.class, username);
        return n == null ? 0 : n;
    }
}
