/**
 * Provisioning infrastructure adapters — the Keycloak implementation of
 * the {@link ch.alpenflight.tenancy.provisioning.domain.KeycloakDeploymentDirectory}
 * port. Talks to the realm via the same machine client S-052 provisioned
 * for the user-directory adapter, with the additional {@code manage-groups}
 * scope (S-138).
 */
package ch.alpenflight.tenancy.provisioning.infra;
