/**
 * Tenant provisioning — wires a Keycloak user to a fresh Deployment +
 * Clubs on first successful migration ingest. S-141's bundle-ingest
 * pipeline reaches in directly through
 * {@code application.DeploymentProvisioningService.provision} + the
 * {@code ProvisioningRequest} / {@code ProvisioningResult} / {@code ClubSpec}
 * carrier records.
 *
 * <p>Declared {@link org.springframework.modulith.ApplicationModule.Type#OPEN}
 * so {@code migrations} may import the application-layer carriers without a
 * named-interface re-export — the provisioning surface is co-designed with
 * S-141 and intentionally not isolated.
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
@ApplicationModule(type = ApplicationModule.Type.OPEN)
@NullMarked
package ch.alpenflight.tenancy.provisioning;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
