/**
 * Aircraft use-case orchestration. Transactional services, request /
 * response DTOs, the domain-to-DTO mapper, the {@code AircraftAccess}
 * SpEL bean used by {@code @PreAuthorize} on the controller.
 *
 * <p>Per ADR 0023 this layer depends on {@code aircraft.domain} (aggregate
 * + the {@link ch.alpenflight.aircraft.domain.AircraftRepository} port)
 * and on Spring's transaction + DI infrastructure. It must NOT depend on
 * {@code aircraft.web} or {@code aircraft.infra}.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.aircraft.application;
