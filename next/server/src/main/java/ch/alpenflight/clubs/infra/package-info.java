/**
 * Clubs persistence adapter — Spring Data JPA implementations of the
 * {@code clubs.domain} ports. {@link
 * ch.alpenflight.clubs.infra.JpaClubRepository} extends both
 * {@link ch.alpenflight.clubs.domain.ClubRepository} and Spring Data's
 * {@code JpaRepository<Club, UUID>}, so the application layer depends on
 * the abstract port while Spring Data generates the runtime bean.
 *
 * <p>Per ADR 0023 only {@code clubs.domain} and Spring Data may be
 * imported here. {@code clubs.web} and {@code clubs.application} must NOT
 * depend on this package — the application layer goes through the port,
 * not the adapter.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.clubs.infra;
