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
import ch.alpenflight.flights.domain.FlightValidator;
import ch.alpenflight.flights.domain.TransitionTrigger;
import ch.alpenflight.platform.id.FlightId;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public FlightProcessState validateAndRecord(FlightId id) {
        Flight flight = repository.findByIdWithCrew(id)
                .orElseThrow(() -> new FlightNotFoundException(id));
        FlightProcessState before = flight.getProcessState();
        flight.recordValidation(clock.instant(), FlightValidator.validate(flight));
        Flight saved = repository.save(flight);
        FlightProcessState after = saved.getProcessState();
        if (after != before) {
            audit.record(AuditAction.STATE_TRANSITION,
                    new AuditedTarget(AUDIT_ENTITY_TYPE,
                            Objects.requireNonNull(saved.getId()),
                            null,
                            new StateTransitionPayload(before, after, TransitionTrigger.VALIDATOR)));
        }
        return after;
    }

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

    public record StateTransitionPayload(FlightProcessState from,
                                         FlightProcessState to,
                                         TransitionTrigger trigger) {}
}
