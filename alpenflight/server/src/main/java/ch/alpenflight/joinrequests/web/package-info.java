/**
 * Join-requests HTTP adapter (S-178).
 * {@link ch.alpenflight.joinrequests.web.JoinRequestController} speaks
 * {@code POST /api/v1/join-requests}, {@code POST /{id}/withdraw},
 * {@code GET /api/v1/me/join-request}, and the CLUB_ADMINISTRATOR
 * {@code GET /api/v1/join-requests?status=pending}. The package-local
 * {@code @RestControllerAdvice} maps the application + domain exception
 * vocabulary to RFC-7807-shaped responses.
 *
 * <p>Per ADR 0023 this package depends on {@code joinrequests.application}
 * (service + DTOs + exception types) and Spring web; it must NOT depend on
 * {@code joinrequests.infra}.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.joinrequests.web;
