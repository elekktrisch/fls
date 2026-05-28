/**
 * Provisioning infrastructure adapters — the Keycloak implementation of
 * the {@link ch.alpenflight.tenancy.provisioning.domain.KeycloakDeploymentDirectory}
 * port. Talks to the realm via the same machine client the user-
 * directory adapter uses, with the additional {@code manage-groups}
 * scope.
 */
package ch.alpenflight.tenancy.provisioning.infra;
