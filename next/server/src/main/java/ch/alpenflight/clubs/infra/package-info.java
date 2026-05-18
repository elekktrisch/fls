/**
 * Clubs persistence adapter — Spring Data JPA implementations of the
 * {@code clubs.domain} ports. {@link
 * ch.alpenflight.clubs.infra.JpaClubRepository} extends both
 * {@link ch.alpenflight.clubs.domain.ClubRepository} and Spring Data's
 * {@code JpaRepository<Club, UUID>}, so the application layer depends on
 * the abstract port while Spring Data generates the runtime bean.
 *
 * <p>Per ADR 0023 nothing in {@code clubs.web} or {@code clubs.application}
 * may import from this package — the application layer goes through the
 * domain port, not the adapter. Outbound dependencies of {@code infra/}
 * itself are pragmatic (JPA, Spring Data, library SDKs as needed) — the
 * direction-of-dependency rule is one-way inbound.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.clubs.infra;
