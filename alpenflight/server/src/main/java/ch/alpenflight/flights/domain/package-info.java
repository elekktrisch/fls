/**
 * Flight aggregate root + aggregate-internal FlightCrew + value objects +
 * repository port + domain exceptions.
 *
 * <p>Per ADR 0023 this package is the stable centre of the Flights module.
 * The aggregate carries its own invariants (per ADR 0022 directive 2 —
 * business rules on aggregates, NOT schema CHECK constraints): tow-link
 * rules ({@link ch.alpenflight.flights.domain.Flight#linkTow}), runway /
 * coupon format VOs, pairwise temporal ordering checks, FlightAircraftType
 * sparse-enum mapping {1, 2, 4}.
 *
 * <p>Tenant-scoped via {@link ch.alpenflight.flights.domain.Flight#operatingClubId}
 * (the {@code @TenantId} discriminator). FlightCrew is aggregate-internal —
 * managed only via Flight mutator methods; no top-level repository.
 *
 * <p>Cross-tenant references intentional and load-bearing:
 * {@code flight_crew.person_id} → Person (no {@code @TenantId} per S-051);
 * {@code flight.flight_cost_balance_type_id} → FCBT (system-global per
 * S-053). PK-load resolves cross-tenant by design.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.flights.domain;
