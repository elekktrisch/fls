/**
 * Flight-group mappers (S-185): {@code Location}, {@code StartType},
 * {@code FlightType}, {@code Aircraft}, {@code AircraftAircraftState},
 * {@code AircraftOperatingCounter}, {@code Flight}, {@code FlightCrew}.
 *
 * <p>Carries the {@link ch.alpenflight.migration.bundle.Mapper Mapper}
 * contract from S-183 for everything in
 * {@link ch.alpenflight.migration.bundle.EntityType.Group#FLIGHT}.
 * The cross-tenant FK shape (Aircraft is cross-tenant per ADR 0008 /
 * V10 + S-058; Location is tenant-scoped via {@code club_id} per V7) is
 * reflected in {@link ch.alpenflight.migration.bundle.Manifest}'s
 * {@code TENANT_BYPASS_ALLOW_LIST} widening + each mapper's
 * {@link ch.alpenflight.migration.bundle.Mapper#foreignKeys()}.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.migration.bundle.flight;
