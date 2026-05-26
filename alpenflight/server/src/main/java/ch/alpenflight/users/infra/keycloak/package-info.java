/**
 * Keycloak admin-REST adapter for the Users module.
 *
 * <p>Hand-rolled Spring {@code WebClient} façade against the KC admin API —
 * NOT the official {@code keycloak-admin-client} library, which transitively
 * pulls RESTEasy + JBoss-Logging + Jakarta-Activation. Surface is intentionally
 * small (~8 calls); the cost of the dependency tree is not worth the saving.
 *
 * <p>Hardening:
 * <ul>
 *   <li>{@link ch.alpenflight.users.infra.keycloak.KeycloakAdminTokenSupplier}
 *       caches the service-account access token and refreshes ~30s before
 *       expiry — the admin client is not request-scoped.</li>
 *   <li>The {@code WebClient} log filter redacts {@code Authorization: Bearer …}
 *       so the admin token never lands in request logs.</li>
 *   <li>List/search calls are scoped with {@code q=clubId:<callerClub>} so a
 *       forgotten filter doesn't leak realm-wide users.</li>
 * </ul>
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.users.infra.keycloak;
