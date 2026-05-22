/**
 * Aircraft module — cross-tenant masterdata aggregate root for the airframe
 * (immatriculation, type, owner-club / owner-person, FLARM id, MTOM,
 * counter-unit types, homebase). Per tenant-rules.yaml (2026-05-16
 * reclassification) Aircraft is <strong>cross-tenant</strong>: no
 * {@code @TenantId}, {@code owner_club_id} is nullable (charter / loan /
 * private ownership). Per-flight tenancy lives on
 * {@code Flight.operating_club_id}, set per-flight by the operator.
 *
 * <p>Layered per ADR 0023 into four sub-packages:
 * <ul>
 *   <li>{@code aircraft.domain} — {@link ch.alpenflight.aircraft.domain.Aircraft}
 *       aggregate root, {@link ch.alpenflight.aircraft.domain.AircraftStateHistoryEntry}
 *       + {@link ch.alpenflight.aircraft.domain.AircraftOperatingCounter}
 *       aggregate-internal entities, {@link ch.alpenflight.aircraft.domain.AircraftRepository}
 *       port, domain exceptions, value objects.</li>
 *   <li>{@code aircraft.application} — {@code AircraftsService}, DTOs,
 *       mapper, {@code AircraftAccess} SpEL bean.</li>
 *   <li>{@code aircraft.web} — REST controller + exception handler.</li>
 *   <li>{@code aircraft.infra} — Spring Data JPA implementation.</li>
 * </ul>
 *
 * <p>Authz model: read open to any authenticated principal (full-fleet
 * visibility is intentional for charter / loan use cases). Write gated to
 * SYSTEM_ADMINISTRATOR (always) or CLUB_ADMINISTRATOR of
 * {@code aircraft.owner_club_id} (when non-null) — checked at the service
 * layer via {@code AircraftAccess}. Owner-club / owner-person fields are
 * excluded from the update DTO; ownership transfer goes through a
 * dedicated SYSTEM_ADMIN-only endpoint (A04 mass-assignment defense).
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.aircraft;
