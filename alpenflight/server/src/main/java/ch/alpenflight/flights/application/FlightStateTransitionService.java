package ch.alpenflight.flights.application;

import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
import ch.alpenflight.flights.domain.Flight;
import ch.alpenflight.flights.domain.FlightGateNotReachedException;
import ch.alpenflight.flights.domain.FlightInitialStateProvider;
import ch.alpenflight.flights.domain.FlightNotFoundException;
import ch.alpenflight.flights.domain.FlightProcessState;
import ch.alpenflight.flights.domain.FlightRepository;
import ch.alpenflight.flights.domain.TransitionTrigger;
import ch.alpenflight.platform.id.FlightId;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates Flight process-state transitions: loads the aggregate,
 * delegates legality + mutation to {@link Flight#transition}, persists,
 * emits a {@link AuditAction#STATE_TRANSITION} audit row.
 *
 * <p>The {@link #transitionWithTowCascade(FlightId, FlightProcessState, TransitionTrigger)}
 * variant additionally drives the paired tow flight through the same
 * transition in one transaction — matches legacy
 * {@code DeliveryService.cs:191-196} / {@code FlightService.cs:1008-1014}.
 * Operator-driven transitions use the plain {@link #transition} so the
 * caller sees exactly what they're changing.
 *
 * <p>Initial-state provider is referenced so the service participates in
 * the bean graph that owns the canonical UUIDs; the field isn't read
 * directly here but its presence keeps the design coherent with
 * {@link FlightsService}.
 */
@Service
@Transactional
public class FlightStateTransitionService {

    private static final String AUDIT_ENTITY_TYPE = "Flight";

    private final FlightRepository repository;
    private final AuditTrail audit;
    private final FlightGatePolicy gatePolicy;
    private final Clock clock;

    @SuppressWarnings("UnusedVariable")
    private final FlightInitialStateProvider initialState;

    public FlightStateTransitionService(FlightRepository repository,
                                        AuditTrail audit,
                                        FlightGatePolicy gatePolicy,
                                        Clock clock,
                                        FlightInitialStateProvider initialState) {
        this.repository = repository;
        this.audit = audit;
        this.gatePolicy = gatePolicy;
        this.clock = clock;
        this.initialState = initialState;
    }

    /**
     * Transition a single flight. Does not cascade to a linked tow —
     * operator endpoints use this so each PATCH affects exactly one row.
     */
    public Flight transition(FlightId id,
                             FlightProcessState target,
                             TransitionTrigger trigger) {
        Flight flight = repository.findByIdWithCrew(id)
                .orElseThrow(() -> new FlightNotFoundException(id));
        FlightProcessState before = flight.getProcessState();
        Instant now = clock.instant();
        assertTimeGate(flight, before, target, now);
        flight.transition(target, trigger, now);
        Flight saved = repository.save(flight);
        audit.record(AuditAction.STATE_TRANSITION,
                new AuditedTarget(AUDIT_ENTITY_TYPE,
                        Objects.requireNonNull(saved.getId()),
                        null,
                        new StateTransitionPayload(before, target, trigger)));
        return saved;
    }

    /**
     * Transition a glider and its paired tow flight together. If no tow
     * is linked, behaves identically to {@link #transition}. Used by
     * system-driven paths (validator / lock job / delivery prep /
     * booking) — not exposed via the operator HTTP surface.
     */
    public void transitionWithTowCascade(FlightId gliderId,
                                         FlightProcessState target,
                                         TransitionTrigger trigger) {
        Flight glider = repository.findByIdWithCrew(gliderId)
                .orElseThrow(() -> new FlightNotFoundException(gliderId));
        FlightProcessState gliderBefore = glider.getProcessState();
        Instant now = clock.instant();
        assertTimeGate(glider, gliderBefore, target, now);
        glider.transition(target, trigger, now);
        repository.save(glider);
        audit.record(AuditAction.STATE_TRANSITION,
                new AuditedTarget(AUDIT_ENTITY_TYPE,
                        Objects.requireNonNull(glider.getId()),
                        null,
                        new StateTransitionPayload(gliderBefore, target, trigger)));

        if (glider.getTowFlightId() == null) {
            return;
        }
        FlightId towId = FlightId.of(glider.getTowFlightId());
        Flight tow = repository.findByIdWithCrew(towId)
                .orElseThrow(() -> new FlightNotFoundException(towId));
        FlightProcessState towBefore = tow.getProcessState();
        assertTimeGate(tow, towBefore, target, now);
        tow.transition(target, trigger, now);
        repository.save(tow);
        audit.record(AuditAction.STATE_TRANSITION,
                new AuditedTarget(AUDIT_ENTITY_TYPE,
                        Objects.requireNonNull(tow.getId()),
                        null,
                        new StateTransitionPayload(towBefore, target, trigger)));
    }

    /**
     * Enforces the S-061 time-gate on the two gated edges. Other
     * transitions pass through untouched. The matrix legality check still
     * runs inside {@link Flight#transition} — this only adds the calendar
     * gate, so an illegal edge surfaces as {@code IllegalFlightTransition},
     * a too-recent legal edge as {@link FlightGateNotReachedException}.
     */
    private void assertTimeGate(Flight flight,
                                FlightProcessState from,
                                FlightProcessState target,
                                Instant now) {
        if (from == FlightProcessState.VALID
                && target == FlightProcessState.LOCKED
                && !gatePolicy.canLock(flight, now)) {
            throw new FlightGateNotReachedException(FlightGateNotReachedException.Gate.LOCK);
        }
        if (from == FlightProcessState.LOCKED
                && target == FlightProcessState.DELIVERY_PREPARED
                && !gatePolicy.canBill(flight, now)) {
            throw new FlightGateNotReachedException(FlightGateNotReachedException.Gate.BILL);
        }
    }

    /**
     * Audit payload for a state transition. Recorded as
     * {@code AuditedTarget#after} so the change is visible without
     * before / after entity diffs — the per-row mutation is the
     * transition itself, not a content edit.
     */
    public record StateTransitionPayload(FlightProcessState from,
                                         FlightProcessState to,
                                         TransitionTrigger trigger) {}
}
