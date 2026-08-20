package nontransactionalbydesignplants;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

public final class NonTransactionalByDesignPlants {

    public static final String PINNED_METHOD_NAME = "readsInsideTenantsRunAs";

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.TYPE})
    @Transactional
    public @interface PlantedComposedTransactionAnnotation {
    }

    public static class PlantedPinnedMethodWithAMethodLevelTransactional {

        @Transactional
        public void readsInsideTenantsRunAs() {
        }
    }

    @Transactional
    public static class PlantedPinnedMethodInheritingAClassLevelTransactional {

        public void readsInsideTenantsRunAs() {
        }
    }

    public static class PlantedPinnedMethodWithANonDefaultPropagation {

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void readsInsideTenantsRunAs() {
        }
    }

    public static class PlantedPinnedMethodWithAComposedTransactionAnnotation {

        @PlantedComposedTransactionAnnotation
        public void readsInsideTenantsRunAs() {
        }
    }

    @PlantedComposedTransactionAnnotation
    public static class PlantedPinnedMethodInheritingAComposedTransactionAnnotation {

        public void readsInsideTenantsRunAs() {
        }
    }

    public static class PlantedPinnedMethodWithTheJakartaTransactional {

        @jakarta.transaction.Transactional
        public void readsInsideTenantsRunAs() {
        }
    }

    public static class PlantedPinnedMethodBesideATransactionalSibling {

        public void readsInsideTenantsRunAs() {
        }

        @Transactional(readOnly = true)
        public void pendingForCurrentTenant() {
        }
    }

    public static class PlantedPinnedCallee {

        public void readsInsideTenantsRunAs() {
        }
    }

    @Transactional
    public static class PlantedTransactionalCallerOfThePinnedMethod {

        private final PlantedPinnedCallee callee = new PlantedPinnedCallee();

        public void handle() {
            callee.readsInsideTenantsRunAs();
        }
    }

    public static class PlantedTransactionTemplateWrapperOfThePinnedMethod {

        private final PlantedPinnedCallee callee = new PlantedPinnedCallee();

        private final TransactionTemplate transactions = new TransactionTemplate();

        public void handle() {
            transactions.executeWithoutResult(status -> callee.readsInsideTenantsRunAs());
        }
    }

    public static class PlantedNonTransactionalCallerOfThePinnedMethod {

        private final PlantedPinnedCallee callee = new PlantedPinnedCallee();

        public void handle() {
            callee.readsInsideTenantsRunAs();
        }
    }

    private NonTransactionalByDesignPlants() {
    }
}
