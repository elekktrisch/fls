/**
 * S-186 — concrete mappers for {@link ch.alpenflight.migration.bundle.EntityType.Group#ACCOUNTING}
 * (V4 boundary) plus the two reclassified-per-club ref tables
 * ({@code AircraftReservationType}, {@code PlanningDayAssignmentType})
 * surfaced when the architectural review found they need per-bundle FK
 * resolution to satisfy {@link ch.alpenflight.migration.bundle.Manifest}'s
 * coverage gate.
 *
 * <p>{@code AuditLogMapper} lives in {@code identity.*} per the
 * group-routing convention (legacy {@code AuditLogs} was bundled under the
 * IDENTITY group for cross-cutting reasons — see
 * {@link ch.alpenflight.migration.bundle.EntityType#AUDIT_LOG}).
 */
package ch.alpenflight.migration.bundle.accounting;
