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
 * <p>Cross-tenant per tenant-rules.yaml (2026-05-16 reclassification): no
 * {@code @TenantId} on Aircraft or its aggregate internals. Tenancy / authz
 * lives at the application-service seam via
 * {@code ch.alpenflight.aircraft.application.AircraftAccess}.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.aircraft.domain;
