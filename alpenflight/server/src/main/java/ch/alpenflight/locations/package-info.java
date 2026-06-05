/**
 * Locations module — per-club masterdata aggregate root for airfields,
 * waypoints, and outlanding fields. TENANT_SCOPED since S-049b (was
 * reference data through S-049): the discriminator column {@code club_id}
 * wears {@code @TenantId}, so Hibernate appends the per-tenant predicate on
 * every JPA query. Writes open to CLUB_ADMINISTRATOR (own club, structurally
 * via the tenant filter) + SYSTEM_ADMINISTRATOR (acts within the club its
 * JWT claim points at; an unscoped cross-club escape hatch is an ADR 0008
 * follow-up).
 *
 * <p>Layered per ADR 0023 into four sub-packages:
 * <ul>
 *   <li>{@code locations.domain} — {@link ch.alpenflight.locations.domain.Location}
 *       aggregate root, {@link ch.alpenflight.locations.domain.InOutboundPoint}
 *       child entity, {@link ch.alpenflight.locations.domain.LocationRepository}
 *       port, domain exceptions.</li>
 *   <li>{@code locations.application} — {@code LocationsService}, DTOs, mapper.</li>
 *   <li>{@code locations.web} — REST controller + exception handler.</li>
 *   <li>{@code locations.infra} — Spring Data JPA implementation.</li>
 * </ul>
 *
 * <p>The aggregate boundary: {@link ch.alpenflight.locations.domain.InOutboundPoint}
 * is managed via Location's edit screen only — no top-level CRUD endpoint
 * for IOPs. The {@code @OneToMany(cascade=ALL, orphanRemoval=true)}
 * mapping replaces the IOP list on each update.
 *
 * <p>Declared an {@link org.springframework.modulith.ApplicationModule#type()
 * OPEN} Spring Modulith module (matching {@code clubs} / {@code referencedata} /
 * {@code audit}) so a cross-cutting consumer — the {@code tenancy.showcase}
 * seed loader — may build {@link ch.alpenflight.locations.domain.Location}
 * aggregates through their factory and persist deterministic demo rows. The
 * seed is the only external importer of {@code locations.domain}; the normal
 * read/write path stays the {@code locations.application} service + REST
 * controller.
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN)
@org.jspecify.annotations.NullMarked
package ch.alpenflight.locations;
