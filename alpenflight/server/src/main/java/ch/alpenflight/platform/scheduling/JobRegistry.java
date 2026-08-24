package ch.alpenflight.platform.scheduling;

import ch.alpenflight.deployments.application.LifecycleStateFilter;
import ch.alpenflight.deployments.domain.LifecycleState;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

@Component
public class JobRegistry {

    public enum HowTheRegisteredJobSkipsTheSandboxDeployment {
        THE_JOB_EXISTS_TO_RESET_THE_SANDBOX_SEATS,
        THE_JOB_REDACTS_CLIENT_IPS_ON_AUDIT_ROWS_THE_SANDBOX_PURGE_MAY_NOT_DELETE,
        THE_ONLY_QUERY_OF_THE_JOB_EXCLUDES_EVERY_ROW_OF_A_SANDBOX_CLUB
    }

    static final Map<String, HowTheRegisteredJobSkipsTheSandboxDeployment>
            JOBS_THAT_CARRY_NO_LIFECYCLE_STATE_FILTER = Map.of(
            "sandbox-reset",
            HowTheRegisteredJobSkipsTheSandboxDeployment.THE_JOB_EXISTS_TO_RESET_THE_SANDBOX_SEATS,
            "client-ip-retention",
            HowTheRegisteredJobSkipsTheSandboxDeployment
                    .THE_JOB_REDACTS_CLIENT_IPS_ON_AUDIT_ROWS_THE_SANDBOX_PURGE_MAY_NOT_DELETE,
            "aircraft-database-sync",
            HowTheRegisteredJobSkipsTheSandboxDeployment
                    .THE_ONLY_QUERY_OF_THE_JOB_EXCLUDES_EVERY_ROW_OF_A_SANDBOX_CLUB,
            "licence-notification",
            HowTheRegisteredJobSkipsTheSandboxDeployment
                    .THE_ONLY_QUERY_OF_THE_JOB_EXCLUDES_EVERY_ROW_OF_A_SANDBOX_CLUB);

    static final String RESIDUAL_LIMIT_THIS_GUARD_DOES_NOT_COVER =
            "Residual limit — what this guard does NOT score: it reads the DECLARATION of each "
                    + "registered BusinessJob, never the rows the job reaches. It reads the "
                    + "@LifecycleStateFilter of the most specific @Scheduled method and the "
                    + "JOBS_THAT_CARRY_NO_LIFECYCLE_STATE_FILTER map above. It does not read the "
                    + "runOnce() body that /system/jobs Run-now calls, it does not read the JPQL "
                    + "of a repository, and it scores nothing that no Spring bean registers as a "
                    + "BusinessJob. Each map entry is backed by its own named test: "
                    + "SandboxResetJobIT, "
                    + "EveryClientIpStaysReachableByTheRetentionSweepIT, "
                    + "AircraftDatabaseSyncJobIT and LicenceNotificationJobIT. Add a map entry "
                    + "only together with a test that measures how far the job reaches into the "
                    + "sandbox Deployment.";

    private final JobRunRepository jobRuns;
    private final Map<String, BusinessJob> byName;

    public JobRegistry(List<BusinessJob> jobs, JobRunRepository jobRuns) {
        this.jobRuns = jobRuns;
        this.byName = Map.copyOf(indexByMeasuredNameRefusingATwin(jobs));
        requireEveryRegisteredJobSkipsTheSandboxDeployment(this.byName);
    }

    public List<JobDescriptor> list() {
        return byName.entrySet().stream()
                .map(e -> descriptor(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(JobDescriptor::name))
                .toList();
    }

    public JobRun runOnce(String name) {
        BusinessJob job = byName.get(name);
        if (job == null) {
            throw new JobNotFoundException(name);
        }
        job.runOnce();
        return jobRuns.findLatestByJobName(name)
                .orElseThrow(() -> new IllegalStateException(
                        "No JobRun recorded for '" + name + "' after runOnce — is @MeasuredJob missing?"));
    }

    private JobDescriptor descriptor(String name, BusinessJob job) {
        MeasuredJob measured = measuredOf(job);
        return new JobDescriptor(name, measured.cronShownInConsole(), measured.description(),
                jobRuns.findLatestByJobName(name).orElse(null));
    }

    static Map<String, BusinessJob> indexByMeasuredNameRefusingATwin(List<BusinessJob> jobs) {
        Map<String, BusinessJob> map = new LinkedHashMap<>();
        for (BusinessJob job : jobs) {
            String name = measuredOf(job).name();
            BusinessJob alreadyRegistered = map.put(name, job);
            if (alreadyRegistered != null) {
                throw new TwoRegisteredJobsUnderOneNameException(name,
                        AopUtils.getTargetClass(alreadyRegistered).getName(),
                        AopUtils.getTargetClass(job).getName());
            }
        }
        return map;
    }

    static void requireEveryRegisteredJobSkipsTheSandboxDeployment(
            Map<String, BusinessJob> byMeasuredName) {
        Map<String, Class<?>> jobClassBehindEveryProxy = new LinkedHashMap<>();
        for (Map.Entry<String, BusinessJob> registered : byMeasuredName.entrySet()) {
            jobClassBehindEveryProxy.put(registered.getKey(),
                    AopUtils.getTargetClass(registered.getValue()));
        }
        requireEveryRegisteredJobClassSkipsTheSandboxDeployment(jobClassBehindEveryProxy);
    }

    static void requireEveryRegisteredJobClassSkipsTheSandboxDeployment(
            Map<String, Class<?>> byMeasuredName) {
        List<String> jobsThatReachTheSandboxDeployment = new ArrayList<>();
        for (Map.Entry<String, Class<?>> registered
                : new TreeMap<>(byMeasuredName).entrySet()) {
            String name = registered.getKey();
            if (JOBS_THAT_CARRY_NO_LIFECYCLE_STATE_FILTER.containsKey(name)) {
                continue;
            }
            Class<?> jobClass = registered.getValue();
            Set<LifecycleState> declared = lifecycleStatesDeclaredOnTheScheduledEntryPoints(
                    jobClass);
            if (declared.isEmpty()) {
                jobsThatReachTheSandboxDeployment.add(name + " (" + jobClass.getName()
                        + ") declares no non-empty @LifecycleStateFilter on the most specific "
                        + "@Scheduled method it registers, and names no reason in "
                        + "JOBS_THAT_CARRY_NO_LIFECYCLE_STATE_FILTER");
            } else if (declared.contains(LifecycleState.SANDBOX)) {
                jobsThatReachTheSandboxDeployment.add(name + " (" + jobClass.getName()
                        + ") declares @LifecycleStateFilter " + new TreeSet<>(declared)
                        + ", which names SANDBOX, so the job runs inside the demo seat clubs");
            }
        }
        if (!jobsThatReachTheSandboxDeployment.isEmpty()) {
            throw new RegisteredJobThatDoesNotSkipTheSandboxDeploymentException(
                    List.copyOf(jobsThatReachTheSandboxDeployment));
        }
    }

    private static Set<LifecycleState> lifecycleStatesDeclaredOnTheScheduledEntryPoints(
            Class<?> jobClass) {
        Set<LifecycleState> declared = new TreeSet<>();
        for (Method scheduled : mostSpecificScheduledMethodsOf(jobClass)) {
            LifecycleStateFilter filter = scheduled.getAnnotation(LifecycleStateFilter.class);
            if (filter == null) {
                return Set.of();
            }
            List<LifecycleState> states = Arrays.asList(filter.value());
            if (states.isEmpty()) {
                return Set.of();
            }
            declared.addAll(states);
        }
        return declared;
    }

    private static List<Method> mostSpecificScheduledMethodsOf(Class<?> jobClass) {
        List<Method> mostSpecific = new ArrayList<>();
        for (Class<?> level = jobClass; level != null && level != Object.class;
                level = level.getSuperclass()) {
            for (Method declaredAtThisLevel : level.getDeclaredMethods()) {
                if (declaredAtThisLevel.isAnnotationPresent(Scheduled.class)) {
                    mostSpecific.add(ClassUtils.getMostSpecificMethod(
                            declaredAtThisLevel, jobClass));
                }
            }
        }
        return mostSpecific;
    }

    private static MeasuredJob measuredOf(BusinessJob job) {
        MeasuredJob measured = AnnotationUtils.findAnnotation(
                AopUtils.getTargetClass(job), MeasuredJob.class);
        if (measured == null) {
            throw new IllegalStateException(
                    "BusinessJob " + job.getClass().getName() + " is missing @MeasuredJob");
        }
        return measured;
    }

    public record JobDescriptor(String name, String cron, String description,
                                @Nullable JobRun lastRun) {
    }

    public static final class JobNotFoundException extends RuntimeException {
        public JobNotFoundException(String name) {
            super("No registered job named '" + name + "'");
        }
    }

    public static final class TwoRegisteredJobsUnderOneNameException extends RuntimeException {
        public TwoRegisteredJobsUnderOneNameException(String name,
                                                      String firstJobClass,
                                                      String secondJobClass) {
            super("two BusinessJob beans register under the @MeasuredJob name '" + name + "': "
                    + firstJobClass + " and " + secondJobClass + ". One of them would win the "
                    + "registry silently, and it would carry the sandbox-deployment decision of "
                    + "the other. Give each job its own name. "
                    + RESIDUAL_LIMIT_THIS_GUARD_DOES_NOT_COVER);
        }
    }

    public static final class RegisteredJobThatDoesNotSkipTheSandboxDeploymentException
            extends RuntimeException {
        public RegisteredJobThatDoesNotSkipTheSandboxDeploymentException(
                List<String> jobsThatReachTheSandboxDeployment) {
            super("these registered BusinessJob beans do not skip the sandbox Deployment, so "
                    + "they would run business behaviour inside the demo seat clubs: "
                    + jobsThatReachTheSandboxDeployment
                    + ". Put a non-empty @LifecycleStateFilter that does not name SANDBOX on the "
                    + "@Scheduled entry point, or name the job in "
                    + "JOBS_THAT_CARRY_NO_LIFECYCLE_STATE_FILTER with the reason it may run "
                    + "there. "
                    + RESIDUAL_LIMIT_THIS_GUARD_DOES_NOT_COVER);
        }
    }
}
