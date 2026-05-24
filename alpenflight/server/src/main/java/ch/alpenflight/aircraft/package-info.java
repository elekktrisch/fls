/**
 * Aircraft module — cross-tenant masterdata aggregate root for the airframe
 * (immatriculation, type, owner, FLARM id, MTOM, counter-unit types,
 * homebase). Per tenant-rules.yaml (2026-05-24 S-058 reversion of S-159):
 * Aircraft is <strong>cross-tenant</strong> — any authenticated user may
 * read the catalog (so the Flight aircraft picker surfaces another club's
 * tow plane); mutations are gated to the owning club via the
 * {@code AircraftAccess} SpEL bean.
 *
 * <p>Three orthogonal columns drive Aircraft semantics:
 *
 * <ul>
 *   <li>{@code managing_club_id} (NOT NULL): the operational manager.
 *       This is the gate field for the SpEL bean. Required even for
 *       externally-owned aircraft — when Club A flies an aircraft owned
 *       by an organisation that isn't in the system, Club A is still the
 *       manager.</li>
 *   <li>{@code owner_club_id} (NULL OK): physical owner club. Metadata
 *       only. NULL when owned by an external organisation or by a
 *       private person.</li>
 *   <li>{@code aircraft_owner_person_id} (NULL OK): private-person owner
 *       metadata. Today informational; person-edit predicate deferred
 *       until S-052 wires User→Person.</li>
 * </ul>
 *
 * <p>Owner-kind discriminator (own-club / other-organisation /
 * private-person / external) is derived at the application layer, not in
 * the schema (ADR 0022 directive 2).
 *
 * <p>Layered per ADR 0023 into four sub-packages: {@code domain} (aggregate
 * root + aggregate-internal entities + repository port + value objects +
 * domain exceptions), {@code application} (orchestration service + DTOs +
 * mapper + the {@code AircraftAccess} SpEL bean), {@code web} (REST
 * adapter), {@code infra} (Spring Data JPA adapter).
 *
 * <p>Authz model: reads (list / picker / detail) require only
 * {@code isAuthenticated()}; the catalog is intentionally cross-tenant.
 * Mutations are gated by the {@code AircraftAccess} SpEL bean:
 *
 * <ul>
 *   <li><b>Register</b>: CLUB_ADMINISTRATOR only. The new row's
 *       {@code managing_club_id} is sourced from the caller's JWT
 *       {@code clubId} claim, which SYSTEM_ADMINISTRATOR lacks — a
 *       sysadmin-driven register variant is tracked separately (S-162).</li>
 *   <li><b>Edit / soft-delete / transfer-ownership</b>: CLUB_ADMINISTRATOR
 *       of the aircraft's {@code managing_club_id}, or SYSTEM_ADMINISTRATOR
 *       as universal fallback.</li>
 *   <li><b>State / counter</b>: same predicate, FLIGHT_OPERATOR also
 *       admitted.</li>
 * </ul>
 *
 * <p>Person-owner edit predicate is deferred to a follow-up story when
 * User→Person (S-052) wires up.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.aircraft;
