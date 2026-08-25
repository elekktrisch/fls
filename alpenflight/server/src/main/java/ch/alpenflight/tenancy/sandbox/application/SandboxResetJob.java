package ch.alpenflight.tenancy.sandbox.application;

import ch.alpenflight.platform.scheduling.BusinessJob;
import ch.alpenflight.platform.scheduling.MeasuredJob;
import ch.alpenflight.platform.scheduling.SelfProxy;
import ch.alpenflight.platform.scheduling.UnscopedScheduledJob;
import ch.alpenflight.tenancy.sandbox.application.SandboxSeatResetService.SeatResetSummary;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@MeasuredJob(name = SandboxResetJob.JOB_NAME,
        cronShownInConsole = SandboxResetJob.CRON,
        description = "Demo seats — delete and re-seed every sandbox club that holds no live lease")
public class SandboxResetJob implements BusinessJob {

    public static final String JOB_NAME = "sandbox-reset";

    static final String CRON = "0 45 3 * * *";

    private final SandboxSeatResetService reset;
    private final SelfProxy<SandboxResetJob> self;

    public SandboxResetJob(SandboxSeatResetService reset,
                           ObjectProvider<SandboxResetJob> self) {
        this.reset = reset;
        this.self = SelfProxy.around(self);
    }

    @Scheduled(cron = CRON)
    @UnscopedScheduledJob
    public void runScheduled() {
        self.soTheJobRunRecordIsWritten().runOnce();
    }

    @Override
    public SeatResetSummary runOnce() {
        return reset.resetEverySeatThatHoldsNoLiveLease();
    }
}
