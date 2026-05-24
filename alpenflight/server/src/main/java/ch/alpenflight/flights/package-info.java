/**
 * Flight module — tenant-scoped aggregate root for an executed flight.
 * Sacred-cow shape (per S-013 + S-058 design notes): a single Flight table
 * carries glider / tow / motor flights discriminated by
 * {@link ch.alpenflight.flights.domain.FlightAircraftType} (sparse SMALLINT
 * 1=GLIDER, 2=TOW, 4=MOTOR — value 3 deliberately skipped, per legacy
 * {@code FlightAircraftTypeValue.cs}). NEVER split into per-type tables.
 *
 * <p>Tenant-scoped via Hibernate's {@code @TenantId} discriminator on
 * {@link ch.alpenflight.flights.domain.Flight#operatingClubId} (ADR 0008).
 * Aircraft is cross-tenant (S-058 reversion of S-159) so a Flight's
 * {@code aircraft_id} FK may reference any active aircraft regardless of
 * its managing club — this is the charter case (Club B's Flight flying
 * Club A's tow plane).
 *
 * <p>Layered per ADR 0023 into four sub-packages: {@code domain} (aggregate
 * root + aggregate-internal FlightCrew + value objects + repository port +
 * domain exceptions), {@code application} (orchestration service + DTOs +
 * mapper + keyset-cursor codec), {@code web} (REST adapter), {@code infra}
 * (Spring Data JPA adapter + reference-data projections).
 *
 * <p>Authz model: tenant scoping is structural via {@code @TenantId}.
 * Role-within-tenant gates per S-159 (no {@code SYSTEM_ADMINISTRATOR} —
 * sysadmin has no tenant-scoped HTTP rights): CLUB_ADMINISTRATOR or
 * FLIGHT_OPERATOR for read / create / update; CLUB_ADMINISTRATOR only for
 * soft-delete (destructive, higher bar). Cross-tenant {@code GET} returns
 * 404 (the row is invisible under the caller's tenant scope), not 403.
 *
 * <p>Out of scope for S-058: state-machine transitions (NotProcessed →
 * Valid → Locked → DeliveryPrepared → DeliveryBooked), the validator,
 * delivery / invoice flow, {@code is_solo_flight} server-derive,
 * FlightType × FlightAircraftType compatibility, validated_on /
 * delivery_created_on derivation. All deferred to S-059.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.flights;
