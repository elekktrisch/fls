package ch.alpenflight.accounting.application;

import ch.alpenflight.deployments.application.DeploymentContext;
import ch.alpenflight.deployments.application.LifecycleStateFilter;
import ch.alpenflight.deployments.domain.LifecycleState;
import ch.alpenflight.platform.scheduling.BusinessJob;
import ch.alpenflight.platform.scheduling.MeasuredJob;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@MeasuredJob(name = DeliveryCreationJob.JOB_NAME,
        cron = DeliveryCreationJob.CRON,
        description = "Delivery creation from eligible locked flights")
public class DeliveryCreationJob implements BusinessJob {

    public static final String JOB_NAME = "delivery-creation";

    static final String CRON = "0 30 2 * * *";

    private final DeliveryCreationService creationService;
    private final DeploymentContext deploymentContext;

    public DeliveryCreationJob(DeliveryCreationService creationService,
                               DeploymentContext deploymentContext) {
        this.creationService = creationService;
        this.deploymentContext = deploymentContext;
    }

    @Scheduled(cron = CRON)
    @LifecycleStateFilter({LifecycleState.ACTIVE})
    public void runScheduled() {
        runForCurrentClub();
    }

    @Override
    public RunSummary runOnce() {
        return new RunSummary(deploymentContext.foldOverClubs(JOB_NAME, 0,
                (total, club) -> total + runForCurrentClub().createdCount(),
                LifecycleState.ACTIVE));
    }

    public RunSummary runForCurrentClub() {
        return new RunSummary(creationService.createFromEligibleFlights().size());
    }

    public record RunSummary(int createdCount) {

        @Override
        public String toString() {
            return createdCount + " deliveries created";
        }
    }
}
