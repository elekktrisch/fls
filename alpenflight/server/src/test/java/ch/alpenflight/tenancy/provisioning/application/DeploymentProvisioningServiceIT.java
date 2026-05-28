package ch.alpenflight.tenancy.provisioning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.alpenflight.deployments.domain.Deployment;
import ch.alpenflight.deployments.domain.DeploymentRepository;
import ch.alpenflight.deployments.domain.KeycloakReconcileState;
import ch.alpenflight.deployments.domain.LifecycleState;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.tenancy.provisioning.domain.DeploymentExistsException;
import ch.alpenflight.tenancy.provisioning.domain.KeycloakDeploymentDirectory;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Full-stack provisioning IT — exercises
 * {@link DeploymentProvisioningService} against a real Postgres so the
 * partial UNIQUE indexes ({@code ux_deployment_owner_active},
 * {@code ux_deployment_idempotency_key}) fire as they would in
 * production. The directory port is replaced with a Mockito mock so the
 * reconcile contract can be exercised without an upstream realm.
 *
 * <p>Covers: happy N-Club ingest, idempotent replay (including
 * owner-mismatch defense), second-ingest 409 across the non-terminal
 * lifecycle states, DELETING exemption, reference-data bootstrap,
 * directory-failure compensation, primary-Club fallback rules.
 */
@Import(DeploymentProvisioningServiceIT.MockDirectoryConfig.class)
class DeploymentProvisioningServiceIT extends PostgresIntegrationTest {

    @Autowired
    private DeploymentProvisioningService provisioning;

    @Autowired
    private DeploymentRepository deployments;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KeycloakDeploymentDirectory directory;

    private UUID countryId;
    private UUID clubStateId;

    @BeforeEach
    void seed() {
        countryId = jdbc.queryForObject("SELECT id FROM t_country LIMIT 1", UUID.class);
        clubStateId = jdbc.queryForObject("SELECT id FROM t_club_state LIMIT 1", UUID.class);

        // ADR 0021 pre-clean. IT-owned Deployments carry an IT_PROV_ prefix
        // the production seed never uses. Re-point any Clubs that pointed
        // at an IT-owned Deployment back to the operator default before
        // deleting (ON DELETE RESTRICT).
        jdbc.update("""
                UPDATE t_club SET deployment_id = '00000000-0000-0000-0000-000000000002'::uuid
                WHERE deployment_id IN (SELECT id FROM t_deployment WHERE name LIKE 'IT_PROV_%')
                """);
        jdbc.update("""
                DELETE FROM t_member_state WHERE club_id IN (
                    SELECT id FROM t_club WHERE clubname LIKE 'IT_PROV_%')
                """);
        jdbc.update("""
                DELETE FROM t_flight_type WHERE operating_club_id IN (
                    SELECT id FROM t_club WHERE clubname LIKE 'IT_PROV_%')
                """);
        jdbc.update("DELETE FROM t_club WHERE clubname LIKE 'IT_PROV_%'");
        jdbc.update("DELETE FROM t_deployment WHERE name LIKE 'IT_PROV_%'");

        // Default mock behaviour: return synthetic ids on every directory
        // call. Per-test overrides simulate KC failure.
        reset(directory);
        when(directory.findOrCreateDeploymentGroup(any(UUID.class)))
                .thenAnswer(inv -> UUID.randomUUID());
        when(directory.findOrCreateClubAdminRole(any(UUID.class), any(UUID.class)))
                .thenAnswer(inv -> UUID.randomUUID());
    }

    @Test
    void happy_path_two_club_bundle_provisions_full_state() {
        UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000a01");
        UUID idempotencyKey = UUID.randomUUID();

        ProvisioningRequest request = new ProvisioningRequest(
                idempotencyKey, owner, "IT_PROV_happy",
                List.of(
                        clubSpec("IT_PROV_alpha", "it-prov-alpha", "IPA"),
                        clubSpec("IT_PROV_bravo", "it-prov-bravo", "IPB")),
                null);

        ProvisioningResult result = provisioning.provision(request);
        provisioning.reconcileKeycloak(result.deploymentId());

        Deployment saved = deployments.findById(result.deploymentId()).orElseThrow();
        assertThat(saved.getLifecycleState()).isEqualTo(LifecycleState.TRIAL);
        assertThat(saved.getOwnerKeycloakSub()).isEqualTo(owner);
        assertThat(saved.getIdempotencyKey()).isEqualTo(idempotencyKey);
        assertThat(saved.getKeycloakState()).isEqualTo(KeycloakReconcileState.READY);
        assertThat(result.clubIds()).hasSize(2);
        assertThat(result.primaryClubId())
                .isEqualTo(result.clubIds().stream().min(Comparator.naturalOrder()).orElseThrow());

        // Per-Club reference data landed.
        for (UUID clubId : result.clubIds()) {
            Integer flightTypeCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM t_flight_type WHERE operating_club_id = ?::uuid",
                    Integer.class, clubId.toString());
            assertThat(flightTypeCount).isEqualTo(4);

            Integer memberStateCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM t_member_state WHERE club_id = ?::uuid",
                    Integer.class, clubId.toString());
            assertThat(memberStateCount).isEqualTo(3);
        }

        // KC reconcile invoked once per Club for the admin role; once for
        // the group; user attribute set once.
        verify(directory, times(1)).findOrCreateDeploymentGroup(eq(result.deploymentId()));
        verify(directory, times(1)).addUserToGroupIfAbsent(eq(owner), any(UUID.class));
        verify(directory, times(2)).findOrCreateClubAdminRole(eq(result.deploymentId()), any(UUID.class));
        verify(directory, times(2)).assignRoleIfAbsent(eq(owner), any(UUID.class), anyString());
        verify(directory, times(1)).setUserAttribute(
                eq(owner), eq("clubId"), eq(List.of(result.primaryClubId().toString())));
    }

    @Test
    void idempotent_replay_returns_same_deployment_without_second_insert() {
        UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000a02");
        UUID idempotencyKey = UUID.randomUUID();
        ProvisioningRequest request = new ProvisioningRequest(
                idempotencyKey, owner, "IT_PROV_idem",
                List.of(clubSpec("IT_PROV_idem_solo", "it-prov-idem-solo", "IPI")),
                null);

        ProvisioningResult first = provisioning.provision(request);
        ProvisioningResult second = provisioning.provision(request);

        assertThat(second.deploymentId()).isEqualTo(first.deploymentId());
        assertThat(second.clubIds()).isEqualTo(first.clubIds());

        Integer rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_deployment WHERE name = 'IT_PROV_idem'", Integer.class);
        assertThat(rowCount).isEqualTo(1);
    }

    @Test
    void second_ingest_for_owner_with_trial_deployment_throws_structured_409() {
        UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000a03");
        UUID firstKey = UUID.randomUUID();
        UUID secondKey = UUID.randomUUID();

        ProvisioningResult first = provisioning.provision(new ProvisioningRequest(
                firstKey, owner, "IT_PROV_first",
                List.of(clubSpec("IT_PROV_first_only", "it-prov-first-only", "IPF")),
                null));

        ProvisioningRequest secondAttempt = new ProvisioningRequest(
                secondKey, owner, "IT_PROV_second_attempt",
                List.of(clubSpec("IT_PROV_second_only", "it-prov-second-only", "IPS")),
                null);

        assertThatThrownBy(() -> provisioning.provision(secondAttempt))
                .isInstanceOf(DeploymentExistsException.class)
                .satisfies(thrown -> {
                    DeploymentExistsException e = (DeploymentExistsException) thrown;
                    assertThat(e.existingDeploymentId()).isEqualTo(first.deploymentId());
                    assertThat(e.existingLifecycleState()).isEqualTo(LifecycleState.TRIAL);
                    assertThat(e.existingDeploymentName()).isEqualTo("IT_PROV_first");
                    assertThat(e.existingClubIds()).containsExactlyInAnyOrderElementsOf(first.clubIds());
                });
    }

    @Test
    void second_ingest_blocked_for_active_past_due_and_cancelled_owners() {
        Clock clock = Clock.systemUTC();
        Map<LifecycleState, UUID> ownerByState = Map.of(
                LifecycleState.ACTIVE,
                        UUID.fromString("00000000-0000-0000-0000-000000000ac1"),
                LifecycleState.PAST_DUE,
                        UUID.fromString("00000000-0000-0000-0000-000000000ac2"),
                LifecycleState.CANCELLED,
                        UUID.fromString("00000000-0000-0000-0000-000000000ac3"));
        for (LifecycleState blockingState : List.of(
                LifecycleState.ACTIVE, LifecycleState.PAST_DUE, LifecycleState.CANCELLED)) {
            UUID owner = ownerByState.get(blockingState);

            Deployment first = Deployment.startTrial(clock,
                    "IT_PROV_blockedBy_" + blockingState, owner);
            first.bindIdempotencyKey(UUID.randomUUID());
            if (blockingState != LifecycleState.TRIAL) {
                first.activateSubscription("cus_" + blockingState, "sub_" + blockingState, clock);
            }
            if (blockingState == LifecycleState.PAST_DUE) {
                first.markPastDue(clock);
            }
            if (blockingState == LifecycleState.CANCELLED) {
                first.cancel(clock);
            }
            deployments.save(first);

            ProvisioningRequest attempt = new ProvisioningRequest(
                    UUID.randomUUID(), owner, "IT_PROV_attempt_" + blockingState,
                    List.of(clubSpec("IT_PROV_attempt_club_" + blockingState,
                            "it-prov-attempt-" + blockingState.toString().toLowerCase(Locale.ROOT),
                            "IP" + blockingState.name().charAt(0))),
                    null);

            assertThatThrownBy(() -> provisioning.provision(attempt))
                    .as("state %s should block re-provisioning", blockingState)
                    .isInstanceOf(DeploymentExistsException.class);
        }
    }

    @Test
    void deleting_state_does_not_block_new_provisioning() {
        UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000a05");
        Clock clock = Clock.systemUTC();

        Deployment first = Deployment.startTrial(clock, "IT_PROV_deleting", owner);
        first.bindIdempotencyKey(UUID.randomUUID());
        first.activateSubscription("cus_d", "sub_d", clock);
        first.cancel(clock);
        first.scheduleDelete(clock);
        deployments.save(first);

        ProvisioningResult fresh = provisioning.provision(new ProvisioningRequest(
                UUID.randomUUID(), owner, "IT_PROV_fresh_after_delete",
                List.of(clubSpec("IT_PROV_fresh_club", "it-prov-fresh-club", "IPC")),
                null));

        assertThat(fresh.deploymentId()).isNotEqualTo(first.getId());
    }

    @Test
    void kc_failure_in_phase_b_leaves_db_intact_and_state_pending() {
        UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000a06");
        UUID idempotencyKey = UUID.randomUUID();
        ProvisioningRequest request = new ProvisioningRequest(
                idempotencyKey, owner, "IT_PROV_kcfail",
                List.of(clubSpec("IT_PROV_kcfail_solo", "it-prov-kcfail-solo", "IPK")),
                null);

        doThrow(new RuntimeException("simulated KC 503"))
                .when(directory).findOrCreateDeploymentGroup(any(UUID.class));

        ProvisioningResult result = provisioning.provision(request);
        assertThatThrownBy(() -> provisioning.reconcileKeycloak(result.deploymentId()))
                .isInstanceOf(RuntimeException.class);

        Deployment afterFailure = deployments.findById(result.deploymentId()).orElseThrow();
        assertThat(afterFailure.getKeycloakState()).isEqualTo(KeycloakReconcileState.PENDING);
        assertThat(afterFailure.getLifecycleState()).isEqualTo(LifecycleState.TRIAL);
        assertThat(result.keycloakPending()).isTrue();
    }

    @Test
    void kc_reconcile_is_idempotent_after_retry() {
        UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000a07");
        ProvisioningRequest request = new ProvisioningRequest(
                UUID.randomUUID(), owner, "IT_PROV_retry",
                List.of(clubSpec("IT_PROV_retry_solo", "it-prov-retry-solo", "IPR")),
                null);

        UUID stubGroupId = UUID.randomUUID();
        UUID stubRoleId = UUID.randomUUID();
        when(directory.findOrCreateDeploymentGroup(any(UUID.class)))
                .thenThrow(new RuntimeException("simulated KC 503"))
                .thenReturn(stubGroupId);
        when(directory.findOrCreateClubAdminRole(any(UUID.class), any(UUID.class)))
                .thenReturn(stubRoleId);

        ProvisioningResult result = provisioning.provision(request);
        assertThatThrownBy(() -> provisioning.reconcileKeycloak(result.deploymentId()))
                .isInstanceOf(RuntimeException.class);

        provisioning.reconcileKeycloak(result.deploymentId());

        Deployment afterRetry = deployments.findById(result.deploymentId()).orElseThrow();
        assertThat(afterRetry.getKeycloakState()).isEqualTo(KeycloakReconcileState.READY);

        // Second invocation against a ready Deployment is a no-op:
        Mockito.clearInvocations(directory);
        provisioning.reconcileKeycloak(result.deploymentId());
        verify(directory, Mockito.never()).findOrCreateDeploymentGroup(any(UUID.class));
    }

    @Test
    void primary_club_falls_back_to_lowest_uuid_when_manifest_omits() {
        UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000a08");
        ProvisioningResult result = provisioning.provision(new ProvisioningRequest(
                UUID.randomUUID(), owner, "IT_PROV_primary",
                List.of(
                        clubSpec("IT_PROV_primary_alpha", "it-prov-primary-alpha", "IPA"),
                        clubSpec("IT_PROV_primary_bravo", "it-prov-primary-bravo", "IPB"),
                        clubSpec("IT_PROV_primary_gamma", "it-prov-primary-gamma", "IPG")),
                null));

        UUID expectedPrimary = result.clubIds().stream()
                .min(Comparator.naturalOrder())
                .orElseThrow();
        assertThat(result.primaryClubId()).isEqualTo(expectedPrimary);
    }

    @Test
    void manifest_primary_club_is_ignored_on_idempotent_replay() {
        UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000a09");
        UUID idempotencyKey = UUID.randomUUID();
        ProvisioningResult first = provisioning.provision(new ProvisioningRequest(
                idempotencyKey, owner, "IT_PROV_manifest",
                List.of(
                        clubSpec("IT_PROV_manifest_alpha", "it-prov-manifest-alpha", "IPA"),
                        clubSpec("IT_PROV_manifest_bravo", "it-prov-manifest-bravo", "IPB")),
                null));

        // Replay with a high-id manifest-declared primary; the service
        // short-circuits via idempotency and returns the originally-
        // resolved primary (lowest UUID), NOT the manifest hint —
        // primary is bound at first provisioning, not mutable on replay.
        UUID highestClubId = first.clubIds().stream()
                .max(Comparator.naturalOrder())
                .orElseThrow();

        ProvisioningResult replay = provisioning.provision(new ProvisioningRequest(
                idempotencyKey, owner, "IT_PROV_manifest",
                List.of(
                        clubSpec("IT_PROV_manifest_alpha", "it-prov-manifest-alpha", "IPA"),
                        clubSpec("IT_PROV_manifest_bravo", "it-prov-manifest-bravo", "IPB")),
                highestClubId));

        assertThat(replay.deploymentId()).isEqualTo(first.deploymentId());
        assertThat(replay.primaryClubId()).isEqualTo(first.primaryClubId());
    }

    @Test
    void replay_with_mismatched_owner_is_rejected() {
        UUID originalOwner = UUID.fromString("00000000-0000-0000-0000-000000000a0a");
        UUID attackerOwner = UUID.fromString("00000000-0000-0000-0000-000000000a0b");
        UUID idempotencyKey = UUID.randomUUID();

        provisioning.provision(new ProvisioningRequest(
                idempotencyKey, originalOwner, "IT_PROV_owner_first",
                List.of(clubSpec("IT_PROV_owner_first_solo", "it-prov-owner-first-solo", "IPO")),
                null));

        ProvisioningRequest hijack = new ProvisioningRequest(
                idempotencyKey, attackerOwner, "IT_PROV_owner_hijack",
                List.of(clubSpec("IT_PROV_owner_hijack_solo", "it-prov-owner-hijack-solo", "IPH")),
                null);

        assertThatThrownBy(() -> provisioning.provision(hijack))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different owner");
    }

    private ClubSpec clubSpec(String name, String slug, String clubKey) {
        return new ClubSpec(name, slug, clubKey, false, countryId, clubStateId);
    }

    /**
     * Spy-wraps the production {@link KeycloakDeploymentDirectory} bean so
     * tests can stub failures + assert call counts without dragging in a
     * real Keycloak. Methods default to throwing
     * {@code UnsupportedOperationException} (which would fail loudly); the
     * {@code @BeforeEach} sets per-test happy-path stubs.
     */
    @TestConfiguration
    static class MockDirectoryConfig {
        @Bean
        @Primary
        KeycloakDeploymentDirectory testKeycloakDeploymentDirectory() {
            return Mockito.mock(KeycloakDeploymentDirectory.class);
        }
    }
}
