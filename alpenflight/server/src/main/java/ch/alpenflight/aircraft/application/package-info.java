/**
 * Aircraft use-case orchestration. Transactional services, request /
 * response DTOs, the domain-to-DTO mapper, and the {@code AircraftAccess}
 * SpEL bean used by the controller's {@code @PreAuthorize} expressions.
 *
 * <p>Aircraft is cross-tenant (S-058 reversion of S-159): reads are open to
 * any authenticated user; writes are gated by {@code managing_club_id} via
 * the SpEL bean (CLUB_ADMINISTRATOR or FLIGHT_OPERATOR depending on the
 * sub-resource; SYSTEM_ADMINISTRATOR fallback).
 *
 * <p>Per ADR 0023 this layer depends on {@code aircraft.domain} (aggregate
 * + the {@link ch.alpenflight.aircraft.domain.AircraftRepository} port)
 * and on Spring's transaction + DI infrastructure. It must NOT depend on
 * {@code aircraft.web} or {@code aircraft.infra}.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.aircraft.application;
