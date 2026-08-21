package ch.alpenflight.arch;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.properties.HasAnnotations;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@AnalyzeClasses(
        packages = "ch.alpenflight",
        importOptions = {ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeJars.class})
class NonTransactionalByDesignGuardTest {

    private static final Set<String> TRANSACTION_DEMARCATING_ANNOTATION_NAMES = Set.of(
            "org.springframework.transaction.annotation.Transactional",
            "jakarta.transaction.Transactional");

    private static final Set<String> PROGRAMMATIC_TRANSACTION_STARTER_NAMES = Set.of(
            "org.springframework.transaction.support.TransactionTemplate",
            "org.springframework.transaction.support.TransactionOperations");

    static final String WHY_LATEST_FOR_CALLER_MUST_STAY_NON_TRANSACTIONAL =
            "JoinRequestsService.latestForCaller must stay non-transactional. A transaction opens "
                    + "the Hibernate session before the method body runs, and Hibernate fixes the "
                    + "session tenant at session-open from ClubTenantIdentifierResolver. The caller "
                    + "is a person who joined no club yet, so the resolver returns NO_TENANT and "
                    + "the session keeps NO_TENANT for its whole life. The read inside "
                    + "Tenants.runAs(clubId, ...) then matches no row, and the screen tells the "
                    + "applicant that no request exists. Without a transaction, each repository "
                    + "call opens its own session, and the call inside Tenants.runAs opens with "
                    + "the club bound. spring.jpa.open-in-view is false, so no earlier session "
                    + "exists on the request thread.";

    static final String WHY_RUN_FOR_CURRENT_CLUB_MUST_STAY_NON_TRANSACTIONAL =
            "DailyFlightValidationJob.runForCurrentClub must stay non-transactional. "
                    + "FlightStateTransitionService carries a class-level @Transactional, so each "
                    + "flight transition runs in its own transaction, and the two passes catch "
                    + "RuntimeException per flight and continue. A transaction here makes the "
                    + "per-flight transitions join it. The first failed flight marks the shared "
                    + "transaction rollback-only, the catch hides the failure, and the commit "
                    + "throws UnexpectedRollbackException. One bad flight then discards every "
                    + "flight that the club validated and locked in the same run.";

    static final String WHY_ON_LIFECYCLE_TRANSITION_MUST_STAY_NON_TRANSACTIONAL =
            "LifecycleTransitionAuditListener.onLifecycleTransition must stay a plain "
                    + "@EventListener. Spring Data publishes the @DomainEvents of Deployment "
                    + "inside the transaction of DeploymentsAdminService.transitionLifecycle, and "
                    + "the listener must run inline in that transaction, because "
                    + "AuditTrailService.build reads the actor, the tenant and the requestId from "
                    + "thread-local state at call time. MutationAuditEventListener already defers "
                    + "the write with @TransactionalEventListener(AFTER_COMMIT) plus "
                    + "@Transactional(REQUIRES_NEW). A REQUIRES_NEW transaction here commits the "
                    + "audit row on the inner commit, so a rolled-back transition keeps its audit "
                    + "row. A @TransactionalEventListener or an @Async here defers the record call "
                    + "past that thread-local boundary, so the row loses its actor or never "
                    + "reaches the table. A joining @Transactional changes no behaviour today, and "
                    + "this guard still refuses it, because it is the door to both harmful forms.";

    static final String RESIDUAL_LIMIT_THIS_GUARD_DOES_NOT_COVER =
            " Residual limit — the annotation rule reads annotations, not effective behaviour. It "
                    + "covers a method-level @Transactional, a class-level @Transactional that the "
                    + "method inherits, any propagation value, a Spring meta-annotation composed "
                    + "over @Transactional, and jakarta.transaction.Transactional. The caller rule "
                    + "closes the caller side coarsely: it treats a class as transaction-bearing "
                    + "when the class or any of its code units carries @Transactional, or when the "
                    + "class calls TransactionTemplate or TransactionOperations. That "
                    + "over-approximates, so a red can name a caller that opens its transaction on "
                    + "another path. Two gaps stay reachable and no rule here catches them: a "
                    + "transaction that a further-out caller opened and passed down through a "
                    + "non-annotated intermediate class, and a programmatic transaction that "
                    + "another class starts on the same thread before the call. Read the whole "
                    + "call chain, not only the annotation.";

    record MethodPinnedNonTransactional(String declaringClassFullName, String methodName) {
    }

    static final Map<MethodPinnedNonTransactional, String> METHODS_PINNED_NON_TRANSACTIONAL =
            Map.of(
                    new MethodPinnedNonTransactional(
                            "ch.alpenflight.joinrequests.application.JoinRequestsService",
                            "latestForCaller"),
                    WHY_LATEST_FOR_CALLER_MUST_STAY_NON_TRANSACTIONAL,
                    new MethodPinnedNonTransactional(
                            "ch.alpenflight.flights.application.DailyFlightValidationJob",
                            "runForCurrentClub"),
                    WHY_RUN_FOR_CURRENT_CLUB_MUST_STAY_NON_TRANSACTIONAL,
                    new MethodPinnedNonTransactional(
                            "ch.alpenflight.deployments.application.LifecycleTransitionAuditListener",
                            "onLifecycleTransition"),
                    WHY_ON_LIFECYCLE_TRANSITION_MUST_STAY_NON_TRANSACTIONAL);

    static final Map<MethodPinnedNonTransactional, String>
            METHODS_A_CALLER_OPENED_TRANSACTION_ALSO_BREAKS = pinsWithoutTheInlineEventListener();

    @ArchTest
    static final ArchRule the_methods_pinned_non_transactional_carry_no_transactional_annotation =
            methodsPinnedNonTransactionalCarryNoTransactionalAnnotation(
                    METHODS_PINNED_NON_TRANSACTIONAL);

    @ArchTest
    static final ArchRule no_transaction_bearing_class_calls_a_method_pinned_non_transactional =
            noTransactionBearingClassCallsAMethodPinnedNonTransactional(
                    METHODS_A_CALLER_OPENED_TRANSACTION_ALSO_BREAKS);

    static ArchRule methodsPinnedNonTransactionalCarryNoTransactionalAnnotation(
            Map<MethodPinnedNonTransactional, String> pinnedMethodsAndWhy) {
        return methods()
                .that(describe("are pinned non-transactional by design",
                        (JavaMethod method) -> pinnedMethodsAndWhy.containsKey(pinOf(method))))
                .should(carryNoTransactionalAnnotation(pinnedMethodsAndWhy))
                .as("A method pinned non-transactional by design must carry no @Transactional, "
                        + "and its declaring class must carry none either. Each violation below "
                        + "names the behaviour that a transaction breaks."
                        + RESIDUAL_LIMIT_THIS_GUARD_DOES_NOT_COVER)
                .allowEmptyShould(true);
    }

    static ArchRule noTransactionBearingClassCallsAMethodPinnedNonTransactional(
            Map<MethodPinnedNonTransactional, String> pinnedMethodsAndWhy) {
        return classes()
                .that(describe("open or join a transaction",
                        NonTransactionalByDesignGuardTest::bearsATransaction))
                .should(callNoMethodPinnedNonTransactional(pinnedMethodsAndWhy))
                .as("A class that opens or joins a transaction must not call a method pinned "
                        + "non-transactional by design. The callee then runs inside a transaction "
                        + "although it carries no annotation of its own, which is the same failure "
                        + "with no annotation to read."
                        + RESIDUAL_LIMIT_THIS_GUARD_DOES_NOT_COVER)
                .allowEmptyShould(true);
    }

    private static Map<MethodPinnedNonTransactional, String> pinsWithoutTheInlineEventListener() {
        Map<MethodPinnedNonTransactional, String> pins =
                new LinkedHashMap<>(METHODS_PINNED_NON_TRANSACTIONAL);
        pins.remove(new MethodPinnedNonTransactional(
                "ch.alpenflight.deployments.application.LifecycleTransitionAuditListener",
                "onLifecycleTransition"));
        return Map.copyOf(pins);
    }

    private static ArchCondition<JavaMethod> carryNoTransactionalAnnotation(
            Map<MethodPinnedNonTransactional, String> pinnedMethodsAndWhy) {
        return new ArchCondition<>("carry no @Transactional on the method, on the declaring class, "
                + "or through a meta-annotation composed over @Transactional") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                String why = pinnedMethodsAndWhy.getOrDefault(pinOf(method), "");
                transactionalAnnotationOn(method).ifPresent(found -> events.add(
                        SimpleConditionEvent.violated(method,
                                method.getFullName() + " carries " + found
                                        + " on the method itself. " + why)));
                transactionalAnnotationOn(method.getOwner()).ifPresent(found -> events.add(
                        SimpleConditionEvent.violated(method,
                                method.getFullName() + " inherits " + found
                                        + " from its declaring class "
                                        + method.getOwner().getSimpleName() + ". " + why)));
            }
        };
    }

    private static ArchCondition<JavaClass> callNoMethodPinnedNonTransactional(
            Map<MethodPinnedNonTransactional, String> pinnedMethodsAndWhy) {
        return new ArchCondition<>("call no method that is pinned non-transactional by design") {
            @Override
            public void check(JavaClass caller, ConditionEvents events) {
                for (JavaMethodCall call : caller.getMethodCallsFromSelf()) {
                    MethodPinnedNonTransactional called = new MethodPinnedNonTransactional(
                            call.getTargetOwner().getFullName(), call.getTarget().getName());
                    String why = pinnedMethodsAndWhy.get(called);
                    if (why != null) {
                        events.add(SimpleConditionEvent.violated(caller,
                                caller.getName() + " opens or joins a transaction and calls "
                                        + call.getTargetOwner().getSimpleName() + "."
                                        + call.getTarget().getName() + " at "
                                        + call.getSourceCodeLocation() + ". " + why));
                    }
                }
            }
        };
    }

    private static boolean bearsATransaction(JavaClass candidate) {
        if (transactionalAnnotationOn(candidate).isPresent()) {
            return true;
        }
        for (JavaCodeUnit codeUnit : candidate.getCodeUnits()) {
            if (transactionalAnnotationOn(codeUnit).isPresent()) {
                return true;
            }
        }
        return candidate.getMethodCallsFromSelf().stream()
                .anyMatch(call -> PROGRAMMATIC_TRANSACTION_STARTER_NAMES
                        .contains(call.getTargetOwner().getFullName()));
    }

    static Optional<String> transactionalAnnotationOn(HasAnnotations<?> annotated) {
        for (JavaAnnotation<?> annotation : annotated.getAnnotations()) {
            if (TRANSACTION_DEMARCATING_ANNOTATION_NAMES
                    .contains(annotation.getRawType().getFullName())) {
                return Optional.of(renderedWithPropagation(annotation));
            }
        }
        for (JavaAnnotation<?> annotation : annotated.getAnnotations()) {
            if (composesATransactionDemarcation(annotation.getRawType())) {
                return Optional.of("@" + annotation.getRawType().getSimpleName()
                        + ", a meta-annotation composed over @Transactional");
            }
        }
        return Optional.empty();
    }

    private static boolean composesATransactionDemarcation(JavaClass annotationType) {
        return TRANSACTION_DEMARCATING_ANNOTATION_NAMES.stream()
                .anyMatch(demarcation -> annotationType.isAnnotatedWith(demarcation)
                        || annotationType.isMetaAnnotatedWith(demarcation));
    }

    private static String renderedWithPropagation(JavaAnnotation<?> annotation) {
        Object propagation = annotation.getProperties().get("propagation");
        return "@" + annotation.getRawType().getSimpleName()
                + (propagation == null ? "" : "(propagation = " + propagation + ")");
    }

    private static MethodPinnedNonTransactional pinOf(JavaMethod method) {
        return new MethodPinnedNonTransactional(
                method.getOwner().getFullName(), method.getName());
    }
}
