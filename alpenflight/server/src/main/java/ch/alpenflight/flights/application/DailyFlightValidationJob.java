package ch.alpenflight.flights.application;

import ch.alpenflight.clubs.domain.Club;
import ch.alpenflight.deployments.application.DeploymentContext;
import ch.alpenflight.deployments.application.LifecycleStateFilter;
import ch.alpenflight.deployments.domain.Deployment;
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

/**
 * Daily flight validation + lock (S-083) — mirrors legacy
 * {@code DailyFlightValidationJob.cs}, which iterates clubs and calls
 * {@code FlightService.ValidateFlights(clubId)} then
 * {@code FlightService.LockFlights(clubId)} for each.
 *
 * <ul>
 *   <li><strong>Validate.</strong> Every {@code NOT_PROCESSED} or {@code INVALID}
 *       flight is re-run through {@link ch.alpenflight.flights.domain.FlightValidator}
 *       and lands {@code VALID} or {@code INVALID}
 *       ({@code FlightService.cs:909-946}).</li>
 *   <li><strong>Lock.</strong> Every {@code VALID} flight past the lock gate moves
 *       to {@code LOCKED}, cascading to a linked tow so the accounting pair stays
 *       in step ({@code FlightService.cs:1145-1184}).</li>
 * </ul>
 *
 * <p><strong>Lock gate.</strong> {@link FlightGatePolicy#canLock} owns the
 * threshold — {@code flight_date <= today - 2}, the J-2 parity decision that
 * replaced legacy's {@code created_on} keying. Selecting on the same policy the
 * transition service enforces keeps the two from disagreeing.
 *
 * <p><strong>Validation scope.</strong> Legacy narrows the {@code INVALID} arm to
 * flights modified since their last validation ({@code ModifiedOn >= ValidatedOn}).
 * Nothing maintains {@code t_flight.modified_on} here, so that predicate would
 * freeze at insert time and strand genuinely-fixed flights as {@code INVALID};
 * every {@code INVALID} flight is re-validated instead. The outcome set only ever
 * moves flights toward the state their data warrants.
 *
 * <p><strong>Isolation.</strong> Per-flight work goes through
 * {@link FlightStateTransitionService}, whose own transaction bounds each flight,
 * and a throw is logged and stepped over — one unprocessable flight never aborts
 * the batch (legacy wraps each pass in try/catch for the same reason). This method
 * is deliberately NOT {@code @Transactional}: joining one outer transaction would
 * let a single failure poison the whole club's work.
 */
@Component
@MeasuredJob(name = DailyFlightValidationJob.JOB_NAME,
        cron = DailyFlightValidationJob.CRON,
        description = "Daily flight validation + lock")
public class DailyFlightValidationJob implements BusinessJob {

    private static final Logger LOG = LoggerFactory.getLogger(DailyFlightValidationJob.class);

    /** Stable registry key — see {@link MeasuredJob#name()}. */
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

    /**
     * Scheduled tick. {@code LifecycleStateFilterAspect} re-enters
     * {@link #runForCurrentClub()} once per {@code ACTIVE} Club with that Club's
     * tenant context established, so the body itself runs per-club.
     */
    @Scheduled(cron = CRON)
    @LifecycleStateFilter({LifecycleState.ACTIVE})
    public void runScheduled() {
        runForCurrentClub();
    }

    /**
     * Cross-tenant "Run now" for the {@code /system/jobs} console: opens every
     * {@code ACTIVE} Deployment's Clubs in turn and folds their counts into one
     * summary, which the {@code MeasuredJobAspect} records as the last-run summary.
     */
    @Override
    public RunSummary runOnce() {
        RunSummary total = RunSummary.empty();
        for (Deployment deployment : deploymentContext.findDeployment(LifecycleState.ACTIVE)) {
            UUID deploymentId = deployment.getId();
            if (deploymentId == null) {
                continue;
            }
            RunSummary[] acc = {RunSummary.empty()};
            deploymentContext.forEachClub(deploymentId, club -> acc[0] = acc[0].plus(runFor(club)));
            total = total.plus(acc[0]);
        }
        return total;
    }

    private RunSummary runFor(Club club) {
        try {
            return runForCurrentClub();
        } catch (RuntimeException e) {
            LOG.error("daily-flight-validation failed for club {} — continuing", club.getId(), e);
            return RunSummary.empty();
        }
    }

    /** Both passes for the club in the current tenant context. */
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

    /**
     * Locks every gate-eligible {@code VALID} flight. A glider drags its tow along
     * ({@link FlightStateTransitionService#transitionWithTowCascade}), so tows the
     * cascade already claims are skipped — a second transition of an
     * already-{@code LOCKED} flight is not a legal edge.
     */
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

    /**
     * Non-PII run summary the console surfaces as the last-run outcome: how many
     * flights the pass validated, invalidated, and locked.
     */
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
