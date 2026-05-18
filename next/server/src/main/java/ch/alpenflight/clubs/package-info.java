/**
 * Clubs module — the tenant root itself. Not {@code @TenantId}-annotated.
 *
 * <p>Layered per ADR 0023 into four sub-packages:
 * <ul>
 *   <li>{@code clubs.domain} — {@link ch.alpenflight.clubs.domain.Club}
 *       aggregate, {@link ch.alpenflight.clubs.domain.MemberState},
 *       {@link ch.alpenflight.clubs.domain.ClubRepository} port,
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
@org.jspecify.annotations.NullMarked
package ch.alpenflight.clubs;
