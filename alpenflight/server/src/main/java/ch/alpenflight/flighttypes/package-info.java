/**
 * Flight types module — co-edited masterdata for the two aggregate roots
 * {@link ch.alpenflight.flighttypes.domain.FlightType} (per-club tenant-scoped
 * roster of flight kinds) and
 * {@link ch.alpenflight.flighttypes.domain.FlightCostBalanceType}
 * (system-global reference of cost-balance models). Both are referenced by
 * Flight (S-058) + AccountingRuleFilter (S-072).
 *
 * <p><strong>Two aggregates, two tenancies — do not mirror-blast.</strong>
 * {@code FlightType} carries {@code @TenantId} on {@code operatingClubId};
 * {@code FlightCostBalanceType} has no tenant column (cross-tenant reference
 * per tenant-rules.yaml). The S-024 leakage sweep iterates FlightType
 * automatically via {@link org.hibernate.annotations.TenantId} discovery; FCBT
 * is intentionally absent because it carries no tenant discriminator.
 *
 * <p>Layered per ADR 0023 into four sub-packages: {@code domain} (aggregate
 * roots + repository ports + domain exceptions), {@code application}
 * (orchestration services + DTOs + mapper), {@code web} (REST adapters +
 * exception handler), {@code infra} (Spring Data JPA adapters).
 *
 * <p>Authz model: FlightType writes gated to CLUB_ADMINISTRATOR per S-159
 * (SYSTEM_ADMINISTRATOR explicitly denied — tenant-scoped sacred cow); reads
 * to any authenticated principal. Cross-tenant FlightType detail surfaces as
 * 404 (the row is invisible under the caller's tenant scope), never 403. FCBT
 * is GET-only at {@code /api/v1/flight-cost-balance-types} (any authenticated
 * principal — S-047 reference pattern); sysadmin admin-CRUD deferred until
 * the first consumer (S-058 / S-072) demands it.
 *
 * <p>Per ADR 0022 directive 2 the at-least-one-of-{glider,tow,motor} invariant
 * on FCBT (was V3's dropped {@code ck_fcbt_at_least_one_flag}) lives on the
 * aggregate (constructor + flag mutators), not as a DB CHECK.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.flighttypes;
