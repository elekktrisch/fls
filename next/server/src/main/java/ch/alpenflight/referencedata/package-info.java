/**
 * System-global reference-data catalogs (Country, ClubState). Read-only
 * from the API; data is owned by Flyway seed migrations (V2). Cross-
 * tenant by construction — no {@code @TenantId} on any entity per the
 * ADR 0008 carve-out for system-wide lookups.
 *
 * <p>Declared as an {@link org.springframework.modulith.ApplicationModule#type()
 * OPEN} Spring Modulith module: every business module
 * ({@code clubs}, future {@code locations}, {@code flights}, …) may
 * import {@code referencedata.domain.*} entities and repository ports
 * for FK validation / picker lookups without going through a named
 * interface. The OPEN type is the same shared-kernel exception
 * {@link ch.alpenflight.platform} uses; both are read-by-everyone.
 *
 * <p>Layered per ADR 0023 into the standard four sub-packages:
 * <ul>
 *   <li>{@code referencedata.domain} — plain JPA entities + repository ports.</li>
 *   <li>{@code referencedata.application} — read-only services + DTOs + mapper.</li>
 *   <li>{@code referencedata.web} — REST controllers (read-only GETs).</li>
 *   <li>{@code referencedata.infra} — Spring Data JPA adapters.</li>
 * </ul>
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN)
@NullMarked
package ch.alpenflight.referencedata;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
