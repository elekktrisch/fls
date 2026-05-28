package ch.alpenflight.tenancy.provisioning.application;

import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.deployments.domain.Deployment;
import ch.alpenflight.deployments.domain.DeploymentRepository;
import ch.alpenflight.platform.id.ClubId;
import ch.alpenflight.platform.tenancy.Tenants;
import ch.alpenflight.tenancy.provisioning.domain.DeploymentExistsException;
import ch.alpenflight.tenancy.provisioning.domain.KeycloakDeploymentDirectory;
import ch.alpenflight.tenancy.provisioning.domain.KeycloakDeploymentNames;
import jakarta.persistence.EntityManager;
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
 * Provisions a trial Deployment + Clubs + per-Club reference data + the
 * Keycloak group / role / attribute plumbing for a freshly-signed-up
 * user on the first successful bundle ingest. Two-phase: the DB-half
 * commits in the caller's transaction; the directory-half runs
 * post-commit (either inline by the caller or by the hourly reconcile
 * job), is idempotent, and flips {@code kc_state} to READY on success.
 *
 * <p>Caller contract: invoke {@link #reconcileKeycloak} only after
 * {@link #provision} has committed (the directory-half is
 * {@code REQUIRES_NEW} so it self-contains its own commit; calling it
 * inside a still-open caller transaction would commit the reconcile
 * before the caller's outer transaction finishes, defeating the
 * post-commit ordering).
 */
@Service
public class DeploymentProvisioningService {

    private static final Logger LOG = LoggerFactory.getLogger(DeploymentProvisioningService.class);

    private final DeploymentRepository deployments;
    private final ClubRepository clubs;
    private final ReferenceDataSeeder referenceDataSeeder;
    private final KeycloakDeploymentDirectory directory;
    private final EntityManager entityManager;
    private final Clock clock;

    public DeploymentProvisioningService(DeploymentRepository deployments,
                                         ClubRepository clubs,
                                         ReferenceDataSeeder referenceDataSeeder,
                                         KeycloakDeploymentDirectory directory,
                                         EntityManager entityManager,
                                         Clock clock) {
        this.deployments = deployments;
        this.clubs = clubs;
        this.referenceDataSeeder = referenceDataSeeder;
        this.directory = directory;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /**
     * Materialises the DB state for a new trial Deployment, or short-
     * circuits to the existing Deployment when the idempotency key has
     * been seen before. Throws {@link DeploymentExistsException} when
     * the owner already holds a non-terminal Deployment from a different
     * attempt — the structural partial UNIQUE
     * {@code ux_deployment_owner_active} is the source of truth; the
     * pre-check + flush exists so the exception surfaces with the
     * existing Deployment's identifiers pre-populated for the 409 body.
     *
     * <p>On replay (same idempotency key), an owner-mismatch is treated
     * as a not-found-shaped error rather than a 200 — defense in depth
     * against a caller that forgets to re-assert the upload-vs-principal
     * binding.
     */
    @Transactional
    public ProvisioningResult provision(ProvisioningRequest request) {
        Objects.requireNonNull(request, "request");

        Optional<Deployment> alreadyProvisioned = deployments.findByIdempotencyKey(request.idempotencyKey());
        if (alreadyProvisioned.isPresent()) {
            Deployment existing = alreadyProvisioned.get();
            if (!request.ownerKeycloakSub().equals(existing.getOwnerKeycloakSub())) {
                // Reject without leaking the bound owner's identifiers.
                throw new IllegalStateException(
                        "Idempotency key already bound to a different owner");
            }
            return loadResult(existing);
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
        } catch (DataIntegrityViolationException raceLost) {
            Deployment racer = deployments.findActiveByOwner(request.ownerKeycloakSub())
                    .orElseThrow(() -> new IllegalStateException(
                            "Deployment INSERT raised a constraint violation but no active "
                                    + "Deployment found for owner " + request.ownerKeycloakSub(), raceLost));
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

        // Funnel-telemetry placeholder; the dedicated funnel emitter
        // replaces this log line once it lands. Logged shape matches the
        // security plan: deploymentId + clubCount + plan only — no
        // operator display name, no per-Club names.
        LOG.info(
                "funnel event=deployment.provisioned deploymentId={} clubCount={} plan={}",
                deploymentId, clubIds.size(), saved.getPlan());

        return new ProvisioningResult(deploymentId, clubIds, primaryClubId, true);
    }

    /**
     * Idempotent directory-side reconcile. Runs in its own transaction so
     * a failure does not roll back the provisioning commit; the caller
     * MUST have committed {@link #provision} before invoking this.
     * Re-running against a Deployment already in
     * {@code kc_state = READY} short-circuits to a no-op.
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
                "Deployment.ownerKeycloakSub must be set after provisioning");

        UUID groupId = directory.findOrCreateDeploymentGroup(deploymentId);
        directory.addUserToGroupIfAbsent(owner, groupId);

        List<UUID> clubIds = clubs.findIdsByDeploymentId(deploymentId);
        for (UUID clubId : clubIds) {
            UUID roleId = directory.findOrCreateClubAdminRole(deploymentId, clubId);
            String roleName = KeycloakDeploymentNames.clubAdminRoleName(deploymentId, clubId);
            directory.assignRoleIfAbsent(owner, roleId, roleName);
        }

        UUID primaryClubId = clubIds.isEmpty()
                ? null
                : clubIds.stream().min(Comparator.naturalOrder()).orElseThrow();
        if (primaryClubId != null) {
            directory.setUserAttribute(owner,
                    KeycloakDeploymentNames.CLUB_ID_USER_ATTRIBUTE,
                    List.of(primaryClubId.toString()));
        }

        deployment.markKeycloakReady();
        // Hibernate dirty-checks the managed entity on commit; no
        // explicit save needed.
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
        // Deterministic fallback: lowest UUID — matches the legacy
        // single-tenant assumption until the ingest pipeline plumbs an
        // explicit manifest primary through.
        return clubIds.stream().min(Comparator.naturalOrder()).orElseThrow();
    }
}
