/**
 * Shared Keycloak admin-REST plumbing — token supplier, configuration
 * properties, bearer-injection + log-redaction interceptors. Lives in the
 * OPEN platform module so business adapters in
 * {@code users.infra.keycloak} (S-052) + {@code tenancy.provisioning.infra}
 * (S-138) can each build their own narrow {@code RestClient} against the
 * same machine-client token + property surface without crossing each
 * other's module boundaries.
 *
 * <p>The user-directory and deployment-directory adapters keep distinct
 * narrow surfaces (one per business module's port); only the transport
 * basics are shared here.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.platform.keycloak;
