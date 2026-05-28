/**
 * Keycloak admin-REST adapter for the Users module.
 *
 * <p>Hand-rolled Spring {@code RestClient} façade against the KC admin API —
 * NOT the official {@code keycloak-admin-client} library, which transitively
 * pulls RESTEasy + JBoss-Logging + Jakarta-Activation. Surface is intentionally
 * small; the cost of the dependency tree is not worth the saving.
 *
 * <p>Token caching, configuration properties + bearer-injection / log-
 * redaction interceptors live in {@link ch.alpenflight.platform.keycloak}
 * so sibling business adapters can share the transport basics without
 * crossing the users module's boundary.
 *
 * <p>Hardening (specific to this adapter):
 * <ul>
 *   <li>List/search calls are scoped with {@code q=clubId:<callerClub>} so
 *       a forgotten filter doesn't leak realm-wide users.</li>
 * </ul>
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.users.infra.keycloak;
