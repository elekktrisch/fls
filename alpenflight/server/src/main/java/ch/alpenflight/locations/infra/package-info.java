/**
 * Locations persistence adapter — Spring Data JPA implementation of the
 * {@code locations.domain} ports. {@link
 * ch.alpenflight.locations.infra.JpaLocationRepository} extends both
 * {@link ch.alpenflight.locations.domain.LocationRepository} and Spring
 * Data's {@code JpaRepository<Location, UUID>}, so the application layer
 * depends on the abstract port while Spring Data generates the runtime bean.
 *
 * <p>Per ADR 0023 nothing in {@code locations.web} or
 * {@code locations.application} may import from this package.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.locations.infra;
