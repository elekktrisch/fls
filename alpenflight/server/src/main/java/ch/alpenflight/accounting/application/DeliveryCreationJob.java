package ch.alpenflight.accounting.application;

import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.deployments.application.DeploymentContext;
import ch.alpenflight.deployments.application.LifecycleStateFilter;
import ch.alpenflight.deployments.domain.Deployment;
import ch.alpenflight.deployments.domain.LifecycleState;
import ch.alpenflight.platform.scheduling.BusinessJob;
import ch.alpenflight.platform.scheduling.MeasuredJob;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly delivery creation (S-089) — the cron/console face of
 * {@link DeliveryCreationService#createFromEligibleFlights()}, mirroring legacy
 * {@code DeliveryCreationJob.cs} which iterates clubs and calls
 * {@code DeliveryService.CreateDeliveriesFromFlights(clubId)} for each.
 *
 * <p>All the behaviour lives in the service: eligibility ({@code LOCKED} +
 * billable aircraft type + {@code created_on <= today - 3d}), the per-flight
 * {@code DELIVERY_PREPARED} flip with its tow, and the per-flight failure
 * outcomes ({@code DELIVERY_PREPARATION_ERROR}, or
 * {@code EXCLUDED_FROM_DELIVERY_PROCESS} on a do-not-invoice rule match). This
 * class only supplies the schedule and the per-club tenant windows.
 */
@Component
@MeasuredJob(name = DeliveryCreationJob.JOB_NAME,
        cron = DeliveryCreationJob.CRON,
        description = "Delivery creation from eligible locked flights")
public class DeliveryCreationJob implements BusinessJob {

    private static final Logger LOG = LoggerFactory.getLogger(DeliveryCreationJob.class);

    /** Stable registry key — see {@link MeasuredJob#name()}. */
    public static final String JOB_NAME = "delivery-creation";

    static final String CRON = "0 30 2 * * *";

    private final DeliveryCreationService creationService;
    private final DeploymentContext deploymentContext;

    public DeliveryCreationJob(DeliveryCreationService creationService,
                               DeploymentContext deploymentContext) {
        this.creationService = creationService;
        this.deploymentContext = deploymentContext;
    }

    /**
     * Scheduled tick. {@code LifecycleStateFilterAspect} re-enters
     * {@link #runForCurrentClub()} once per {@code ACTIVE} Club under that Club's
     * tenant context.
     */
    @Scheduled(cron = CRON)
    @LifecycleStateFilter({LifecycleState.ACTIVE})
    public void runScheduled() {
        runForCurrentClub();
    }

    /** Cross-tenant "Run now" for the {@code /system/jobs} console. */
    @Override
    public RunSummary runOnce() {
        int created = 0;
        for (Deployment deployment : deploymentContext.findDeployment(LifecycleState.ACTIVE)) {
            UUID deploymentId = deployment.getId();
            if (deploymentId == null) {
                continue;
            }
            int[] acc = {0};
            deploymentContext.forEachClub(deploymentId, club -> acc[0] += runFor(club));
            created += acc[0];
        }
        return new RunSummary(created);
    }

    private int runFor(Club club) {
        try {
            return runForCurrentClub().createdCount();
        } catch (RuntimeException e) {
            LOG.error("delivery-creation failed for club {} — continuing", club.getId(), e);
            return 0;
        }
    }

    /** Creates deliveries for the club in the current tenant context. */
    public RunSummary runForCurrentClub() {
        return new RunSummary(creationService.createFromEligibleFlights().size());
    }

    /** Non-PII run summary: how many deliveries the pass persisted. */
    public record RunSummary(int createdCount) {

        @Override
        public String toString() {
            return createdCount + " deliveries created";
        }
    }
}
