package ch.alpenflight.flights.domain;

import java.util.UUID;

/**
 * Port supplying the two reference-data UUIDs the Flight aggregate stamps
 * on create — the canonical {@code flight_process_state.code='NOT_PROCESSED'}
 * and {@code flight_air_state.code='NEW'}. Implemented in
 * {@code flights.infra} by a {@code @PostConstruct}-cached component;
 * {@code flights.application} depends on the port per ADR 0023.
 */
public interface FlightInitialStateProvider {

    UUID initialProcessStateId();

    UUID initialAirStateId();
}
