package ch.alpenflight.platform.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.AlpenFlightApplication;
import ch.alpenflight.aircraft.application.AircraftDatabaseSyncJob;
import ch.alpenflight.arch.ScheduledLifecycleFilterCoverageTest;
import ch.alpenflight.deployments.application.LifecycleStateFilter;
import ch.alpenflight.deployments.domain.LifecycleState;
import ch.alpenflight.persons.application.LicenceNotificationJob;
import ch.alpenflight.platform.scheduling.JobRegistry.RegisteredJobThatDoesNotSkipTheSandboxDeploymentException;
import ch.alpenflight.platform.scheduling.JobRegistry.TwoRegisteredJobsUnderOneNameException;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.scheduling.annotation.Scheduled;

class JobRegistrySandboxDeploymentSealRedsOnEveryPlantedViolationTest {

    private static final String CLASS_FROM_THE_MIGRATION_BUNDLE_JAR =
            "ch.alpenflight.migration.bundle.Manifest";

    private static final int EIGHT_JOBS_REGISTER_TODAY = 8;

    private static JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .withImportOption(new ImportOption.DoNotIncludeJars())
                .importPackagesOf(AlpenFlightApplication.class);
    }

    @MeasuredJob(name = "planted-unscoped-annotated-bean")
    static class JobRegisteredAsAnAnnotatedBeanThatDeclaresItselfUnscoped implements BusinessJob {

        @Scheduled(cron = "0 0 3 * * *")
        @UnscopedScheduledJob
        public void runScheduled() {
        }

        @Override
        public @Nullable Object runOnce() {
            return null;
        }
    }

    @MeasuredJob(name = "planted-run-now-only-factory-bean")
    static class JobRegisteredByAFactoryMethodWithNoScheduledEntryPoint implements BusinessJob {

        @Override
        public @Nullable Object runOnce() {
            return null;
        }
    }

    @MeasuredJob(name = "planted-lifecycle-filtered-bean")
    static class JobRegisteredWithALifecycleFilterThatExcludesTheSandbox implements BusinessJob {

        @Scheduled(cron = "0 0 3 * * *")
        @LifecycleStateFilter({LifecycleState.ACTIVE, LifecycleState.TRIAL})
        public void runScheduled() {
        }

        @Override
        public @Nullable Object runOnce() {
            return null;
        }
    }

    @MeasuredJob(name = "planted-sandbox-naming-bean")
    static class JobRegisteredWithALifecycleFilterThatNamesTheSandbox implements BusinessJob {

        @Scheduled(cron = "0 0 3 * * *")
        @LifecycleStateFilter({LifecycleState.ACTIVE, LifecycleState.SANDBOX})
        public void runScheduled() {
        }

        @Override
        public @Nullable Object runOnce() {
            return null;
        }
    }

    static class JobRegisteredAsASubclassThatInheritsTheMeasuredNameOfItsParent
            extends JobRegisteredWithALifecycleFilterThatExcludesTheSandbox {

        @Override
        public @Nullable Object runOnce() {
            return null;
        }
    }

    private static BusinessJob theLambdaAFactoryMethodReturns() {
        return () -> null;
    }

    private static BusinessJob theProxyAroundTheAnnotatedBean() {
        return (BusinessJob) new ProxyFactory(
                new JobRegisteredAsAnAnnotatedBeanThatDeclaresItselfUnscoped()).getProxy();
    }

    private static void scoreTheSealOver(BusinessJob... plants) {
        JobRegistry.requireEveryRegisteredJobSkipsTheSandboxDeployment(
                JobRegistry.indexByMeasuredNameRefusingATwin(List.of(plants)));
    }

    private static void assertTheSealRedsOn(BusinessJob plant, String expectedShape) {
        assertThatThrownBy(() -> scoreTheSealOver(plant))
                .isInstanceOf(RegisteredJobThatDoesNotSkipTheSandboxDeploymentException.class)
                .hasMessageContaining(expectedShape)
                .hasMessageContaining(JobRegistry.RESIDUAL_LIMIT_THIS_GUARD_DOES_NOT_COVER);
    }

    private static @Nullable URL classFileResourceOf(Class<?> type) {
        return type.getResource("/" + type.getName().replace('.', '/') + ".class");
    }

    private static List<String> scoreTheStaticScanThisRepositoryHadBeforeTheSealOver(
            Class<?>... plants) {
        return ScheduledLifecycleFilterCoverageTest.scheduledMethodsWithoutALifecycleDeclaration(
                new ClassFileImporter().importClasses(plants));
    }

    @Test
    void input_class_a_job_registered_as_an_annotated_bean_that_declares_itself_unscoped() {
        assertThat(scoreTheStaticScanThisRepositoryHadBeforeTheSealOver(
                JobRegisteredAsAnAnnotatedBeanThatDeclaresItselfUnscoped.class))
                .as("the static scan accepts @UnscopedScheduledJob, and an unscoped job runs "
                        + "inside every club including a demo seat club — three production jobs "
                        + "carried exactly this shape")
                .isEmpty();

        assertTheSealRedsOn(new JobRegisteredAsAnAnnotatedBeanThatDeclaresItselfUnscoped(),
                "planted-unscoped-annotated-bean");
    }

    @Test
    void input_class_a_job_registered_by_a_factory_method_with_no_scheduled_entry_point() {
        assertThat(scoreTheStaticScanThisRepositoryHadBeforeTheSealOver(
                JobRegisteredByAFactoryMethodWithNoScheduledEntryPoint.class))
                .as("the static scan reads @Scheduled methods only, so a job that /system/jobs "
                        + "Run-now is the sole entry point of scores nothing")
                .isEmpty();

        assertTheSealRedsOn(new JobRegisteredByAFactoryMethodWithNoScheduledEntryPoint(),
                "planted-run-now-only-factory-bean");
    }

    @Test
    void input_class_a_job_registered_as_a_lambda_that_a_factory_method_returns() {
        Class<?> lambdaClass = theLambdaAFactoryMethodReturns().getClass();

        assertThat(classFileResourceOf(lambdaClass))
                .as("a lambda class exists only in memory, so no class file backs it")
                .isNull();
        assertThat(scoreTheStaticScanThisRepositoryHadBeforeTheSealOver(lambdaClass))
                .as("the static scan reads class files, so it scores nothing for a job that a "
                        + "factory method returns as a lambda")
                .isEmpty();

        assertThatThrownBy(() -> scoreTheSealOver(theLambdaAFactoryMethodReturns()))
                .as("the seal scores the injected bean, so it reaches a job the static scan "
                        + "cannot import")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is missing @MeasuredJob");
    }

    @Test
    void input_class_a_job_registered_as_a_subclass_that_inherits_the_measured_name() {
        assertThat(scoreTheStaticScanThisRepositoryHadBeforeTheSealOver(
                JobRegisteredWithALifecycleFilterThatExcludesTheSandbox.class,
                JobRegisteredAsASubclassThatInheritsTheMeasuredNameOfItsParent.class))
                .as("the static scan reads annotations, never registration, so two beans under "
                        + "one job name score nothing")
                .isEmpty();

        assertThatThrownBy(() -> scoreTheSealOver(
                new JobRegisteredWithALifecycleFilterThatExcludesTheSandbox(),
                new JobRegisteredAsASubclassThatInheritsTheMeasuredNameOfItsParent()))
                .isInstanceOf(TwoRegisteredJobsUnderOneNameException.class)
                .hasMessageContaining("planted-lifecycle-filtered-bean")
                .hasMessageContaining(JobRegistry.RESIDUAL_LIMIT_THIS_GUARD_DOES_NOT_COVER);
    }

    @Test
    void input_class_a_job_registered_behind_a_spring_aop_proxy() {
        BusinessJob proxied = theProxyAroundTheAnnotatedBean();

        assertThat(proxied.getClass().isAnnotationPresent(MeasuredJob.class))
                .as("the proxy class inherits no class-level annotation, so a naive "
                        + "getClass() read finds no job at all")
                .isFalse();
        assertThat(classFileResourceOf(proxied.getClass()))
                .as("the generated proxy class carries no class file either")
                .isNull();
        assertThat(scoreTheStaticScanThisRepositoryHadBeforeTheSealOver(proxied.getClass()))
                .as("so the static scan scores nothing for the bean the container injects")
                .isEmpty();

        assertTheSealRedsOn(proxied, "planted-unscoped-annotated-bean");
    }

    @Test
    void input_class_a_job_class_that_ships_inside_a_classpath_jar() throws Exception {
        assertThat(Class.forName(CLASS_FROM_THE_MIGRATION_BUNDLE_JAR))
                .as("the component scan root ch.alpenflight reaches the included-build jar, so a "
                        + "@Component BusinessJob added there registers")
                .isNotNull();

        JavaClasses whatTheStaticScanSees = productionClasses();

        assertThat(whatTheStaticScanSees.stream()
                .map(JavaClass::getName)
                .filter(name -> name.startsWith("ch.alpenflight.migration.bundle."))
                .toList())
                .as("DoNotIncludeJars hides every ch.alpenflight class of that jar, so the "
                        + "static scan sees none of them; the seal scores injected beans, so an "
                        + "origin cannot hide one")
                .isEmpty();
    }

    @Test
    void input_class_a_job_whose_lifecycle_filter_names_the_sandbox_state() {
        assertThat(scoreTheStaticScanThisRepositoryHadBeforeTheSealOver(
                JobRegisteredWithALifecycleFilterThatNamesTheSandbox.class))
                .as("the static scan asks for a non-empty filter and reads no state, so a filter "
                        + "that names SANDBOX scores nothing")
                .isEmpty();

        assertTheSealRedsOn(new JobRegisteredWithALifecycleFilterThatNamesTheSandbox(),
                "which names SANDBOX");
    }

    @Test
    void a_job_whose_lifecycle_filter_excludes_the_sandbox_passes_the_seal() {
        assertThatCode(() -> scoreTheSealOver(
                new JobRegisteredWithALifecycleFilterThatExcludesTheSandbox()))
                .doesNotThrowAnyException();
    }

    @Test
    void the_two_sweep_jobs_red_this_seal_without_their_named_reason() {
        assertThatThrownBy(() -> JobRegistry
                .requireEveryRegisteredJobClassSkipsTheSandboxDeployment(Map.of(
                        "licence-notification-before-its-query-excluded-the-sandbox",
                        LicenceNotificationJob.class,
                        "aircraft-database-sync-before-its-query-excluded-the-sandbox",
                        AircraftDatabaseSyncJob.class)))
                .as("both jobs carry @UnscopedScheduledJob and no lifecycle filter, so the seal "
                        + "reds on the production class unless the map names why it may run")
                .isInstanceOf(RegisteredJobThatDoesNotSkipTheSandboxDeploymentException.class)
                .hasMessageContaining(LicenceNotificationJob.class.getName())
                .hasMessageContaining(AircraftDatabaseSyncJob.class.getName());
    }

    @Test
    void every_job_this_application_registers_passes_the_seal() {
        Map<String, Class<?>> registered = new LinkedHashMap<>();
        for (JavaClass measured : productionClasses().stream()
                .filter(c -> c.isAnnotatedWith(MeasuredJob.class)).toList()) {
            registered.put(measured.getAnnotationOfType(MeasuredJob.class).name(),
                    measured.reflect());
        }

        assertThat(registered).hasSizeGreaterThanOrEqualTo(EIGHT_JOBS_REGISTER_TODAY);
        assertThatCode(() -> JobRegistry
                .requireEveryRegisteredJobClassSkipsTheSandboxDeployment(registered))
                .doesNotThrowAnyException();
    }

    @Test
    void every_named_exemption_belongs_to_a_job_this_application_registers() {
        Set<String> measuredJobNames = productionClasses()
                .stream()
                .filter(c -> c.isAnnotatedWith(MeasuredJob.class))
                .map(c -> c.getAnnotationOfType(MeasuredJob.class).name())
                .collect(Collectors.toUnmodifiableSet());

        assertThat(JobRegistry.JOBS_THAT_CARRY_NO_LIFECYCLE_STATE_FILTER.keySet())
                .as("an exemption for a job that no longer registers is a hole that a renamed or "
                        + "deleted job leaves behind")
                .isSubsetOf(measuredJobNames);
    }

    @Test
    void the_named_exemptions_stay_the_four_this_journey_measured() {
        assertThat(JobRegistry.JOBS_THAT_CARRY_NO_LIFECYCLE_STATE_FILTER)
                .as("a new entry here is a job that reaches the demo seat clubs unless its own "
                        + "query excludes them; add one only with the test that proves it")
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "sandbox-reset",
                        JobRegistry.HowTheRegisteredJobSkipsTheSandboxDeployment
                                .THE_JOB_EXISTS_TO_RESET_THE_SANDBOX_SEATS,
                        "client-ip-retention",
                        JobRegistry.HowTheRegisteredJobSkipsTheSandboxDeployment
                                .THE_JOB_REDACTS_CLIENT_IPS_ON_AUDIT_ROWS_THE_SANDBOX_PURGE_MAY_NOT_DELETE,
                        "aircraft-database-sync",
                        JobRegistry.HowTheRegisteredJobSkipsTheSandboxDeployment
                                .THE_ONLY_QUERY_OF_THE_JOB_EXCLUDES_EVERY_ROW_OF_A_SANDBOX_CLUB,
                        "licence-notification",
                        JobRegistry.HowTheRegisteredJobSkipsTheSandboxDeployment
                                .THE_ONLY_QUERY_OF_THE_JOB_EXCLUDES_EVERY_ROW_OF_A_SANDBOX_CLUB));
    }
}
