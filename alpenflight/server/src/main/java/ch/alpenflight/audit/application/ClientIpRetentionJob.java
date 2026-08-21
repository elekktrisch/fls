package ch.alpenflight.audit.application;

import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.platform.scheduling.BusinessJob;
import ch.alpenflight.platform.scheduling.MeasuredJob;
import ch.alpenflight.platform.scheduling.SelfProxy;
import ch.alpenflight.platform.scheduling.UnscopedScheduledJob;
import ch.alpenflight.platform.tenancy.Tenants;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@MeasuredJob(name = ClientIpRetentionJob.JOB_NAME,
        cronShownInConsole = ClientIpRetentionJob.CRON,
        description = "Anonymous client-IP redaction once the 90-day retention window has elapsed")
public class ClientIpRetentionJob implements BusinessJob {

    private static final Logger LOG = LoggerFactory.getLogger(ClientIpRetentionJob.class);

    public static final String JOB_NAME = "client-ip-retention";

    static final String CRON = "0 15 3 * * *";

    private final ClientIpRedaction redaction;
    private final ClubRepository clubs;
    private final SelfProxy<ClientIpRetentionJob> self;

    public ClientIpRetentionJob(ClientIpRedaction redaction,
                                ClubRepository clubs,
                                ObjectProvider<ClientIpRetentionJob> self) {
        this.redaction = redaction;
        this.clubs = clubs;
        this.self = SelfProxy.around(self);
    }

    @Scheduled(cron = CRON)
    @UnscopedScheduledJob
    public void runScheduled() {
        self.soTheJobRunRecordIsWritten().runOnce();
    }

    @Override
    public RunSummary runOnce() {
        int redacted = 0;
        int sweepsThatFailed = 0;
        for (UUID clubId : clubs.idsOfEveryClubIncludingTheSoftDeleted()) {
            try {
                redacted += Tenants.runAs(clubId,
                        redaction::redactEveryClientIpPastRetentionInTheCurrentTenant);
            } catch (RuntimeException failureOfOneSweepThatMustNotStopTheOthers) {
                sweepsThatFailed++;
                LOG.error("{} failed for club {} — continuing with the next sweep",
                        JOB_NAME, clubId, failureOfOneSweepThatMustNotStopTheOthers);
            }
        }
        try {
            redacted += redaction.redactEveryClientIpPastRetentionOnARowNoClubTenantOwns();
        } catch (RuntimeException failureOfTheSweepForRowsOutsideEveryClub) {
            sweepsThatFailed++;
            LOG.error("{} failed for the rows no club owns — those client IPs stay until the "
                            + "next run", JOB_NAME, failureOfTheSweepForRowsOutsideEveryClub);
        }
        return new RunSummary(redacted, sweepsThatFailed);
    }

    public record RunSummary(int redactedClientIpCount, int failedSweepCount) {

        @Override
        public String toString() {
            return redactedClientIpCount + " client IPs redacted past the retention window, "
                    + failedSweepCount + " sweeps failed";
        }
    }
}
