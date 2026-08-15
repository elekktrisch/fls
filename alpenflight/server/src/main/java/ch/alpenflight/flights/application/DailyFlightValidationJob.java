package ch.alpenflight.flights.application;

import ch.alpenflight.deployments.application.DeploymentContext;
import ch.alpenflight.deployments.application.LifecycleStateFilter;
import ch.alpenflight.deployments.domain.LifecycleState;
import ch.alpenflight.flights.domain.Flight;
import ch.alpenflight.flights.domain.FlightProcessState;
import ch.alpenflight.flights.domain.FlightRepository;
import ch.alpenflight.flights.domain.TransitionTrigger;
import ch.alpenflight.platform.id.FlightId;
import ch.alpenflight.platform.scheduling.BusinessJob;
import ch.alpenflight.platform.scheduling.MeasuredJob;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@MeasuredJob(name = DailyFlightValidationJob.JOB_NAME,
        cron = DailyFlightValidationJob.CRON,
        description = "Daily flight validation + lock")
public class DailyFlightValidationJob implements BusinessJob {

    private static final Logger LOG = LoggerFactory.getLogger(DailyFlightValidationJob.class);

    public static final String JOB_NAME = "daily-flight-validation";

    static final String CRON = "0 0 2 * * *";

    private final FlightRepository flights;
    private final FlightStateTransitionService transitions;
    private final FlightGatePolicy gatePolicy;
    private final DeploymentContext deploymentContext;
    private final Clock clock;

    public DailyFlightValidationJob(FlightRepository flights,
                                    FlightStateTransitionService transitions,
                                    FlightGatePolicy gatePolicy,
                                    DeploymentContext deploymentContext,
                                    Clock clock) {
        this.flights = flights;
        this.transitions = transitions;
        this.gatePolicy = gatePolicy;
        this.deploymentContext = deploymentContext;
        this.clock = clock;
    }

    @Scheduled(cron = CRON)
    @LifecycleStateFilter({LifecycleState.ACTIVE})
    public void runScheduled() {
        runForCurrentClub();
    }

    @Override
    public RunSummary runOnce() {
        return deploymentContext.foldOverClubs(JOB_NAME, RunSummary.empty(),
                (total, club) -> total.plus(runForCurrentClub()), LifecycleState.ACTIVE);
    }

    public RunSummary runForCurrentClub() {
        Instant now = clock.instant();
        ValidationCounts validated = validatePass();
        int locked = lockPass(now);
        return new RunSummary(validated.valid(), validated.invalid(), locked);
    }

    private ValidationCounts validatePass() {
        int valid = 0;
        int invalid = 0;
        for (UUID id : idsInState(FlightProcessState.NOT_PROCESSED, FlightProcessState.INVALID)) {
            try {
                FlightProcessState outcome = transitions.validateAndRecord(FlightId.of(id));
                if (outcome == FlightProcessState.VALID) {
                    valid++;
                } else {
                    invalid++;
                }
            } catch (RuntimeException e) {
                LOG.warn("validation skipped flight {}", id, e);
            }
        }
        return new ValidationCounts(valid, invalid);
    }

    private int lockPass(Instant now) {
        List<Flight> candidates = flights.findByProcessStateId(FlightProcessState.VALID.id());
        Set<UUID> cascadedTows = new HashSet<>();
        for (Flight flight : candidates) {
            UUID towId = flight.getTowFlightId();
            if (towId != null) {
                cascadedTows.add(towId);
            }
        }
        int locked = 0;
        for (Flight flight : candidates) {
            UUID id = flight.getId();
            if (id == null || cascadedTows.contains(id) || !gatePolicy.canLock(flight, now)) {
                continue;
            }
            try {
                transitions.transitionWithTowCascade(
                        FlightId.of(id), FlightProcessState.LOCKED, TransitionTrigger.LOCK_JOB);
                locked += flight.getTowFlightId() == null ? 1 : 2;
            } catch (RuntimeException e) {
                LOG.warn("lock skipped flight {}", id, e);
            }
        }
        return locked;
    }

    private List<UUID> idsInState(FlightProcessState... states) {
        List<UUID> ids = new ArrayList<>();
        for (FlightProcessState state : states) {
            for (Flight flight : flights.findByProcessStateId(state.id())) {
                UUID id = flight.getId();
                if (id != null) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    private record ValidationCounts(int valid, int invalid) {}

    public record RunSummary(int validatedCount, int invalidatedCount, int lockedCount) {

        static RunSummary empty() {
            return new RunSummary(0, 0, 0);
        }

        RunSummary plus(RunSummary other) {
            return new RunSummary(validatedCount + other.validatedCount,
                    invalidatedCount + other.invalidatedCount,
                    lockedCount + other.lockedCount);
        }

        @Override
        public String toString() {
            return validatedCount + " validated, " + invalidatedCount + " invalidated, "
                    + lockedCount + " locked";
        }
    }
}
