/**
 * Locations module — reference-data aggregate root for airfields, waypoints,
 * and outlanding fields. Cross-tenant by construction (sacred-cow shared
 * resource per {@code alpenflight/database/tenant-rules.yaml}); not
 * {@code @TenantId}-annotated. SYSTEM_ADMINISTRATOR-only mutation; reads
 * open to authenticated callers.
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
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.locations;
