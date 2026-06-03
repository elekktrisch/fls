package ch.alpenflight.tenancy.provisioning.application;

import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.deployments.domain.Deployment;
import ch.alpenflight.deployments.domain.DeploymentRepository;
import ch.alpenflight.platform.id.ClubId;
import ch.alpenflight.platform.tenancy.Tenants;
import ch.alpenflight.tenancy.provisioning.domain.DeploymentExistsException;
import ch.alpenflight.tenancy.provisioning.domain.IdempotencyOwnerMismatchException;
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
                // Surfacing the bound owner's sub here would confirm to a
                // caller racing idempotency keys that one is in flight;
                // 404 is the agreed shape for "I don't know this key".
                throw new IdempotencyOwnerMismatchException();
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

        // Funnel signal. Field set is the security-plan minimum:
        // deploymentId + clubCount + plan only — never the operator's
        // display name or any per-Club name.
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

    /**
     * J-0c provision-on-migrate slice (a thin slice of S-028, NOT the
     * full story). For each migrated Club, mints a fresh loginable
     * Keycloak club-admin identity carrying that Club's id as the
     * tenant user-attribute + the {@code CLUB_ADMINISTRATOR} realm role +
     * {@code UPDATE_PASSWORD} required action. A later real Keycloak login
     * lands in that Club and {@code JitUserMaterializer} (S-169) projects
     * the {@code t_user} — no legacy User row crosses over (C14), so none
     * is seeded here.
     *
     * <p>Username/email are deterministic per Club so a replayed migrate
     * resolves the existing identity rather than colliding (the directory
     * port is idempotent on username).
     *
     * @return the directory subs of the provisioned club admins, in
     *     {@code clubIds} order.
     */
    public List<UUID> provisionMigratedClubAdmins(List<UUID> clubIds) {
        Objects.requireNonNull(clubIds, "clubIds");
        List<UUID> subs = new ArrayList<>(clubIds.size());
        for (UUID clubId : clubIds) {
            String username = migratedClubAdminUsername(clubId);
            subs.add(directory.provisionClubAdminIdentity(clubId, username, username,
                    MIGRATED_ADMIN_FIRST_NAME, MIGRATED_ADMIN_LAST_NAME));
        }
        return subs;
    }

    /**
     * Synthetic given/family name stamped on a migrated club admin's
     * Keycloak user. The migrated admin is a per-Club <em>service identity</em>
     * (synthetic username {@link #migratedClubAdminUsername}, non-routable
     * email, {@code UPDATE_PASSWORD} on first login), NOT a projection of a
     * legacy Person row — and the Person streams don't drain until after
     * this provisioning runs ({@code MigrationBundleIngestService}), so no
     * real name is available here. These names exist only to satisfy the
     * realm's declarative user-profile so Keycloak's {@code VERIFY_PROFILE}
     * required action does not fire on first login (J-1 T-06). The operator
     * edits the display name once a real human owns the identity.
     */
    static final String MIGRATED_ADMIN_FIRST_NAME = "Migrated";

    static final String MIGRATED_ADMIN_LAST_NAME = "Admin";

    /**
     * Deterministic synthetic identity for a migrated Club admin. The
     * {@code +<clubId>} tag keys it to the provisioned Club so a replayed
     * migrate resolves the same user; {@code @migrated.alpenflight.local}
     * is a non-routable sentinel domain (migrated admins set their
     * password via {@code UPDATE_PASSWORD} on first login, no mail is
     * sent — S-082 is out of this slice's scope).
     */
    static String migratedClubAdminUsername(UUID clubId) {
        return "migrated-admin+" + clubId + "@migrated.alpenflight.local";
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
