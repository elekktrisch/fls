package ch.alpenflight.flights.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
import ch.alpenflight.flights.domain.Flight;
import ch.alpenflight.flights.domain.FlightAircraftType;
import ch.alpenflight.flights.domain.FlightInitialStateProvider;
import ch.alpenflight.flights.domain.FlightNotFoundException;
import ch.alpenflight.flights.domain.FlightOperationalData;
import ch.alpenflight.flights.domain.FlightProcessState;
import ch.alpenflight.flights.domain.FlightRepository;
import ch.alpenflight.flights.domain.IllegalFlightTransitionException;
import ch.alpenflight.flights.domain.TransitionTrigger;
import ch.alpenflight.platform.id.FlightId;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Service-layer contract for state transitions. Verifies audit emission
 * shape, exception propagation, and tow-cascade behavior. The matrix
 * itself is exercised in {@code FlightTransitionMatrixTest} — this test
 * focuses on the application-level wiring.
 */
class FlightStateTransitionServiceTest {

    private FlightRepository repo;
    private AuditTrail audit;
    private FlightStateTransitionService service;

    @BeforeEach
    void setUp() {
        repo = Mockito.mock(FlightRepository.class);
        audit = Mockito.mock(AuditTrail.class);
        FlightInitialStateProvider initial = new FlightInitialStateProvider() {
            @Override
            public UUID initialProcessStateId() {
                return FlightProcessState.NOT_PROCESSED.id();
            }

            @Override
            public UUID initialAirStateId() {
                return UUID.fromString("019e2e15-2c00-7e80-8000-000000003e80");
            }
        };
        service = new FlightStateTransitionService(repo, audit, initial);
    }

    @Test
    void operator_transition_emits_one_state_transition_audit_row() {
        Flight f = gliderInState(FlightProcessState.VALID);
        FlightId id = FlightId.of(UUID.randomUUID());
        when(repo.findByIdWithCrew(id)).thenReturn(Optional.of(f));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.transition(id, FlightProcessState.EXCLUDED_FROM_DELIVERY_PROCESS,
                TransitionTrigger.OPERATOR);

        verify(audit).record(eq(AuditAction.STATE_TRANSITION),
                argThat(t -> "Flight".equals(t.entityType())
                        && t.after() instanceof FlightStateTransitionService.StateTransitionPayload p
                        && p.from() == FlightProcessState.VALID
                        && p.to() == FlightProcessState.EXCLUDED_FROM_DELIVERY_PROCESS
                        && p.trigger() == TransitionTrigger.OPERATOR));
    }

    @Test
    void illegal_transition_throws_and_emits_no_audit() {
        Flight f = gliderInState(FlightProcessState.DELIVERY_BOOKED);
        FlightId id = FlightId.of(UUID.randomUUID());
        when(repo.findByIdWithCrew(id)).thenReturn(Optional.of(f));

        assertThatThrownBy(() -> service.transition(id,
                FlightProcessState.LOCKED, TransitionTrigger.OPERATOR))
                .isInstanceOf(IllegalFlightTransitionException.class);

        verify(audit, never()).record(any(), any());
        verify(repo, never()).save(any());
    }

    @Test
    void unknown_flight_throws_not_found_and_emits_no_audit() {
        FlightId id = FlightId.of(UUID.randomUUID());
        when(repo.findByIdWithCrew(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.transition(id,
                FlightProcessState.LOCKED, TransitionTrigger.LOCK_JOB))
                .isInstanceOf(FlightNotFoundException.class);
        verify(audit, never()).record(any(), any());
    }

    @Test
    void tow_cascade_transitions_both_glider_and_tow_in_one_call() {
        Flight glider = gliderInState(FlightProcessState.LOCKED);
        Flight tow = towInState(FlightProcessState.LOCKED);
        FlightId gliderId = FlightId.of(UUID.randomUUID());
        FlightId towId = FlightId.of(UUID.randomUUID());

        // Wire the tow link.
        setField(glider, "towFlightId", towId.value());

        when(repo.findByIdWithCrew(gliderId)).thenReturn(Optional.of(glider));
        when(repo.findByIdWithCrew(FlightId.of(towId.value()))).thenReturn(Optional.of(tow));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.transitionWithTowCascade(gliderId,
                FlightProcessState.DELIVERY_PREPARED, TransitionTrigger.DELIVERY_PREP);

        assertThat(glider.getProcessState()).isEqualTo(FlightProcessState.DELIVERY_PREPARED);
        assertThat(tow.getProcessState()).isEqualTo(FlightProcessState.DELIVERY_PREPARED);
        // One audit row per flight — operator can see both transitions.
        verify(audit, Mockito.times(2)).record(eq(AuditAction.STATE_TRANSITION), any());
    }

    @Test
    void tow_cascade_without_tow_link_only_transitions_glider() {
        Flight glider = gliderInState(FlightProcessState.LOCKED);
        FlightId gliderId = FlightId.of(UUID.randomUUID());
        when(repo.findByIdWithCrew(gliderId)).thenReturn(Optional.of(glider));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.transitionWithTowCascade(gliderId,
                FlightProcessState.DELIVERY_PREPARED, TransitionTrigger.DELIVERY_PREP);

        assertThat(glider.getProcessState()).isEqualTo(FlightProcessState.DELIVERY_PREPARED);
        verify(audit, Mockito.times(1)).record(eq(AuditAction.STATE_TRANSITION), any());
    }

    private static Flight gliderInState(FlightProcessState state) {
        return Flight.createGlider(
                UUID.fromString("019e2e15-2c00-7af9-8000-0000000000a1"),
                state.id(),
                UUID.fromString("019e2e15-2c00-7e80-8000-000000003e80"),
                ops());
    }

    private static Flight towInState(FlightProcessState state) {
        return Flight.createTow(
                UUID.fromString("019e2e15-2c00-7af9-8000-0000000000a2"),
                state.id(),
                UUID.fromString("019e2e15-2c00-7e80-8000-000000003e80"),
                ops());
    }

    private static void setField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("test setField failed for " + name, e);
        }
    }

    private static FlightOperationalData ops() {
        return new FlightOperationalData(
                LocalDate.of(2026, 5, 1),
                null, null, null, null,
                null, null,
                null, null,
                null, null,
                null, null,
                null, null,
                false, false,
                null, null,
                null, null,
                null,
                null,
                null, null,
                false);
    }
}
