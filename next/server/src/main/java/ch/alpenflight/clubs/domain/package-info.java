/**
 * Clubs aggregate roots, value objects, repository port, domain exceptions.
 *
 * <p>Per ADR 0023 this package is the stable centre of the Clubs module —
 * aggregates carry their own invariants (slug pattern, blank-name
 * rejection, soft-delete), the {@link ch.alpenflight.clubs.domain.ClubRepository}
 * port is the persistence boundary, and domain exceptions raise without
 * Spring-web coupling (translated to HTTP by
 * {@code ch.alpenflight.clubs.web.ClubsExceptionHandler}).
 *
 * <p>Allowed dependencies: the JDK, JPA annotations (deliberate
 * Hibernate-on-aggregate concession), JSpecify nullability markers,
 * {@code ch.alpenflight.platform.*} shared kernel. Forbidden:
 * Spring web, Spring stereotypes, Jackson, the servlet API.
 *
 * <p>Inbound from: {@code clubs.application}, {@code clubs.web},
 * {@code clubs.infra}. Outbound: only as above.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.clubs.domain;
