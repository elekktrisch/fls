/**
 * Tenant provisioning — wires a Keycloak user to a fresh Deployment + Clubs
 * on first successful migration ingest (S-138). The provisioning service is
 * migration-internal; S-141's ingest pipeline calls into
 * {@code application.DeploymentProvisioningService} at the right moment.
 *
 * <p>Module shape follows ADR 0023:
 * <ul>
 *   <li>{@code domain/} — port interface for the user directory
 *       (Keycloak), exceptions.</li>
 *   <li>{@code application/} — provisioning service, reference-data seeder,
 *       command + result records.</li>
 *   <li>{@code web/} — exception handler that translates
 *       {@code DeploymentExistsException} to the structured 409 body, plus
 *       the test-profile trigger endpoint.</li>
 *   <li>{@code infra/} — Keycloak adapter implementing the directory
 *       port.</li>
 * </ul>
 */
package ch.alpenflight.tenancy.provisioning;
