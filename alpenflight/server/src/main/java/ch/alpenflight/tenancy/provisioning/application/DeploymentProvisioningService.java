package ch.alpenflight.tenancy.provisioning.application;

import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.deployments.domain.Deployment;
import ch.alpenflight.deployments.domain.DeploymentRepository;
import ch.alpenflight.platform.id.ClubId;
import ch.alpenflight.platform.tenancy.Tenants;
import ch.alpenflight.tenancy.provisioning.domain.DeploymentExistsException;
import ch.alpenflight.tenancy.provisioning.domain.KeycloakDeploymentDirectory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Trial-Deployment provisioning service (S-138). The single seam S-141's
 * ingest pipeline calls to materialize a Deployment + Clubs + per-Club
 * reference data + Keycloak group / role / attribute plumbing for a
 * freshly-signed-up user on the first successful bundle ingest.
 *
 * <p>Two-phase execution mirrors the refinement pin:
 *
 * <ul>
 *   <li><strong>Phase A</strong> — {@link #provision} inside an ingest
 *       transaction. Idempotency check on {@code migration_run.id}, owner
 *       409 pre-check + structural UNIQUE backstop, Deployment +
 *       per-Club row creation, reference-data seed, audit emission via
 *       {@link Deployment#startTrial}'s lifecycle event. Returns a
 *       {@link ProvisioningResult} with {@code kcPending = true}.</li>
 *   <li><strong>Phase B</strong> — {@link #reconcileKeycloak} post-commit
 *       (called by the orchestrating caller or by S-141's hourly retry
 *       job). Idempotent group / role / attribute reconcile against
 *       Keycloak; on success flips {@code kc_state = READY} via
 *       {@link Deployment#markKeycloakReady}.</li>
 * </ul>
 *
 * <p>KC failure in Phase B leaves the row at {@code PENDING}; the DB is
 * untouched and the retry loop completes when the directory recovers.
 */
@Service
public class DeploymentProvisioningService {

    private static final Logger LOG = LoggerFactory.getLogger(DeploymentProvisioningService.class);
    private static final String KEYCLOAK_USER_ATTRIBUTE_CLUB_ID = "clubId";

    private final DeploymentRepository deployments;
    private final ClubRepository clubs;
    private final ReferenceDataSeeder referenceDataSeeder;
    private final KeycloakDeploymentDirectory directory;
    private final Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    public DeploymentProvisioningService(DeploymentRepository deployments,
                                         ClubRepository clubs,
                                         ReferenceDataSeeder referenceDataSeeder,
                                         KeycloakDeploymentDirectory directory,
                                         Clock clock) {
        this.deployments = deployments;
        this.clubs = clubs;
        this.referenceDataSeeder = referenceDataSeeder;
        this.directory = directory;
        this.clock = clock;
    }

    /**
     * Phase A — the DB-half of provisioning. Runs inside the ingest
     * transaction; the caller is responsible for invoking
     * {@link #reconcileKeycloak} after commit (or letting the S-141 retry
     * job do it).
     *
     * <p>Throws {@link DeploymentExistsException} when the owner already
     * holds a non-terminal Deployment from a different ingest attempt
     * (the structural partial UNIQUE {@code ux_deployment_owner_active}
     * is the source of truth; the pre-check + flush exists so the
     * exception surfaces with the existing Deployment's identifiers
     * pre-populated for the 409 body).
     */
    @Transactional
    public ProvisioningResult provision(ProvisioningRequest request) {
        Objects.requireNonNull(request, "request");

        Optional<Deployment> alreadyProvisioned = deployments.findByIdempotencyKey(request.idempotencyKey());
        if (alreadyProvisioned.isPresent()) {
            return loadResult(alreadyProvisioned.get());
        }

        Optional<Deployment> existingOwnerActive = deployments.findActiveByOwner(request.ownerKeycloakSub());
        if (existingOwnerActive.isPresent()) {
            throw existsExceptionFor(existingOwnerActive.get());
        }

        Deployment deployment = Deployment.startTrial(
                clock, request.deploymentName(), request.ownerKeycloakSub());
        deployment.bindIdempotencyKey(request.idempotencyKey());
        Deployment saved;
        try {
            saved = deployments.save(deployment);
            entityManager.flush();
        } catch (DataIntegrityViolationException e) {
            Deployment racer = deployments.findActiveByOwner(request.ownerKeycloakSub())
                    .orElseThrow(() -> new IllegalStateException(
                            "Deployment INSERT raised a constraint violation but no active "
                                    + "Deployment found for owner " + request.ownerKeycloakSub(), e));
            throw existsExceptionFor(racer);
        }
        UUID deploymentId = Objects.requireNonNull(saved.getId(),
                "Deployment.id must be assigned after save");

        List<UUID> clubIds = new ArrayList<>(request.clubs().size());
        for (ClubSpec spec : request.clubs()) {
            Club club = Club.create(spec.name(), spec.slug(), spec.clubKey(),
                    spec.publicRegistrationEnabled(), spec.countryId(), spec.clubStateId(),
                    deploymentId);
            Club savedClub = clubs.save(club);
            ClubId clubIdWrapper = Objects.requireNonNull(savedClub.getId(),
                    "Club.id must be assigned after save");
            clubIds.add(clubIdWrapper.value());
        }
        entityManager.flush();

        for (UUID clubId : clubIds) {
            Tenants.runAs(clubId, () -> referenceDataSeeder.seedDefaults(clubId));
        }

        UUID primaryClubId = resolvePrimaryClubId(request, clubIds);

        // Funnel-telemetry placeholder per refinement; S-147 swaps in the
        // FunnelTelemetry.emit helper.
        LOG.info(
                "funnel event=deployment.provisioned deploymentId={} clubCount={} userId={}",
                deploymentId, clubIds.size(), request.ownerKeycloakSub());

        return new ProvisioningResult(deploymentId, clubIds, primaryClubId, true);
    }

    /**
     * Phase B — Keycloak reconcile. Idempotent: re-running against a
     * Deployment already in {@code kc_state = READY} is a no-op fast-exit.
     * On every successful path the call ends in
     * {@link Deployment#markKeycloakReady}.
     *
     * <p>Errors propagate to the caller (the orchestration layer +
     * S-141's retry job decide whether to log + leave the row PENDING or
     * surface to the operator).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reconcileKeycloak(UUID deploymentId) {
        Deployment deployment = deployments.findById(deploymentId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Deployment not found for reconcile: " + deploymentId));
        if (!deployment.isKeycloakPending()) {
            return;
        }
        UUID owner = Objects.requireNonNull(deployment.getOwnerKeycloakSub(),
                "Deployment.ownerKeycloakSub must be set after Phase A");

        UUID groupId = directory.findOrCreateDeploymentGroup(deploymentId);
        directory.addUserToGroupIfAbsent(owner, groupId);

        List<UUID> clubIds = clubs.findIdsByDeploymentId(deploymentId);
        for (UUID clubId : clubIds) {
            UUID roleId = directory.findOrCreateClubAdminRole(deploymentId, clubId);
            String roleName = clubAdminRoleName(deploymentId, clubId);
            directory.assignRoleIfAbsent(owner, roleId, roleName);
        }

        UUID primaryClubId = clubIds.isEmpty()
                ? null
                : clubIds.stream().min(Comparator.naturalOrder()).orElseThrow();
        if (primaryClubId != null) {
            directory.setUserAttribute(owner, KEYCLOAK_USER_ATTRIBUTE_CLUB_ID,
                    List.of(primaryClubId.toString()));
        }

        deployment.markKeycloakReady();
        deployments.save(deployment);
    }

    private ProvisioningResult loadResult(Deployment deployment) {
        UUID deploymentId = Objects.requireNonNull(deployment.getId(),
                "Persisted Deployment must carry an id");
        List<UUID> existingClubIds = clubs.findIdsByDeploymentId(deploymentId);
        UUID primaryClubId = existingClubIds.isEmpty()
                ? null
                : existingClubIds.stream().min(Comparator.naturalOrder()).orElseThrow();
        return new ProvisioningResult(deploymentId, existingClubIds, primaryClubId,
                deployment.isKeycloakPending());
    }

    private DeploymentExistsException existsExceptionFor(Deployment existing) {
        UUID deploymentId = Objects.requireNonNull(existing.getId(),
                "Existing Deployment must carry an id");
        List<UUID> existingClubIds = clubs.findIdsByDeploymentId(deploymentId);
        return new DeploymentExistsException(
                deploymentId, existing.getName(),
                existing.getLifecycleState(), existingClubIds);
    }

    private static UUID resolvePrimaryClubId(ProvisioningRequest request, List<UUID> clubIds) {
        UUID declared = request.primaryClubId();
        if (declared != null && clubIds.contains(declared)) {
            return declared;
        }
        // Deterministic fallback: lowest UUID. Matches the refinement's
        // legacy-single-tenant-assumption pin (manifest carries an
        // explicit primaryClubId once S-141 / S-183 wire it through).
        return clubIds.stream().min(Comparator.naturalOrder()).orElseThrow();
    }

    private static String clubAdminRoleName(UUID deploymentId, UUID clubId) {
        return "deployment-" + deploymentId + "-club-" + clubId + "-admin";
    }
}
