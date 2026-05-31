/**
 * Flight persistence adapter — Spring Data JPA implementation of
 * {@code flights.domain} ports + the lightweight {@code id+code}
 * reference-data projection for {@code t_flight_process_state} (full
 * reference-data port deferred; the initial-state lookup at startup is all
 * the service needs). The {@code t_flight_air_state} table was dropped by
 * S-060 (V13) — air-state is computed on the aggregate, never stored.
 *
 * <p>Per ADR 0023 nothing in {@code flights.web} or
 * {@code flights.application} may import from this package.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.flights.infra;
