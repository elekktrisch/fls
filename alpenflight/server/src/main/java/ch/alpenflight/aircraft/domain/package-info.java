/**
 * Aircraft aggregate root + child entities + repository port + domain
 * exceptions + value objects.
 *
 * <p>Per ADR 0023 this package is the stable centre of the Aircraft module.
 * The aggregate carries its own invariants (immatriculation format,
 * state-history "one open per aircraft", counter monotonicity, FLARM-id /
 * competition-sign / spot-link regex defenses); the
 * {@link ch.alpenflight.aircraft.domain.AircraftRepository} port is the
 * persistence boundary.
 *
 * <p>Cross-tenant per S-058 (reversion of S-159's {@code @TenantId}, but
 * keeps the {@code managing_club_id} column as the operational-manager
 * gate). Read endpoints are open to any authenticated user (the Flight
 * aircraft picker must see other clubs' aircraft for the charter case);
 * write endpoints are gated by {@code managing_club_id} via the
 * {@code AircraftAccess} SpEL bean. Aggregate-internal entities
 * ({@code AircraftStateHistoryEntry}, {@code AircraftOperatingCounter})
 * ride the parent's aggregate boundary.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.aircraft.domain;
