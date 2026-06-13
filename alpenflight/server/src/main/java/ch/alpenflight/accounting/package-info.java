/**
 * Accounting module (E-09) — the configuration surface for the billing rules
 * engine (S-072, the "sacred cow"). Holds the {@link
 * ch.alpenflight.accounting.domain.AccountingRuleFilter} aggregate root: a
 * per-club, tenant-scoped predicate row that decides which flights a billing
 * rule matches and what article / recipient it produces. The rules ENGINE that
 * consumes these rows is J-9 (S-073–077) and lives elsewhere; this module is
 * the J-8 CRUD/config surface only.
 *
 * <p>Tenant-scoped via Hibernate's {@code @TenantId} on
 * {@code operatingClubId} (ADR 0008). The legacy stack's cross-tenant
 * Update/Delete was a tenant-leak bug; here every read + write is auto-filtered
 * to the caller's tenant, so a cross-tenant mutation surfaces as 404.
 *
 * <p>Layered per ADR 0023 into sub-packages: {@code domain} (aggregate root +
 * the typed {@code FilterConfig} value object + domain exceptions); the
 * repository port / service / web adapters arrive in later J-8 tasks
 * (T-04/T-05/T-06). Per ADR 0022 directive 2 the business invariants (name +
 * filter-type required) live on the aggregate; the V4 schema stays structural.
 *
 * <p>Not an {@code OPEN} Spring Modulith module: unlike {@code flighttypes}
 * (whose flight-report read-model listens to a saved-event), this aggregate has
 * no cross-module event consumer in J-8 — the rules engine reads the rows
 * directly when it lands.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.accounting;
