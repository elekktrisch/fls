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
 * <p>Tenant-scoped via {@link ch.alpenflight.aircraft.domain.Aircraft#managingClubId}
 * (S-159 reclassification): the {@code @TenantId} discriminator filters
 * reads + writes by the resolved managing tenant. Aggregate-internal
 * entities ({@code AircraftStateHistoryEntry}, {@code AircraftOperatingCounter})
 * ride through the parent via FK chain; they do not carry their own
 * {@code @TenantId}.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.aircraft.domain;
