/**
 * Flight persistence adapter — Spring Data JPA implementation of
 * {@code flights.domain} ports + the lightweight {@code id+code}
 * reference-data projections for {@code flight_process_state} /
 * {@code flight_air_state} (full reference-data port deferred to a future
 * story; S-058 only needs lookup-by-code at startup).
 *
 * <p>Per ADR 0023 nothing in {@code flights.web} or
 * {@code flights.application} may import from this package.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.flights.infra;
