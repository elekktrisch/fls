/**
 * Aircraft persistence adapter — Spring Data JPA implementation of
 * {@code aircraft.domain} ports. {@link
 * ch.alpenflight.aircraft.infra.JpaAircraftRepository} extends both
 * {@link ch.alpenflight.aircraft.domain.AircraftRepository} and Spring
 * Data's {@code JpaRepository<Aircraft, UUID>}.
 *
 * <p>Per ADR 0023 nothing in {@code aircraft.web} or
 * {@code aircraft.application} may import from this package.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.aircraft.infra;
