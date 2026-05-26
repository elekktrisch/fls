/**
 * Authenticated-principal view. Single read endpoint
 * {@link ch.alpenflight.me.web.MeController} at {@code GET /api/v1/me};
 * returns claims-derived data enriched with the tenant-scoped Person link
 * resolved via {@code platform.tenancy.UserPrincipalLookup}.
 *
 * <p>Layered per ADR 0023: {@code application} owns the read service +
 * DTOs, {@code web} owns the HTTP surface. The module is intentionally
 * thin — there is no aggregate; the response is a projection over the
 * {@code user} + {@code person} rows already managed by other modules.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.me;
