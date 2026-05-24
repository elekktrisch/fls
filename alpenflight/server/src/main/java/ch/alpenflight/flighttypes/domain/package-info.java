/**
 * Domain layer for the flight-types module. Holds both aggregate roots
 * ({@link ch.alpenflight.flighttypes.domain.FlightType},
 * {@link ch.alpenflight.flighttypes.domain.FlightCostBalanceType}), their
 * repository ports, and the domain exception vocabulary translated to HTTP
 * problem responses by {@code flighttypes.web}.
 *
 * <p>Per ADR 0023 the {@code domain} package depends only on Jakarta
 * Persistence + JSpecify + Hibernate annotations. No Spring web, no Spring
 * Data, no application-layer types — the layering rules are enforced by
 * the ArchUnit suite.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.flighttypes.domain;
