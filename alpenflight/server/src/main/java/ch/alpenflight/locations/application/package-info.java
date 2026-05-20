/**
 * Locations use-case orchestration. Transactional services, request/response
 * DTOs, the domain-to-DTO mapper.
 *
 * <p>Per ADR 0023 this layer depends on {@code locations.domain} (aggregates +
 * the {@link ch.alpenflight.locations.domain.LocationRepository} port) and on
 * Spring's transaction + DI infrastructure. It must NOT depend on
 * {@code locations.web} or {@code locations.infra}.
 *
 * <p>DTOs ship from this package because they're the service's wire
 * contract. The controller in {@code locations.web} adapts HTTP to the
 * service signatures.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.locations.application;
