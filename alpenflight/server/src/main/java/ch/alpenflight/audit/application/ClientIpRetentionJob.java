package ch.alpenflight.audit.application;

import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.platform.scheduling.BusinessJob;
import ch.alpenflight.platform.scheduling.MeasuredJob;
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
    private final ObjectProvider<ClientIpRetentionJob> self;

    public ClientIpRetentionJob(ClientIpRedaction redaction,
                                ClubRepository clubs,
                                ObjectProvider<ClientIpRetentionJob> self) {
        this.redaction = redaction;
        this.clubs = clubs;
        this.self = self;
    }

    @Scheduled(cron = CRON)
    @UnscopedScheduledJob
    public void runScheduled() {
        self.getObject().runOnce();
    }

    @Override
    public RunSummary runOnce() {
        int redacted = 0;
        int tenantsThatFailed = 0;
        for (UUID clubId : clubs.idsOfEveryClubIncludingTheSoftDeleted()) {
            try {
                redacted += Tenants.runAs(clubId,
                        redaction::redactEveryClientIpPastRetentionInTheCurrentTenant);
            } catch (RuntimeException failureOfOneTenantThatMustNotStopTheOthers) {
                tenantsThatFailed++;
                LOG.error("{} failed for club {} — continuing with the next tenant",
                        JOB_NAME, clubId, failureOfOneTenantThatMustNotStopTheOthers);
            }
        }
        return new RunSummary(redacted, tenantsThatFailed);
    }

    public record RunSummary(int redactedClientIpCount, int failedTenantCount) {

        @Override
        public String toString() {
            return redactedClientIpCount + " client IPs redacted past the retention window, "
                    + failedTenantCount + " tenants failed";
        }
    }
}
