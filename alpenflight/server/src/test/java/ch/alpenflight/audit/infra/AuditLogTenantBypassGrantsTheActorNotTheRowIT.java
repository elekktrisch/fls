package ch.alpenflight.audit.infra;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.audit.application.AuditEventDtos.AuditEventQuery;
import ch.alpenflight.audit.application.AuditQueryService;
import ch.alpenflight.audit.domain.MutationAuditEventRepository;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.Manifest;
import ch.alpenflight.migration.bundle.TenantBypassGrant;
import ch.alpenflight.migration.bundle.TenantBypassGrant.CrossTenantReason;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class AuditLogTenantBypassGrantsTheActorNotTheRowIT extends PostgresIntegrationTest {

    private static final String TEST_NAME_PREFIX = "IT_ALTB_";
    private static final String TEST_KEY_PREFIX = "IT_AL_";
    private static final UUID SEED_LANGUAGE_DE =
            UUID.fromString("019e2e15-2c00-77d0-8000-0000000007d0");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ClubRepository clubs;
    @Autowired private CountryRepository countries;
    @Autowired private ClubStateRepository clubStates;
    @Autowired private AuditQueryService auditQueries;
    @Autowired private MutationAuditEventRepository auditRows;

    private UUID clubA;
    private UUID clubB;
    private UUID actorUserInClubA;
    private UUID rowOwnedByClubA;
    private UUID rowOwnedByClubBWithTheClubAActor;
    private UUID rowMigratedWithNoTenantAtAll;
    private String targetEntityTypeUniqueToThisRun;

    @BeforeEach
    void seedTwoClubsOneSharedActorAndThreeAuditRows() {
        TwoClubFixture fixture = new TwoClubFixture(
                jdbc, clubs, countries, clubStates, TEST_NAME_PREFIX, TEST_KEY_PREFIX);
        fixture.seed();
        clubA = fixture.clubA();
        clubB = fixture.clubB();
        targetEntityTypeUniqueToThisRun =
                "AuditBypassProbe" + Long.toString(System.nanoTime(), 36);

        actorUserInClubA = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO t_user (id, club_id, username, friendly_name, notification_email,
                                    language_id)
                VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::uuid)
                """,
                actorUserInClubA.toString(), clubA.toString(),
                "audit-bypass-actor-" + Long.toString(System.nanoTime(), 36),
                "Audit Bypass Actor", "audit-bypass-actor@example.com",
                SEED_LANGUAGE_DE.toString());

        rowOwnedByClubA = insertAuditRow(clubA, actorUserInClubA, "NORMAL");
        rowOwnedByClubBWithTheClubAActor = insertAuditRow(clubB, actorUserInClubA, "NORMAL");
        rowMigratedWithNoTenantAtAll = insertAuditRow(null, null, "LEGACY_MIGRATED");
    }

    @AfterEach
    void removeTheProbeRowsThatOutliveTheTest() {
        jdbc.update("DELETE FROM t_mutation_audit_event WHERE target_entity_type = ?",
                targetEntityTypeUniqueToThisRun);
        jdbc.update("DELETE FROM t_user WHERE id = ?::uuid", actorUserInClubA.toString());
    }

    @Test
    void theSeededClubBRowReallyCarriesTheClubAActorSoTheAssertionsBelowAreNotVacuous() {
        UUID actorOfTheClubBRow = jdbc.queryForObject(
                "SELECT actor_user_id FROM t_mutation_audit_event WHERE id = ?::uuid",
                UUID.class, rowOwnedByClubBWithTheClubAActor.toString());
        UUID clubOfThatActor = jdbc.queryForObject(
                "SELECT club_id FROM t_user WHERE id = ?::uuid",
                UUID.class, actorUserInClubA.toString());

        assertThat(actorOfTheClubBRow).isEqualTo(actorUserInClubA);
        assertThat(clubOfThatActor)
                .as("the club-B audit row's actor genuinely lives in club A — this is the "
                        + "cross-tenant ride the AUDIT_LOG grant permits")
                .isEqualTo(clubA);
    }

    @Test
    void aClubAPrincipalNeverReadsTheClubBRowEvenThoughItsActorBelongsToClubA() {
        List<UUID> visibleToClubA = TenantTestContext.runAs(clubA, this::probeRowIdsForCaller);

        assertThat(visibleToClubA).contains(rowOwnedByClubA);
        assertThat(visibleToClubA)
                .as("the granted bypass moves the actor across tenants, never the audit row")
                .doesNotContain(rowOwnedByClubBWithTheClubAActor)
                .doesNotContain(rowMigratedWithNoTenantAtAll);
    }

    @Test
    void aClubAPrincipalCannotReachTheClubBRowByItsPrimaryKeyEither() {
        TenantTestContext.runAs(clubA, () -> {
            assertThat(auditRows.findById(rowOwnedByClubBWithTheClubAActor)).isEmpty();
            assertThat(auditRows.findById(rowMigratedWithNoTenantAtAll)).isEmpty();
            assertThat(auditRows.findById(rowOwnedByClubA)).isPresent();
        });
    }

    @Test
    void aClubBPrincipalReadsOnlyItsOwnRowSoTheIsolationIsSymmetric() {
        List<UUID> visibleToClubB = TenantTestContext.runAs(clubB, this::probeRowIdsForCaller);

        assertThat(visibleToClubB).containsExactly(rowOwnedByClubBWithTheClubAActor);
    }

    @Test
    void theAuditLogGrantCoversTheActorColumnOnlySoNoBundleCanDeclareTheTenantDiscriminator() {
        TenantBypassGrant auditGrant =
                Manifest.reviewedCrossTenantGrantsByEntity().get(EntityType.AUDIT_LOG);

        assertThat(auditGrant.grantedForeignKeyColumns()).containsExactly("actor_user_id");
        assertThat(auditGrant.crossTenantReasonByForeignKeyColumn().get("actor_user_id"))
                .isEqualTo(CrossTenantReason
                        .HISTORICAL_ACTOR_USER_OF_AN_AUDITED_CHANGE_NEVER_ITS_OWNING_TENANT);
    }

    private List<UUID> probeRowIdsForCaller() {
        return auditQueries.findPage(new AuditEventQuery(
                        null, null, null, targetEntityTypeUniqueToThisRun, null, 50, 0))
                .items().stream()
                .map(row -> row.id())
                .toList();
    }

    private UUID insertAuditRow(UUID tenantClubId, UUID actorUserId, String actorKind) {
        UUID rowId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO t_mutation_audit_event (
                    id, occurred_at, actor_user_id, actor_keycloak_sub, tenant_club_id,
                    action, target_entity_type, target_entity_id, request_id,
                    before_state, after_state, failed, system_actor,
                    http_status, failure_reason, actor_kind)
                VALUES (?::uuid, ?, ?::uuid, NULL, ?::uuid, 'UPDATE', ?, NULL, NULL,
                        NULL, NULL, false, false, NULL, NULL, ?)
                """,
                rowId.toString(),
                Timestamp.from(Instant.now()),
                actorUserId == null ? null : actorUserId.toString(),
                tenantClubId == null ? null : tenantClubId.toString(),
                targetEntityTypeUniqueToThisRun,
                actorKind);
        return rowId;
    }
}
