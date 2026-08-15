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

    @Transactional
    public ProvisioningResult provision(ProvisioningRequest request) {
        Objects.requireNonNull(request, "request");

        Optional<Deployment> alreadyProvisioned = deployments.findByIdempotencyKey(request.idempotencyKey());
        if (alreadyProvisioned.isPresent()) {
            Deployment existing = alreadyProvisioned.get();
            if (!request.ownerKeycloakSub().equals(existing.getOwnerKeycloakSub())) {
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

        LOG.info(
                "funnel event=deployment.provisioned deploymentId={} clubCount={} plan={}",
                deploymentId, clubIds.size(), saved.getPlan());

        return new ProvisioningResult(deploymentId, clubIds, primaryClubId, true);
    }

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
    }

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

    static final String MIGRATED_ADMIN_FIRST_NAME = "Migrated";

    static final String MIGRATED_ADMIN_LAST_NAME = "Admin";

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
        return clubIds.stream().min(Comparator.naturalOrder()).orElseThrow();
    }
}
