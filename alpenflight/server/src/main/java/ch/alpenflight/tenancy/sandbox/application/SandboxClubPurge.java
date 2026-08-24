package ch.alpenflight.tenancy.sandbox.application;

import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.deployments.domain.Deployment;
import ch.alpenflight.platform.tenancy.Tenants;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.hibernate.annotations.TenantId;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class SandboxClubPurge {

    public static final class ClubOutsideTheSandboxDeploymentException extends RuntimeException {

        public ClubOutsideTheSandboxDeploymentException(UUID clubId) {
            super("club " + clubId + " is not bound to the sandbox deployment "
                    + Deployment.SANDBOX_ID
                    + ", so the sandbox purge refuses to delete a single row of it");
        }
    }

    public static final class TenantScopedEntityOutsideThePurgeException extends RuntimeException {

        public TenantScopedEntityOutsideThePurgeException(Set<String> entityNames) {
            super("the tenant-scoped entities " + entityNames + " are neither deleted by the "
                    + "sandbox purge nor named in "
                    + "APPEND_ONLY_ENTITIES_THE_APP_DATABASE_ROLE_MAY_NOT_DELETE_FROM; a demo "
                    + "visitor writes rows that the seat reclaim would then leave behind");
        }
    }

    record ClubScopedDelete(String entityName, String jpql) {

        static ClubScopedDelete ofTheClubAttribute(String entityName, String clubAttribute) {
            return new ClubScopedDelete(entityName,
                    "delete from " + entityName + " scoped where scoped." + clubAttribute
                            + " = :clubId");
        }
    }

    static final Set<String> APPEND_ONLY_ENTITIES_THE_APP_DATABASE_ROLE_MAY_NOT_DELETE_FROM =
            Set.of("MutationAuditEvent");

    static final String MEMBERS_THE_SEAT_CLUB_SHARES_WITH_NO_OTHER_CLUB =
            "delete from Person member where member.id in ("
                    + "select owned.personId from PersonClubMembershipOutsideTheTenantFilter owned "
                    + "where owned.clubId = :clubId and not exists ("
                    + "select 1 from PersonClubMembershipOutsideTheTenantFilter elsewhere "
                    + "where elsewhere.personId = owned.personId "
                    + "and elsewhere.clubId <> :clubId))";

    static final List<ClubScopedDelete> DELETES_IN_FOREIGN_KEY_SAFE_ORDER = List.of(
            ClubScopedDelete.ofTheClubAttribute("FlightReportRow", "operatingClubId"),
            ClubScopedDelete.ofTheClubAttribute("DeliveryCreationTest", "operatingClubId"),
            ClubScopedDelete.ofTheClubAttribute("Delivery", "operatingClubId"),
            ClubScopedDelete.ofTheClubAttribute("Article", "operatingClubId"),
            ClubScopedDelete.ofTheClubAttribute("Flight", "operatingClubId"),
            ClubScopedDelete.ofTheClubAttribute("AircraftReservation", "operatingClubId"),
            ClubScopedDelete.ofTheClubAttribute("AircraftReservationType", "operatingClubId"),
            ClubScopedDelete.ofTheClubAttribute("PlanningDay", "operatingClubId"),
            ClubScopedDelete.ofTheClubAttribute("PlanningDayAssignmentType", "operatingClubId"),
            ClubScopedDelete.ofTheClubAttribute("DiscoveryFlightDay", "clubId"),
            ClubScopedDelete.ofTheClubAttribute("EmailTemplate", "clubId"),
            ClubScopedDelete.ofTheClubAttribute("JoinRequest", "clubId"),
            ClubScopedDelete.ofTheClubAttribute("AccountingRuleFilter", "operatingClubId"),
            new ClubScopedDelete("Person", MEMBERS_THE_SEAT_CLUB_SHARES_WITH_NO_OTHER_CLUB),
            ClubScopedDelete.ofTheClubAttribute("PersonClub", "clubId"),
            ClubScopedDelete.ofTheClubAttribute("Aircraft", "managingClubId"),
            ClubScopedDelete.ofTheClubAttribute("Location", "clubId"),
            ClubScopedDelete.ofTheClubAttribute("FlightType", "operatingClubId"),
            ClubScopedDelete.ofTheClubAttribute("MemberState", "clubId"));

    private final EntityManager entityManager;
    private final ClubRepository clubs;
    private final TransactionTemplate oneTransactionOpenedInsideTheSeatTenant;

    public SandboxClubPurge(EntityManager entityManager,
                            EntityManagerFactory entityManagerFactory,
                            ClubRepository clubs,
                            PlatformTransactionManager transactionManager) {
        this.entityManager = entityManager;
        this.clubs = clubs;
        this.oneTransactionOpenedInsideTheSeatTenant =
                new TransactionTemplate(transactionManager);
        this.oneTransactionOpenedInsideTheSeatTenant.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        requireEveryTenantScopedEntityIsDeletedOrDeliberatelyKept(
                tenantScopedEntityNamesOf(entityManagerFactory),
                DELETES_IN_FOREIGN_KEY_SAFE_ORDER.stream().map(ClubScopedDelete::entityName)
                        .collect(Collectors.toUnmodifiableSet()),
                APPEND_ONLY_ENTITIES_THE_APP_DATABASE_ROLE_MAY_NOT_DELETE_FROM);
    }

    public int deleteEveryRowOf(UUID seatClubId) {
        requireTheClubIsBoundToTheSandboxDeployment(seatClubId);
        Integer deleted = Tenants.runAs(seatClubId,
                () -> oneTransactionOpenedInsideTheSeatTenant.execute(status -> {
                    int rows = 0;
                    for (ClubScopedDelete step : DELETES_IN_FOREIGN_KEY_SAFE_ORDER) {
                        rows += entityManager.createQuery(step.jpql())
                                .setParameter("clubId", seatClubId)
                                .executeUpdate();
                    }
                    entityManager.clear();
                    return rows;
                }));
        if (deleted == null) {
            throw new IllegalStateException("the sandbox purge transaction returned no result");
        }
        return deleted;
    }

    private void requireTheClubIsBoundToTheSandboxDeployment(UUID seatClubId) {
        if (seatClubId == null) {
            throw new IllegalArgumentException("seatClubId must not be null");
        }
        if (!clubs.findIdsByDeploymentId(Deployment.SANDBOX_ID).contains(seatClubId)) {
            throw new ClubOutsideTheSandboxDeploymentException(seatClubId);
        }
    }

    static Set<String> tenantScopedEntityNamesOf(EntityManagerFactory entityManagerFactory) {
        Set<String> names = new TreeSet<>();
        for (EntityType<?> entity : entityManagerFactory.getMetamodel().getEntities()) {
            if (carriesATenantDiscriminator(entity.getJavaType())) {
                names.add(entity.getName());
            }
        }
        return Set.copyOf(names);
    }

    private static boolean carriesATenantDiscriminator(Class<?> entityClass) {
        for (Class<?> type = entityClass; type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.isAnnotationPresent(TenantId.class)) {
                    return true;
                }
            }
        }
        return false;
    }

    static void requireEveryTenantScopedEntityIsDeletedOrDeliberatelyKept(
            Set<String> tenantScopedEntityNames,
            Set<String> entityNamesThePurgeDeletes,
            Set<String> entityNamesThePurgeKeeps) {
        List<String> uncovered = new ArrayList<>();
        for (String tenantScoped : new TreeSet<>(tenantScopedEntityNames)) {
            if (!entityNamesThePurgeDeletes.contains(tenantScoped)
                    && !entityNamesThePurgeKeeps.contains(tenantScoped)) {
                uncovered.add(tenantScoped);
            }
        }
        if (!uncovered.isEmpty()) {
            throw new TenantScopedEntityOutsideThePurgeException(Set.copyOf(uncovered));
        }
    }
}
