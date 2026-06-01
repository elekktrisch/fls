/**
 * Shared Keycloak admin-REST plumbing — token supplier, configuration
 * properties, bearer-injection + log-redaction interceptors. Lives in
 * the OPEN platform module so business adapters
 * ({@code users.infra.keycloak} user-directory adapter,
 * {@code tenancy.provisioning.infra} deployment-directory adapter) can
 * each build their own narrow {@code RestClient} against the same
 * machine-client token + property surface without crossing each other's
 * module boundaries.
 *
 * <p>The user-directory and deployment-directory adapters keep distinct
 * narrow surfaces (one per business module's port); only the transport
 * basics are shared here.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.platform.keycloak;
