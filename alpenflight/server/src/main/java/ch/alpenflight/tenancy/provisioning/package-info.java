/**
 * Tenant provisioning — wires a Keycloak user to a fresh Deployment + Clubs
 * on first successful migration ingest (S-138). The service is migration-
 * internal; no public REST surface (S-141's ingest pipeline calls it).
 *
 * <p>Status: skeleton DTOs landed; service + KC reconcile + reference-data
 * seeder pending. See S-138 story {@code ## Pickup notes} for the build
 * order.
 */
package ch.alpenflight.tenancy.provisioning;
