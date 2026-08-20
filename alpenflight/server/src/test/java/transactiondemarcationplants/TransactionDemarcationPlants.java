package transactiondemarcationplants;

import ch.alpenflight.platform.tenancy.Tenants;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

public final class TransactionDemarcationPlants {

    public static final String PINNED_NESTING_METHOD_NAME = "writeInsideTheClubScope";
    public static final String PINNED_WRITE_METHOD_NAME = "fileTheRequest";
    public static final String PINNED_SEED_RESOLVER_METHOD_NAME = "resolveTheSeed";

    @Component
    public static class PlantedTransactionalWriterBean {

        @Transactional
        public String fileTheRequest(UUID clubId) {
            return clubId.toString();
        }
    }

    public static class PlantedWriterWithNoSpringStereotype {

        @Transactional
        public String fileTheRequest(UUID clubId) {
            return clubId.toString();
        }
    }

    @Component
    public static class PlantedWriterThatLostItsTransactional {

        public String fileTheRequest(UUID clubId) {
            return clubId.toString();
        }
    }

    public static class PlantedTenantScopeOutsideTheTransactionTemplate {

        private final TransactionTemplate transactions = new TransactionTemplate();

        public String writeInsideTheClubScope(UUID clubId) {
            return Tenants.runAs(clubId,
                    () -> transactions.execute(status -> clubId.toString()));
        }
    }

    public static class PlantedTenantScopeOutsideATransactionalWriterBean {

        private final PlantedTransactionalWriterBean writer = new PlantedTransactionalWriterBean();

        public String writeInsideTheClubScope(UUID clubId) {
            return Tenants.runAs(clubId, () -> writer.fileTheRequest(clubId));
        }
    }

    public static class PlantedTransactionTemplateWrappedAroundTheTenantScope {

        private final TransactionTemplate transactions = new TransactionTemplate();

        public String writeInsideTheClubScope(UUID clubId) {
            return transactions.execute(
                    status -> Tenants.runAs(clubId, clubId::toString));
        }
    }

    public static class PlantedTenantScopeWithNoTransactionInside {

        public String writeInsideTheClubScope(UUID clubId) {
            return Tenants.runAs(clubId, clubId::toString);
        }
    }

    public static class PlantedMethodThatNoLongerOpensTheTenantScope {

        private final TransactionTemplate transactions = new TransactionTemplate();

        public String writeInsideTheClubScope(UUID clubId) {
            return transactions.execute(status -> clubId.toString());
        }
    }

    public static class PlantedTransactionalAnnotationAroundTheTenantScope {

        private final TransactionTemplate transactions = new TransactionTemplate();

        @Transactional
        public String writeInsideTheClubScope(UUID clubId) {
            return Tenants.runAs(clubId,
                    () -> transactions.execute(status -> clubId.toString()));
        }
    }

    public static class PlantedCallerHoldingTheInjectedWriterBean {

        private final PlantedTransactionalWriterBean writer;

        public PlantedCallerHoldingTheInjectedWriterBean(PlantedTransactionalWriterBean writer) {
            this.writer = writer;
        }

        public String writeInsideTheClubScope(UUID clubId) {
            return Tenants.runAs(clubId, () -> writer.fileTheRequest(clubId));
        }
    }

    public static class PlantedCallerThatBuildsTheWriterItself {

        private final PlantedTransactionalWriterBean writer = new PlantedTransactionalWriterBean();

        public String writeInsideTheClubScope(UUID clubId) {
            return Tenants.runAs(clubId, () -> writer.fileTheRequest(clubId));
        }
    }

    public static class PlantedCallerHoldingAWriterWithNoSpringStereotype {

        private final PlantedWriterWithNoSpringStereotype writer;

        public PlantedCallerHoldingAWriterWithNoSpringStereotype(
                PlantedWriterWithNoSpringStereotype writer) {
            this.writer = writer;
        }

        public String writeInsideTheClubScope(UUID clubId) {
            return Tenants.runAs(clubId, () -> writer.fileTheRequest(clubId));
        }
    }

    public static class PlantedCallerHoldingAWriterThatLostItsTransactional {

        private final PlantedWriterThatLostItsTransactional writer;

        public PlantedCallerHoldingAWriterThatLostItsTransactional(
                PlantedWriterThatLostItsTransactional writer) {
            this.writer = writer;
        }

        public String writeInsideTheClubScope(UUID clubId) {
            return Tenants.runAs(clubId, () -> writer.fileTheRequest(clubId));
        }
    }

    public static class PlantedCallerWithTheWriterInlinedAndCalledThroughThis {

        public String writeInsideTheClubScope(UUID clubId) {
            return Tenants.runAs(clubId, () -> fileTheRequest(clubId));
        }

        @Transactional
        public String fileTheRequest(UUID clubId) {
            return clubId.toString();
        }
    }

    public static class PlantedCallerThatCallsAnotherBeansTransactionalMethod {

        private final PlantedTransactionalWriterBean writer;

        public PlantedCallerThatCallsAnotherBeansTransactionalMethod(
                PlantedTransactionalWriterBean writer) {
            this.writer = writer;
        }

        public String writeInsideTheClubScope(UUID clubId) {
            return Tenants.runAs(clubId, () -> writer.fileTheRequest(clubId));
        }
    }

    public static class PlantedSeedResolverInAPostConstructCallback {

        private final EntityManager em;
        private final TransactionTemplate transactions;

        private UUID seedId = new UUID(0L, 0L);

        public PlantedSeedResolverInAPostConstructCallback(
                EntityManager em, TransactionTemplate transactions) {
            this.em = em;
            this.transactions = transactions;
        }

        @PostConstruct
        public void resolveTheSeed() {
            transactions.executeWithoutResult(status -> seedId = lookUpTheSeed());
        }

        public UUID seedId() {
            return seedId;
        }

        private UUID lookUpTheSeed() {
            return em.isOpen() ? UUID.randomUUID() : seedId;
        }
    }

    public static class PlantedSeedResolverWithNoPostConstruct {

        private final TransactionTemplate transactions;

        private UUID seedId = new UUID(0L, 0L);

        public PlantedSeedResolverWithNoPostConstruct(TransactionTemplate transactions) {
            this.transactions = transactions;
        }

        public void resolveTheSeed() {
            transactions.executeWithoutResult(status -> seedId = UUID.randomUUID());
        }

        public UUID seedId() {
            return seedId;
        }
    }

    public static class PlantedSeedResolverCalledFromTheConstructor {

        private final TransactionTemplate transactions;

        private UUID seedId = new UUID(0L, 0L);

        public PlantedSeedResolverCalledFromTheConstructor(TransactionTemplate transactions) {
            this.transactions = transactions;
            resolveTheSeed();
        }

        @PostConstruct
        public void resolveTheSeed() {
            transactions.executeWithoutResult(status -> seedId = UUID.randomUUID());
        }

        public UUID seedId() {
            return seedId;
        }
    }

    public static class PlantedSeedResolvedByAFieldInitializer {

        private final UUID seedId = lookUpTheSeedBeforeTheConstructorAssignsAnything();

        private final TransactionTemplate transactions;

        public PlantedSeedResolvedByAFieldInitializer(TransactionTemplate transactions) {
            this.transactions = transactions;
        }

        @PostConstruct
        public void resolveTheSeed() {
        }

        public UUID seedId() {
            return seedId;
        }

        private UUID lookUpTheSeedBeforeTheConstructorAssignsAnything() {
            transactions.executeWithoutResult(status -> { });
            return new UUID(0L, 0L);
        }
    }

    private TransactionDemarcationPlants() {
    }
}
