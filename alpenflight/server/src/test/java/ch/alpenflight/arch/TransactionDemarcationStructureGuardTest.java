package ch.alpenflight.arch;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@AnalyzeClasses(
        packages = "ch.alpenflight",
        importOptions = {ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeJars.class})
class TransactionDemarcationStructureGuardTest {

    private static final String TENANTS_FULL_NAME = "ch.alpenflight.platform.tenancy.Tenants";
    private static final String RUN_AS_METHOD_NAME = "runAs";
    private static final String SPRING_COMPONENT_FULL_NAME =
            "org.springframework.stereotype.Component";
    private static final String POST_CONSTRUCT_FULL_NAME = "jakarta.annotation.PostConstruct";
    private static final String ENTITY_MANAGER_FULL_NAME = "jakarta.persistence.EntityManager";
    private static final String OBJECT_PROVIDER_FULL_NAME =
            "org.springframework.beans.factory.ObjectProvider";

    private static final Set<String> PROGRAMMATIC_TRANSACTION_STARTER_OWNERS = Set.of(
            "org.springframework.transaction.support.TransactionTemplate",
            "org.springframework.transaction.support.TransactionOperations");

    static final String WHY_THE_FLIGHT_REPORT_REBUILD_OPENS_THE_TENANT_SCOPE_FIRST =
            "FlightReportRebuildService.rebuildForClub must call Tenants.runAs first, and must "
                    + "open the TransactionTemplate inside that call. Hibernate fixes the tenant "
                    + "of a session when it opens the session. It reads the tenant from "
                    + "ClubTenantIdentifierResolver at that moment. A transaction opens the "
                    + "session before the lambda body runs. The rebuild runs on a job thread with "
                    + "no security context, so the resolver returns NO_TENANT. Flight and "
                    + "FlightReportRow carry @TenantId, so every read inside the transaction then "
                    + "matches no row. The rebuild reports zero live flights, deletes no orphan "
                    + "row, and refreshes nothing. The swapped order reds all three cases of "
                    + "FlightReportRebuildServiceIT.";

    static final String WHY_THE_JOIN_REQUEST_WRITE_OPENS_THE_TENANT_SCOPE_FIRST =
            "JoinRequestsService.submit and JoinRequestsService.withdraw must call Tenants.runAs "
                    + "first, and must reach the transactional writer inside that call. The "
                    + "applicant belongs to no club, so ClubTenantIdentifierResolver returns "
                    + "NO_TENANT for that caller. A transaction that opens before Tenants.runAs "
                    + "binds the session to NO_TENANT for the whole life of the session. "
                    + "JoinRequest carries @TenantId. The insert then stamps the NO_TENANT "
                    + "sentinel, and the club never sees the request. "
                    + "FlightReportRebuildServiceIT measures the same mechanism on the flight "
                    + "read model.";

    static final String WHY_THE_JOIN_REQUEST_WRITER_STAYS_A_SEPARATE_PROXIED_BEAN =
            "JoinRequestTxWriter must stay a separate Spring bean, and must keep @Transactional "
                    + "on its write methods. Spring opens the transaction in a proxy around the "
                    + "bean. JoinRequestsService receives that proxy, so the call from inside the "
                    + "Tenants.runAs lambda crosses the proxy and opens the transaction at the "
                    + "correct depth. Inline the writer into JoinRequestsService and the call "
                    + "becomes a self-call through this. A self-call never crosses the proxy, so "
                    + "Spring opens no transaction. The audit row and the "
                    + "JoinRequestStatusChangedEvent then leave the write, and a later failure "
                    + "keeps a partial write.";

    static final String WHY_THE_FLIGHT_SEED_RESOLVES_IN_A_POST_CONSTRUCT_CALLBACK =
            "FlightInitialState.resolveSeeds must keep @PostConstruct. No other code calls "
                    + "resolveSeeds. Remove the annotation and initialProcessStateId keeps the "
                    + "all-zero sentinel UUID. The context still starts, so the boot gives no "
                    + "warning. Every flight create then stamps a process-state id that no row "
                    + "carries. Measured: the removal starts the context, and reds five cases of "
                    + "FlightProcessStatePatchIT that get 500 in place of 201.";

    static final String WHY_THE_FLIGHT_SEED_LOOKUP_STAYS_OUT_OF_THE_CONSTRUCTOR =
            "FlightInitialState must not resolve the seed from its constructor. A field "
                    + "initializer and a constructor statement compile to the same <init> code "
                    + "unit, so this rule refuses both. The field-initializer form runs before "
                    + "the constructor assigns em and tx, so it throws NullPointerException at "
                    + "bean creation.";

    static final String CORRECTION_THE_DELETED_COMMENT_GAVE_AN_UNSUPPORTED_REASON =
            " Correction — the deleted comment gave a reason that the code does not support. It "
                    + "said the EntityManager needs a bound JDBC session, and that the ResultSet "
                    + "closes before the read. The Spring shared-EntityManager proxy defers the "
                    + "close until the terminal query call, so a lookup without a transaction "
                    + "works. Measured: a lookup without the TransactionTemplate, and a lookup "
                    + "inside the constructor body, both start the context green against real "
                    + "Postgres. The TransactionTemplate is not load-bearing here. Do not restore "
                    + "that reason. This rule pins the lifecycle callback, not the transaction.";

    static final String RESIDUAL_LIMIT_THESE_RULES_DO_NOT_COVER =
            " Residual limit — these rules read bytecode structure, not runtime behaviour. The "
                    + "nesting rule reads the direct call sites of the pinned method, and marks "
                    + "each site as inside or outside a lambda. It does not follow a call into a "
                    + "helper method, so a helper that opens the transaction both hides the shape "
                    + "and reds the rule. It cannot see a transaction that a caller opened "
                    + "further out, or that another class started on the same thread. The "
                    + "self-call rule covers every production class. It reads the declared call "
                    + "target, so it catches this.method(), a call on a field of the same class, "
                    + "and a call inside a lambda body. It cannot see a self-call routed through a "
                    + "method reference: javac compiles this::method to an invokedynamic "
                    + "instruction, and ArchUnit records that as a method reference, not as a "
                    + "method call. Measured: the rule stays green on a planted "
                    + "this::transactionalMethod. It reads the @Transactional of the target from "
                    + "the method or from the declaring class, and it passes over three shapes "
                    + "that open no new hole: a call whose source line also reaches ObjectProvider, "
                    + "which does cross the proxy; a call from a compiler-generated bridge method; "
                    + "and a call whose origin already runs inside the same boundary, either "
                    + "because the origin carries its own @Transactional or because a class-level "
                    + "@Transactional covers origin and target alike. The ObjectProvider test reads "
                    + "one source line, so splitting self.getObject() and the call over two lines "
                    + "reds the rule. The boot-callback rule reads the @PostConstruct "
                    + "annotation and the constructor call sites; it does not prove that the seed "
                    + "row exists.";

    record MethodPinnedToOpenTheTenantScopeFirst(
            String declaringClassFullName, String methodName) {
    }

    record WriterPinnedAsASeparateProxiedBean(
            String callerFullName, String writerFullName, List<String> writeMethodNames) {
    }

    record MethodPinnedToTheBootLifecycleCallback(
            String declaringClassFullName, String methodName) {
    }

    static final Map<MethodPinnedToOpenTheTenantScopeFirst, String>
            METHODS_PINNED_TO_OPEN_THE_TENANT_SCOPE_FIRST = pinnedNestings();

    static final WriterPinnedAsASeparateProxiedBean JOIN_REQUEST_WRITER_PIN =
            new WriterPinnedAsASeparateProxiedBean(
                    "ch.alpenflight.joinrequests.application.JoinRequestsService",
                    "ch.alpenflight.joinrequests.application.JoinRequestTxWriter",
                    List.of("file", "withdraw"));

    static final MethodPinnedToTheBootLifecycleCallback FLIGHT_SEED_RESOLUTION_PIN =
            new MethodPinnedToTheBootLifecycleCallback(
                    "ch.alpenflight.flights.infra.FlightInitialState", "resolveSeeds");

    @ArchTest
    static final ArchRule the_transaction_opens_inside_the_tenant_scope_never_around_it =
            transactionOpensInsideTheTenantScopeNeverAroundIt(
                    METHODS_PINNED_TO_OPEN_THE_TENANT_SCOPE_FIRST);

    @ArchTest
    static final ArchRule the_join_request_writer_stays_a_separate_proxied_bean =
            writerStaysASeparateProxiedBean(JOIN_REQUEST_WRITER_PIN,
                    WHY_THE_JOIN_REQUEST_WRITER_STAYS_A_SEPARATE_PROXIED_BEAN);

    @ArchTest
    static final ArchRule no_production_class_calls_a_transactional_method_it_declares_itself =
            noClassCallsATransactionalMethodItDeclaresItself();

    @ArchTest
    static final ArchRule the_boot_seed_resolution_stays_a_post_construct_callback =
            bootSeedResolutionStaysAPostConstructCallback(FLIGHT_SEED_RESOLUTION_PIN,
                    WHY_THE_FLIGHT_SEED_RESOLVES_IN_A_POST_CONSTRUCT_CALLBACK);

    static ArchRule transactionOpensInsideTheTenantScopeNeverAroundIt(
            Map<MethodPinnedToOpenTheTenantScopeFirst, String> pinnedAndWhy) {
        return methods()
                .that(describe("open the tenant scope outside the transaction by design",
                        (JavaMethod method) -> pinnedAndWhy.containsKey(nestingPinOf(method))))
                .should(openTheTenantScopeBeforeTheTransaction(pinnedAndWhy))
                .as("A method pinned to open the tenant scope outside the transaction must call "
                        + "Tenants.runAs in its own body, must open no transaction around that "
                        + "call, and must open the transaction inside the Tenants.runAs lambda. "
                        + "Each violation below names the behaviour that the other order breaks."
                        + RESIDUAL_LIMIT_THESE_RULES_DO_NOT_COVER)
                .allowEmptyShould(true);
    }

    static ArchRule writerStaysASeparateProxiedBean(
            WriterPinnedAsASeparateProxiedBean pin, String why) {
        return classes()
                .that(describe("call a transactional writer that Spring must proxy",
                        (JavaClass c) -> c.getFullName().equals(pin.callerFullName())))
                .should(reachTheWriterThroughAnInjectedProxiedBean(pin, why))
                .as("A caller that needs a transaction below a Tenants.runAs scope must reach it "
                        + "through a separate injected Spring bean. Each violation below names "
                        + "the behaviour that an inlined or hand-built writer breaks."
                        + RESIDUAL_LIMIT_THESE_RULES_DO_NOT_COVER)
                .allowEmptyShould(true);
    }

    static ArchRule noClassCallsATransactionalMethodItDeclaresItself() {
        return classes()
                .should(callNoTransactionalMethodItDeclaresItself())
                .as("A class must not call a @Transactional method that it declares itself. Spring "
                        + "opens the transaction in a proxy around the bean. A self-call never "
                        + "crosses the proxy, so Spring opens no transaction, and the write runs "
                        + "with no rollback boundary. Reach the boundary through an injected "
                        + "ObjectProvider self-proxy, as DailyReportJob does, or move it onto a "
                        + "separate bean, as JoinRequestTxWriter does for JoinRequestsService. "
                        + "Measured on the AircraftDatabaseSyncJob run: the self-call let a "
                        + "half-finished sync keep the aircraft it already wrote."
                        + RESIDUAL_LIMIT_THESE_RULES_DO_NOT_COVER)
                .allowEmptyShould(true);
    }

    static ArchRule bootSeedResolutionStaysAPostConstructCallback(
            MethodPinnedToTheBootLifecycleCallback pin, String why) {
        return classes()
                .that(describe("resolve a reference seed once at boot",
                        (JavaClass c) -> c.getFullName().equals(pin.declaringClassFullName())))
                .should(resolveTheSeedFromAPostConstructCallbackOnly(pin, why))
                .as("A class that caches a reference seed at boot must resolve it from a "
                        + "@PostConstruct callback, and never from its constructor."
                        + CORRECTION_THE_DELETED_COMMENT_GAVE_AN_UNSUPPORTED_REASON
                        + RESIDUAL_LIMIT_THESE_RULES_DO_NOT_COVER)
                .allowEmptyShould(true);
    }

    private static Map<MethodPinnedToOpenTheTenantScopeFirst, String>
            pinnedNestings() {
        Map<MethodPinnedToOpenTheTenantScopeFirst, String> pins =
                new LinkedHashMap<>();
        pins.put(new MethodPinnedToOpenTheTenantScopeFirst(
                        "ch.alpenflight.flights.application.FlightReportRebuildService",
                        "rebuildForClub"),
                WHY_THE_FLIGHT_REPORT_REBUILD_OPENS_THE_TENANT_SCOPE_FIRST);
        pins.put(new MethodPinnedToOpenTheTenantScopeFirst(
                        "ch.alpenflight.joinrequests.application.JoinRequestsService", "submit"),
                WHY_THE_JOIN_REQUEST_WRITE_OPENS_THE_TENANT_SCOPE_FIRST);
        pins.put(new MethodPinnedToOpenTheTenantScopeFirst(
                        "ch.alpenflight.joinrequests.application.JoinRequestsService", "withdraw"),
                WHY_THE_JOIN_REQUEST_WRITE_OPENS_THE_TENANT_SCOPE_FIRST);
        return Map.copyOf(pins);
    }

    private static ArchCondition<JavaMethod> openTheTenantScopeBeforeTheTransaction(
            Map<MethodPinnedToOpenTheTenantScopeFirst, String> pinnedAndWhy) {
        return new ArchCondition<>("call Tenants.runAs in the method body, and open the "
                + "transaction only inside the Tenants.runAs lambda") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                String why = pinnedAndWhy.getOrDefault(nestingPinOf(method), "");
                Set<JavaMethodCall> calls = method.getMethodCallsFromSelf();

                transactionalAnnotationOnTheMethodOrItsClass(method).ifPresent(found ->
                        events.add(SimpleConditionEvent.violated(method, method.getFullName()
                                + " carries " + found + ", so a transaction opens around the "
                                + "whole method body and around Tenants.runAs. " + why)));

                if (noCallMatches(calls, call -> isTenantsRunAs(call)
                        && !call.isDeclaredInLambda())) {
                    events.add(SimpleConditionEvent.violated(method, method.getFullName()
                            + " no longer calls Tenants.runAs in its own body, so nothing binds "
                            + "the tenant before the transaction opens. " + why));
                }

                for (JavaMethodCall call : calls) {
                    if (opensATransaction(call) && !call.isDeclaredInLambda()) {
                        events.add(SimpleConditionEvent.violated(method, method.getFullName()
                                + " opens a transaction at " + call.getSourceCodeLocation()
                                + " by calling " + call.getTargetOwner().getSimpleName() + "."
                                + call.getTarget().getName() + " outside every lambda, so the "
                                + "transaction wraps Tenants.runAs instead of nesting inside it. "
                                + why));
                    }
                }

                if (noCallMatches(calls, call -> opensATransaction(call)
                        && call.isDeclaredInLambda())) {
                    events.add(SimpleConditionEvent.violated(method, method.getFullName()
                            + " opens no transaction inside a lambda, so the work below "
                            + "Tenants.runAs runs with no transaction boundary of its own. "
                            + why));
                }
            }
        };
    }

    private static ArchCondition<JavaClass> reachTheWriterThroughAnInjectedProxiedBean(
            WriterPinnedAsASeparateProxiedBean pin, String why) {
        return new ArchCondition<>("hold the transactional writer as an injected Spring bean "
                + "whose write methods carry @Transactional") {
            @Override
            public void check(JavaClass caller, ConditionEvents events) {
                Optional<JavaClass> writer = injectedFieldOfType(caller, pin.writerFullName());
                if (writer.isEmpty()) {
                    events.add(SimpleConditionEvent.violated(caller, caller.getName()
                            + " holds no injected field of type " + pin.writerFullName()
                            + ", so the transactional write no longer crosses a Spring proxy. "
                            + why));
                    return;
                }
                JavaClass writerClass = writer.get();

                if (!isASpringBean(writerClass)) {
                    events.add(SimpleConditionEvent.violated(caller, writerClass.getName()
                            + " carries no Spring stereotype, so Spring builds no proxy around it "
                            + "and opens no transaction. " + why));
                }

                for (String writeMethodName : pin.writeMethodNames()) {
                    List<JavaMethod> declared = writerClass.getMethods().stream()
                            .filter(m -> m.getName().equals(writeMethodName))
                            .toList();
                    if (declared.isEmpty()) {
                        events.add(SimpleConditionEvent.violated(caller, writerClass.getName()
                                + " no longer declares " + writeMethodName
                                + ", so this rule can no longer see the transaction boundary. "
                                + why));
                        continue;
                    }
                    for (JavaMethod writeMethod : declared) {
                        if (transactionalAnnotationOnTheMethodOrItsClass(writeMethod).isEmpty()) {
                            events.add(SimpleConditionEvent.violated(caller,
                                    writeMethod.getFullName() + " carries no @Transactional, so "
                                            + "the write, the audit row and the domain event run "
                                            + "with no shared rollback boundary. " + why));
                        }
                    }
                }

                for (JavaConstructorCall call : caller.getConstructorCallsFromSelf()) {
                    if (call.getTargetOwner().getFullName().equals(pin.writerFullName())) {
                        events.add(SimpleConditionEvent.violated(caller, caller.getName()
                                + " builds " + writerClass.getSimpleName() + " with new at "
                                + call.getSourceCodeLocation()
                                + ", so the instance carries no Spring proxy. " + why));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaClass> callNoTransactionalMethodItDeclaresItself() {
        return new ArchCondition<>("call no @Transactional method that it declares itself") {
            @Override
            public void check(JavaClass candidate, ConditionEvents events) {
                for (JavaMethodCall call : candidate.getMethodCallsFromSelf()) {
                    if (!call.getTargetOwner().getFullName().equals(candidate.getFullName())
                            || isCompilerGenerated(call.getOrigin())
                            || reachesTheBeanThroughAnObjectProviderOnTheSameLine(call)) {
                        continue;
                    }
                    Optional<JavaMethod> target = call.getTarget().resolveMember();
                    if (target.isEmpty()) {
                        continue;
                    }
                    Optional<String> demarcation =
                            transactionalAnnotationOnTheMethodOrItsClass(target.get());
                    if (demarcation.isEmpty()
                            || theOriginAlreadyRunsInsideThatBoundary(call, candidate,
                            target.get())) {
                        continue;
                    }
                    events.add(SimpleConditionEvent.violated(candidate, candidate.getName()
                            + " calls its own method " + call.getTarget().getName()
                            + ", which carries " + demarcation.get() + ", at "
                            + call.getSourceCodeLocation()
                            + ", so the call skips the Spring proxy and opens no transaction."));
                }
            }
        };
    }

    private static boolean reachesTheBeanThroughAnObjectProviderOnTheSameLine(JavaMethodCall call) {
        return call.getOrigin().getMethodCallsFromSelf().stream()
                .anyMatch(sibling -> sibling.getLineNumber() == call.getLineNumber()
                        && OBJECT_PROVIDER_FULL_NAME
                        .equals(sibling.getTargetOwner().getFullName()));
    }

    private static boolean theOriginAlreadyRunsInsideThatBoundary(
            JavaMethodCall call, JavaClass candidate, JavaMethod target) {
        if (!(call.getOrigin() instanceof JavaMethod origin)) {
            return false;
        }
        if (NonTransactionalByDesignGuardTest.transactionalAnnotationOn(origin).isPresent()) {
            return true;
        }
        return NonTransactionalByDesignGuardTest.transactionalAnnotationOn(candidate).isPresent()
                && NonTransactionalByDesignGuardTest.transactionalAnnotationOn(target).isEmpty();
    }

    private static ArchCondition<JavaClass> resolveTheSeedFromAPostConstructCallbackOnly(
            MethodPinnedToTheBootLifecycleCallback pin, String why) {
        return new ArchCondition<>("resolve the reference seed from a @PostConstruct callback, "
                + "and never from a constructor or a field initializer") {
            @Override
            public void check(JavaClass candidate, ConditionEvents events) {
                List<JavaMethod> declared = candidate.getMethods().stream()
                        .filter(m -> m.getName().equals(pin.methodName()))
                        .toList();
                if (declared.isEmpty()) {
                    events.add(SimpleConditionEvent.violated(candidate, candidate.getName()
                            + " no longer declares " + pin.methodName()
                            + ", so the boot-time seed resolution vanished. " + why));
                } else {
                    for (JavaMethod resolver : declared) {
                        if (!resolver.isAnnotatedWith(POST_CONSTRUCT_FULL_NAME)) {
                            events.add(SimpleConditionEvent.violated(candidate,
                                    resolver.getFullName() + " carries no @PostConstruct. "
                                            + why));
                        }
                    }
                }

                seedLookupReachedFromAConstructor(candidate, pin).ifPresent(call ->
                        events.add(SimpleConditionEvent.violated(candidate, candidate.getName()
                                + " reaches the seed lookup from its constructor at "
                                + call.getSourceCodeLocation() + " by calling "
                                + call.getTargetOwner().getSimpleName() + "."
                                + call.getTarget().getName() + ". "
                                + WHY_THE_FLIGHT_SEED_LOOKUP_STAYS_OUT_OF_THE_CONSTRUCTOR)));
            }
        };
    }

    private static Optional<JavaMethodCall> seedLookupReachedFromAConstructor(
            JavaClass candidate, MethodPinnedToTheBootLifecycleCallback pin) {
        Deque<JavaCodeUnit> pending = new ArrayDeque<>();
        Set<String> alreadyWalked = new HashSet<>();
        for (JavaCodeUnit codeUnit : candidate.getCodeUnits()) {
            if (codeUnit.isConstructor() && alreadyWalked.add(codeUnit.getFullName())) {
                pending.add(codeUnit);
            }
        }
        while (!pending.isEmpty()) {
            for (JavaMethodCall call : pending.poll().getMethodCallsFromSelf()) {
                if (resolvesTheSeed(call, pin)) {
                    return Optional.of(call);
                }
                if (call.getTargetOwner().getFullName().equals(candidate.getFullName())) {
                    call.getTarget().resolveMember()
                            .filter(reached -> alreadyWalked.add(reached.getFullName()))
                            .ifPresent(pending::add);
                }
            }
        }
        return Optional.empty();
    }

    private static boolean resolvesTheSeed(
            JavaMethodCall call, MethodPinnedToTheBootLifecycleCallback pin) {
        String targetOwner = call.getTargetOwner().getFullName();
        if (targetOwner.equals(pin.declaringClassFullName())
                && call.getTarget().getName().equals(pin.methodName())) {
            return true;
        }
        return PROGRAMMATIC_TRANSACTION_STARTER_OWNERS.contains(targetOwner)
                || ENTITY_MANAGER_FULL_NAME.equals(targetOwner);
    }

    private static Optional<JavaClass> injectedFieldOfType(JavaClass caller, String typeFullName) {
        return caller.getFields().stream()
                .map(JavaField::getRawType)
                .filter(type -> type.getFullName().equals(typeFullName))
                .findFirst();
    }

    private static boolean isCompilerGenerated(JavaCodeUnit codeUnit) {
        return codeUnit.getModifiers().contains(JavaModifier.SYNTHETIC)
                || codeUnit.getModifiers().contains(JavaModifier.BRIDGE);
    }

    private static boolean isASpringBean(JavaClass candidate) {
        return candidate.isAnnotatedWith(SPRING_COMPONENT_FULL_NAME)
                || candidate.isMetaAnnotatedWith(SPRING_COMPONENT_FULL_NAME);
    }

    private static boolean opensATransaction(JavaMethodCall call) {
        if (PROGRAMMATIC_TRANSACTION_STARTER_OWNERS.contains(
                call.getTargetOwner().getFullName())) {
            return true;
        }
        return call.getTarget().resolveMember()
                .map(target -> transactionalAnnotationOnTheMethodOrItsClass(target).isPresent())
                .orElse(false);
    }

    private static Optional<String> transactionalAnnotationOnTheMethodOrItsClass(
            JavaMethod method) {
        Optional<String> onTheMethod =
                NonTransactionalByDesignGuardTest.transactionalAnnotationOn(method);
        return onTheMethod.isPresent()
                ? onTheMethod
                : NonTransactionalByDesignGuardTest.transactionalAnnotationOn(method.getOwner());
    }

    private static boolean isTenantsRunAs(JavaMethodCall call) {
        return TENANTS_FULL_NAME.equals(call.getTargetOwner().getFullName())
                && RUN_AS_METHOD_NAME.equals(call.getTarget().getName());
    }

    private static boolean noCallMatches(
            Set<JavaMethodCall> calls, java.util.function.Predicate<JavaMethodCall> shape) {
        return calls.stream().noneMatch(shape);
    }

    private static MethodPinnedToOpenTheTenantScopeFirst nestingPinOf(
            JavaMethod method) {
        return new MethodPinnedToOpenTheTenantScopeFirst(
                method.getOwner().getFullName(), method.getName());
    }
}
