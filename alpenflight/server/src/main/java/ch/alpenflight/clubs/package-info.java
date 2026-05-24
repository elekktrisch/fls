/**
 * Clubs module — the tenant root itself. Not {@code @TenantId}-annotated.
 *
 * <p>Declared as an {@link org.springframework.modulith.ApplicationModule#type()
 * OPEN} Spring Modulith module so business modules ({@code persons},
 * future {@code users} / {@code flights}, …) may import
 * {@code clubs.domain.*} entities + repository ports (e.g.
 * {@link ch.alpenflight.clubs.domain.MemberState} +
 * {@link ch.alpenflight.clubs.domain.MemberStateRepository}) for FK
 * validation, per-club lookup data, and tenant-root metadata without going
 * through a named interface. Matches the {@code referencedata} +
 * {@code audit} shared-kernel surfaces.
 *
 * <p>Layered per ADR 0023 into four sub-packages:
 * <ul>
 *   <li>{@code clubs.domain} — {@link ch.alpenflight.clubs.domain.Club}
 *       aggregate, {@link ch.alpenflight.clubs.domain.MemberState},
 *       {@link ch.alpenflight.clubs.domain.ClubRepository} +
 *       {@link ch.alpenflight.clubs.domain.MemberStateRepository} ports,
 *       domain exceptions.</li>
 *   <li>{@code clubs.application} — {@code ClubsService}, DTOs, mapper.</li>
 *   <li>{@code clubs.web} — REST controller + exception handler.</li>
 *   <li>{@code clubs.infra} — Spring Data JPA implementations.</li>
 * </ul>
 *
 * <p>Authorization is by role, never by tenant filter — Clubs are the
 * tenant boundary. The {@code @PreAuthorize} predicates stay across the
 * S-019/S-020 auth-chain swap; only the principal source flips.
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN)
@NullMarked
package ch.alpenflight.clubs;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
