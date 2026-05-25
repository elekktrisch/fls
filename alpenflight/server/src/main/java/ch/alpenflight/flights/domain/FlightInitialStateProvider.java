package ch.alpenflight.flights.domain;

import java.util.UUID;

/**
 * Port supplying the reference-data UUID the Flight aggregate stamps on
 * create — the canonical {@code flight_process_state.code='NOT_PROCESSED'}.
 * Implemented in {@code flights.infra} by a {@code @PostConstruct}-cached
 * component; {@code flights.application} depends on the port per ADR 0023.
 *
 * <p>Air-state is computed (S-060) — there is no air-state seed to resolve.
 */
public interface FlightInitialStateProvider {

    UUID initialProcessStateId();
}
