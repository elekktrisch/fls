/**
 * Clubs HTTP adapter. {@link ch.alpenflight.clubs.web.ClubsController}
 * speaks {@code /api/v1/clubs}; {@link
 * ch.alpenflight.clubs.web.ClubsExceptionHandler} translates domain
 * exceptions to HTTP responses.
 *
 * <p>Per ADR 0023 this package depends on {@code clubs.application} (the
 * service it adapts), {@code clubs.domain} (for the exception types it
 * catches), and Spring web. It must NOT depend on {@code clubs.infra}
 * (Spring Data implementation) — controllers flow through the application
 * layer, never directly to a repository.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.clubs.web;
