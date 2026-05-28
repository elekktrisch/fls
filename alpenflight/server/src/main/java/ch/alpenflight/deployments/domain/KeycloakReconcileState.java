package ch.alpenflight.deployments.domain;

/**
 * Phase B reconcile state for a {@link Deployment}. The DB-half (Phase A —
 * Deployment + Clubs + reference-data) commits in the ingest transaction;
 * the Keycloak-half (Phase B — deployment group + per-Club realm roles +
 * user attribute) runs post-commit and may fail without rolling the DB.
 *
 * <ul>
 *   <li>{@link #PENDING} — Phase B has not yet completed. The S-141 hourly
 *       reconcile job re-invokes
 *       {@code DeploymentProvisioningService.reconcileKeycloak} for every
 *       Deployment in this state until it succeeds.</li>
 *   <li>{@link #READY} — Phase B finished. The directory now carries the
 *       deployment group + per-Club admin roles + the user-attribute
 *       {@code clubId}; the retry job ignores the row.</li>
 * </ul>
 *
 * <p>Per ADR 0022 directive 2 the legal values live on the Java side; the
 * V15 schema stores the column as {@code VARCHAR(16)} without an enum
 * constraint, so adding states later doesn't need a migration.
 */
public enum KeycloakReconcileState {
    PENDING,
    READY
}
