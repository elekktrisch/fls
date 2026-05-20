/**
 * Locations aggregate root, child entities, repository port, domain
 * exceptions.
 *
 * <p>Per ADR 0023 this package is the stable centre of the Locations module
 * — the aggregate carries its own invariants (ICAO format, blank-name
 * rejection, soft-delete), the
 * {@link ch.alpenflight.locations.domain.LocationRepository} port is the
 * persistence boundary, and domain exceptions raise without Spring-web
 * coupling (translated to HTTP by
 * {@code ch.alpenflight.locations.web.LocationsExceptionHandler}).
 *
 * <p>Allowed dependencies: the JDK, JPA annotations (deliberate
 * Hibernate-on-aggregate concession), JSpecify nullability markers,
 * {@code ch.alpenflight.platform.*} shared kernel. Forbidden: Spring web,
 * Spring stereotypes, Jackson, the servlet API.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.locations.domain;
