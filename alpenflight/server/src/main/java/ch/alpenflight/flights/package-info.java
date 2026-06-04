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
 *
 * <p>Declared an {@link org.springframework.modulith.ApplicationModule#type()
 * OPEN} Spring Modulith module (matching {@code aircraft} / {@code locations} /
 * {@code clubs} / {@code referencedata} / {@code audit}) so the cross-cutting
 * {@code tenancy.showcase} seed loader (J-3 T-03b) may build {@link
 * ch.alpenflight.flights.domain.Flight} aggregates through their factories,
 * link a paired aerotow ({@link ch.alpenflight.flights.domain.Flight#linkTow}),
 * and drive the seeded flights through the real process-state edges via
 * {@link ch.alpenflight.flights.application.FlightStateTransitionService} — so
 * every showcase flight reaches its target state through the domain rather than
 * a raw illegal-state INSERT. The seed is the only external importer of the
 * flights packages; the normal read/write path stays the {@code
 * flights.application} service + REST controller.
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN)
@org.jspecify.annotations.NullMarked
package ch.alpenflight.flights;
