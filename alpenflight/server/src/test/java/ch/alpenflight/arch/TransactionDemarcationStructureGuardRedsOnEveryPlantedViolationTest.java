package ch.alpenflight.arch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.arch.TransactionDemarcationStructureGuardTest.MethodPinnedToOpenTheTenantScopeFirst;
import ch.alpenflight.arch.TransactionDemarcationStructureGuardTest.MethodPinnedToTheBootLifecycleCallback;
import ch.alpenflight.arch.TransactionDemarcationStructureGuardTest.WriterPinnedAsASeparateProxiedBean;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import transactiondemarcationplants.TransactionDemarcationPlants;

class TransactionDemarcationStructureGuardRedsOnEveryPlantedViolationTest {

    private static final String WHY_NESTING = TransactionDemarcationStructureGuardTest
            .WHY_THE_FLIGHT_REPORT_REBUILD_OPENS_THE_TENANT_SCOPE_FIRST;

    private static final String WHY_WRITER_BEAN = TransactionDemarcationStructureGuardTest
            .WHY_THE_JOIN_REQUEST_WRITER_STAYS_A_SEPARATE_PROXIED_BEAN;

    private static final String WHY_BOOT_CALLBACK = TransactionDemarcationStructureGuardTest
            .WHY_THE_FLIGHT_SEED_RESOLVES_IN_A_POST_CONSTRUCT_CALLBACK;

    private static JavaClasses imported(Class<?>... plants) {
        return new ClassFileImporter().importClasses(plants);
    }

    private static Map<MethodPinnedToOpenTheTenantScopeFirst, String> nestingPin(
            Class<?> plantedOwner) {
        return Map.of(new MethodPinnedToOpenTheTenantScopeFirst(
                        plantedOwner.getName(),
                        TransactionDemarcationPlants.PINNED_NESTING_METHOD_NAME),
                WHY_NESTING);
    }

    private static WriterPinnedAsASeparateProxiedBean writerPin(
            Class<?> plantedCaller, Class<?> plantedWriter) {
        return new WriterPinnedAsASeparateProxiedBean(plantedCaller.getName(),
                plantedWriter.getName(),
                List.of(TransactionDemarcationPlants.PINNED_WRITE_METHOD_NAME));
    }

    private static MethodPinnedToTheBootLifecycleCallback bootCallbackPin(Class<?> plantedOwner) {
        return new MethodPinnedToTheBootLifecycleCallback(plantedOwner.getName(),
                TransactionDemarcationPlants.PINNED_SEED_RESOLVER_METHOD_NAME);
    }

    private static void assertTheNestingRuleRedsOn(Class<?> plant, String expectedShape) {
        assertThatThrownBy(() -> TransactionDemarcationStructureGuardTest
                .transactionOpensInsideTheTenantScopeNeverAroundIt(nestingPin(plant))
                .check(imported(plant)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(expectedShape)
                .hasMessageContaining(WHY_NESTING)
                .hasMessageContaining(TransactionDemarcationStructureGuardTest
                        .RESIDUAL_LIMIT_THESE_RULES_DO_NOT_COVER);
    }

    private static void assertTheWriterBeanRuleRedsOn(
            Class<?> plantedCaller, Class<?> plantedWriter, String expectedShape) {
        assertThatThrownBy(() -> TransactionDemarcationStructureGuardTest
                .writerStaysASeparateProxiedBean(
                        writerPin(plantedCaller, plantedWriter), WHY_WRITER_BEAN)
                .check(imported(plantedCaller, plantedWriter)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(expectedShape)
                .hasMessageContaining(WHY_WRITER_BEAN);
    }

    private static void assertTheBootCallbackRuleRedsOn(Class<?> plant, String expectedShape) {
        assertThatThrownBy(() -> TransactionDemarcationStructureGuardTest
                .bootSeedResolutionStaysAPostConstructCallback(bootCallbackPin(plant),
                        WHY_BOOT_CALLBACK)
                .check(imported(plant)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(expectedShape)
                .hasMessageContaining(TransactionDemarcationStructureGuardTest
                        .CORRECTION_THE_DELETED_COMMENT_GAVE_AN_UNSUPPORTED_REASON);
    }

    @Test
    void input_class_the_transaction_template_wraps_the_tenant_scope() {
        assertTheNestingRuleRedsOn(
                TransactionDemarcationPlants
                        .PlantedTransactionTemplateWrappedAroundTheTenantScope.class,
                "outside every lambda");
    }

    @Test
    void input_class_the_tenant_scope_opens_no_transaction_inside_itself() {
        assertTheNestingRuleRedsOn(
                TransactionDemarcationPlants.PlantedTenantScopeWithNoTransactionInside.class,
                "opens no transaction inside a lambda");
    }

    @Test
    void input_class_the_pinned_method_no_longer_opens_the_tenant_scope() {
        assertTheNestingRuleRedsOn(
                TransactionDemarcationPlants.PlantedMethodThatNoLongerOpensTheTenantScope.class,
                "no longer calls Tenants.runAs in its own body");
    }

    @Test
    void input_class_a_transactional_annotation_wraps_the_whole_pinned_method() {
        assertTheNestingRuleRedsOn(
                TransactionDemarcationPlants
                        .PlantedTransactionalAnnotationAroundTheTenantScope.class,
                "so a transaction opens around the whole method body");
    }

    @Test
    void the_nesting_rule_stays_green_on_a_transaction_template_inside_the_tenant_scope() {
        Class<?> plant =
                TransactionDemarcationPlants.PlantedTenantScopeOutsideTheTransactionTemplate.class;

        assertThatCode(() -> TransactionDemarcationStructureGuardTest
                .transactionOpensInsideTheTenantScopeNeverAroundIt(nestingPin(plant))
                .check(imported(plant)))
                .doesNotThrowAnyException();
    }

    @Test
    void the_nesting_rule_stays_green_on_a_transactional_bean_called_inside_the_tenant_scope() {
        Class<?> caller =
                TransactionDemarcationPlants.PlantedTenantScopeOutsideATransactionalWriterBean.class;
        Class<?> writer = TransactionDemarcationPlants.PlantedTransactionalWriterBean.class;

        assertThatCode(() -> TransactionDemarcationStructureGuardTest
                .transactionOpensInsideTheTenantScopeNeverAroundIt(nestingPin(caller))
                .check(imported(caller, writer)))
                .doesNotThrowAnyException();
    }

    @Test
    void input_class_the_caller_builds_the_writer_with_new() {
        assertTheWriterBeanRuleRedsOn(
                TransactionDemarcationPlants.PlantedCallerThatBuildsTheWriterItself.class,
                TransactionDemarcationPlants.PlantedTransactionalWriterBean.class,
                "with new at");
    }

    @Test
    void input_class_the_writer_lost_its_spring_stereotype() {
        assertTheWriterBeanRuleRedsOn(
                TransactionDemarcationPlants.PlantedCallerHoldingAWriterWithNoSpringStereotype.class,
                TransactionDemarcationPlants.PlantedWriterWithNoSpringStereotype.class,
                "carries no Spring stereotype");
    }

    @Test
    void input_class_the_writer_lost_its_transactional_annotation() {
        assertTheWriterBeanRuleRedsOn(
                TransactionDemarcationPlants
                        .PlantedCallerHoldingAWriterThatLostItsTransactional.class,
                TransactionDemarcationPlants.PlantedWriterThatLostItsTransactional.class,
                "carries no @Transactional");
    }

    @Test
    void input_class_the_writer_is_inlined_into_the_caller() {
        assertTheWriterBeanRuleRedsOn(
                TransactionDemarcationPlants
                        .PlantedCallerWithTheWriterInlinedAndCalledThroughThis.class,
                TransactionDemarcationPlants.PlantedTransactionalWriterBean.class,
                "holds no injected field of type");
    }

    @Test
    void the_writer_bean_rule_stays_green_on_an_injected_transactional_writer_bean() {
        Class<?> caller = TransactionDemarcationPlants.PlantedCallerHoldingTheInjectedWriterBean.class;
        Class<?> writer = TransactionDemarcationPlants.PlantedTransactionalWriterBean.class;

        assertThatCode(() -> TransactionDemarcationStructureGuardTest
                .writerStaysASeparateProxiedBean(writerPin(caller, writer), WHY_WRITER_BEAN)
                .check(imported(caller, writer)))
                .doesNotThrowAnyException();
    }

    private static void assertTheSelfCallRuleRedsOn(Class<?> plant) {
        assertThatThrownBy(() -> TransactionDemarcationStructureGuardTest
                .noClassCallsATransactionalMethodItDeclaresItself()
                .check(imported(plant)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("calls its own method")
                .hasMessageContaining(TransactionDemarcationPlants.PINNED_WRITE_METHOD_NAME)
                .hasMessageContaining("skips the Spring proxy and opens no transaction")
                .hasMessageContaining(TransactionDemarcationStructureGuardTest
                        .RESIDUAL_LIMIT_THESE_RULES_DO_NOT_COVER);
    }

    private static void assertTheSelfCallRuleStaysGreenOn(Class<?>... plants) {
        assertThatCode(() -> TransactionDemarcationStructureGuardTest
                .noClassCallsATransactionalMethodItDeclaresItself()
                .check(imported(plants)))
                .doesNotThrowAnyException();
    }

    @Test
    void input_class_the_caller_invokes_its_own_transactional_method_through_this() {
        assertTheSelfCallRuleRedsOn(TransactionDemarcationPlants
                .PlantedCallerWithTheWriterInlinedAndCalledThroughThis.class);
    }

    @Test
    void input_class_a_scheduled_entry_point_self_calls_a_method_level_transactional() {
        assertTheSelfCallRuleRedsOn(TransactionDemarcationPlants
                .PlantedScheduledEntryPointThatSelfCallsItsMethodLevelTransactional.class);
    }

    @Test
    void input_class_a_constructor_self_calls_a_method_a_class_level_transactional_covers() {
        assertTheSelfCallRuleRedsOn(TransactionDemarcationPlants
                .PlantedConstructorThatSelfCallsAClassLevelTransactionalMethod.class);
    }

    @Test
    void input_class_a_private_helper_forwards_the_self_call_to_the_transactional_method() {
        assertTheSelfCallRuleRedsOn(TransactionDemarcationPlants
                .PlantedScheduledEntryPointThatSelfCallsThroughAPrivateHelper.class);
    }

    @Test
    void input_class_a_lambda_body_carries_the_self_call_to_the_transactional_method() {
        assertTheSelfCallRuleRedsOn(TransactionDemarcationPlants
                .PlantedScheduledEntryPointThatSelfCallsInsideALambda.class);
    }

    @Test
    void the_self_call_rule_cannot_see_a_self_call_routed_through_a_method_reference() {
        assertTheSelfCallRuleStaysGreenOn(TransactionDemarcationPlants
                .PlantedScheduledEntryPointThatSelfCallsThroughAMethodReference.class);

        assertThat(TransactionDemarcationStructureGuardTest
                .RESIDUAL_LIMIT_THESE_RULES_DO_NOT_COVER)
                .as("a shape the rule cannot see must be named in the failure message")
                .contains("It cannot see a self-call routed through a method reference");
    }

    @Test
    void the_self_call_rule_stays_green_when_the_transactional_method_sits_on_another_bean() {
        assertTheSelfCallRuleStaysGreenOn(
                TransactionDemarcationPlants
                        .PlantedCallerThatCallsAnotherBeansTransactionalMethod.class,
                TransactionDemarcationPlants.PlantedTransactionalWriterBean.class);
    }

    @Test
    void input_class_a_raw_object_provider_crosses_the_proxy_without_naming_a_reason() {
        assertThatThrownBy(() -> TransactionDemarcationStructureGuardTest
                .noClassCallsATransactionalMethodItDeclaresItself()
                .check(imported(TransactionDemarcationPlants
                        .PlantedScheduledEntryPointThatCrossesTheProxyThroughAnObjectProvider
                        .class)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("through a raw ObjectProvider")
                .hasMessageContaining("names no reason for existing");
    }

    @Test
    void the_self_call_rule_stays_green_when_the_self_proxy_names_the_transactional_reason() {
        assertTheSelfCallRuleStaysGreenOn(TransactionDemarcationPlants
                .PlantedScheduledEntryPointThatNamesTheTransactionalReason.class);
    }

    @Test
    void input_class_the_self_proxy_names_a_transaction_the_target_does_not_declare() {
        assertThatThrownBy(() -> TransactionDemarcationStructureGuardTest
                .noClassCallsATransactionalMethodItDeclaresItself()
                .check(imported(TransactionDemarcationPlants
                        .PlantedSelfProxyNamingATransactionTheTargetNoLongerDeclares.class)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("through SelfProxy."
                        + TransactionDemarcationStructureGuardTest
                        .SELF_PROXY_REASON_THE_TRANSACTION_APPLIES)
                .hasMessageContaining("carries no @Transactional, so the stated reason is false");
    }

    @Test
    void the_self_call_rule_stays_green_when_the_self_proxy_names_the_job_run_record_reason() {
        assertTheSelfCallRuleStaysGreenOn(TransactionDemarcationPlants
                .PlantedMeasuredJobThatNamesTheJobRunRecordReason.class);
    }

    @Test
    void input_class_the_self_proxy_names_a_job_run_record_the_aspect_never_writes() {
        assertThatThrownBy(() -> TransactionDemarcationStructureGuardTest
                .noClassCallsATransactionalMethodItDeclaresItself()
                .check(imported(TransactionDemarcationPlants
                        .PlantedSelfProxyNamingAJobRunRecordTheAspectNeverWrites.class)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("through SelfProxy."
                        + TransactionDemarcationStructureGuardTest
                        .SELF_PROXY_REASON_THE_JOB_RUN_RECORD_IS_WRITTEN)
                .hasMessageContaining("MeasuredJobAspect advises only runOnce");
    }

    @Test
    void every_reason_the_self_proxy_offers_is_a_reason_this_guard_verifies() {
        List<String> reasonsTheHelperOffers = Arrays.stream(
                        loaded(TransactionDemarcationStructureGuardTest.SELF_PROXY_FULL_NAME)
                                .getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> !Modifier.isStatic(method.getModifiers()))
                .map(Method::getName)
                .toList();

        assertThat(reasonsTheHelperOffers)
                .as("SelfProxy states the reason for a proxy hop in the method it offers; a "
                        + "reason the guard does not verify is a reason nobody re-tests")
                .isNotEmpty()
                .allMatch(TransactionDemarcationStructureGuardTest
                        .SELF_PROXY_REASONS_THIS_RULE_VERIFIES::contains);
    }

    @Test
    void the_self_call_rule_stays_green_inside_one_class_level_transactional_boundary() {
        assertTheSelfCallRuleStaysGreenOn(TransactionDemarcationPlants
                .PlantedClassLevelTransactionalWhoseMethodsCallEachOther.class);
    }

    @Test
    void the_self_call_rule_stays_green_when_the_calling_method_is_transactional_itself() {
        assertTheSelfCallRuleStaysGreenOn(TransactionDemarcationPlants
                .PlantedTransactionalMethodCalledFromATransactionalMethodOfTheSameClass.class);
    }

    @Test
    void input_class_the_boot_resolver_lost_its_post_construct_annotation() {
        assertTheBootCallbackRuleRedsOn(
                TransactionDemarcationPlants.PlantedSeedResolverWithNoPostConstruct.class,
                "carries no @PostConstruct");
    }

    @Test
    void input_class_the_constructor_calls_the_boot_resolver() {
        assertTheBootCallbackRuleRedsOn(
                TransactionDemarcationPlants.PlantedSeedResolverCalledFromTheConstructor.class,
                "reaches the seed lookup from its constructor");
    }

    @Test
    void input_class_a_field_initializer_reaches_the_seed_lookup_through_a_helper() {
        assertTheBootCallbackRuleRedsOn(
                TransactionDemarcationPlants.PlantedSeedResolvedByAFieldInitializer.class,
                "reaches the seed lookup from its constructor");
    }

    @Test
    void the_boot_callback_rule_stays_green_on_a_post_construct_resolver() {
        Class<?> plant =
                TransactionDemarcationPlants.PlantedSeedResolverInAPostConstructCallback.class;

        assertThatCode(() -> TransactionDemarcationStructureGuardTest
                .bootSeedResolutionStaysAPostConstructCallback(bootCallbackPin(plant),
                        WHY_BOOT_CALLBACK)
                .check(imported(plant)))
                .doesNotThrowAnyException();
    }

    @Test
    void every_pinned_method_still_exists_so_a_rename_cannot_empty_the_guard() {
        TransactionDemarcationStructureGuardTest
                .METHODS_PINNED_TO_OPEN_THE_TENANT_SCOPE_FIRST.keySet()
                .forEach(pin -> assertThat(declaredMethodNamesOf(pin.declaringClassFullName()))
                        .as("%s.%s is pinned to open the tenant scope outside the transaction; a "
                                        + "rename that leaves the pin behind turns the guard into "
                                        + "a no-op",
                                pin.declaringClassFullName(), pin.methodName())
                        .contains(pin.methodName()));

        assertThat(declaredMethodNamesOf(
                TransactionDemarcationStructureGuardTest.FLIGHT_SEED_RESOLUTION_PIN
                        .declaringClassFullName()))
                .as("the boot seed resolver is pinned to a @PostConstruct callback; a rename that "
                        + "leaves the pin behind turns the guard into a no-op")
                .contains(TransactionDemarcationStructureGuardTest.FLIGHT_SEED_RESOLUTION_PIN
                        .methodName());
    }

    @Test
    void the_pinned_writer_bean_still_declares_every_pinned_write_method() {
        WriterPinnedAsASeparateProxiedBean pin =
                TransactionDemarcationStructureGuardTest.JOIN_REQUEST_WRITER_PIN;

        assertThat(declaredMethodNamesOf(pin.writerFullName()))
                .as("%s is pinned as the separate transactional writer bean of %s",
                        pin.writerFullName(), pin.callerFullName())
                .containsAll(pin.writeMethodNames());

        Arrays.stream(loaded(pin.writerFullName()).getDeclaredMethods())
                .filter(method -> pin.writeMethodNames().contains(method.getName()))
                .forEach(method -> assertThat(method.isAnnotationPresent(Transactional.class))
                        .as("%s must keep @Transactional", method)
                        .isTrue());
    }

    private static List<String> declaredMethodNamesOf(String classFullName) {
        return Arrays.stream(loaded(classFullName).getDeclaredMethods())
                .map(Method::getName)
                .toList();
    }

    private static Class<?> loaded(String classFullName) {
        try {
            return Class.forName(classFullName);
        } catch (ClassNotFoundException notOnTheClasspath) {
            throw new AssertionError(
                    classFullName + " is pinned by the transaction-demarcation guard but no "
                            + "longer exists", notOnTheClasspath);
        }
    }
}
