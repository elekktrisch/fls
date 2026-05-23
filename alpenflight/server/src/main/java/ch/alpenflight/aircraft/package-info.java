/**
 * Aircraft module — tenant-scoped masterdata aggregate root for the airframe
 * (immatriculation, type, owner, FLARM id, MTOM, counter-unit types,
 * homebase). Per tenant-rules.yaml (2026-05-23 reclassification, S-159)
 * Aircraft is <strong>tenant-scoped</strong> via
 * {@code Aircraft.managingClubId} (the {@code @TenantId} discriminator).
 *
 * <p>Ownership is independent metadata with three derived cases: own-club
 * ({@code owner_club_id == managing_club_id}), other organisation
 * ({@code owner_club_id != managing_club_id}, or NULL when external to the
 * Clubs catalog), private person ({@code aircraft_owner_person_id}).
 * The owner-kind discriminator lives in the domain, not in the schema
 * (ADR 0022 directive 2).
 *
 * <p>Layered per ADR 0023 into four sub-packages: {@code domain} (aggregate
 * root + aggregate-internal entities + repository port + value objects +
 * domain exceptions), {@code application} (orchestration service + DTOs +
 * mapper), {@code web} (REST adapter), {@code infra} (Spring Data JPA
 * adapter).
 *
 * <p>Authz model: tenant scoping is structural via {@code @TenantId}; reads
 * + writes are filtered to the caller's managing tenant by Hibernate.
 * Role-within-tenant gates live at the controller as
 * {@code @PreAuthorize("hasRole(...)")} — CLUB_ADMINISTRATOR for register /
 * update / soft-delete / transfer-ownership; CLUB_ADMINISTRATOR or
 * FLIGHT_OPERATOR for state changes + counter recording. Cross-tenant
 * mutation surfaces as 404 (the row is invisible under the caller's
 * tenant scope), not 403 — this is the IDOR contract.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.aircraft;
