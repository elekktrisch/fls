package ch.alpenflight.deployments.domain;

/**
 * Reconcile state for a {@link Deployment}'s directory-side plumbing.
 * The DB-half (Deployment + Clubs + reference-data) commits in the
 * ingest transaction; the directory-half (group + per-Club realm roles +
 * user attribute) runs post-commit and may fail without rolling the DB.
 * {@link #PENDING} drives the hourly reconcile job; {@link #READY} is
 * the terminal success state. Per ADR 0022 directive 2 the legal values
 * live on the Java side; the schema column has no enum constraint.
 */
public enum KeycloakReconcileState {
    PENDING,
    READY
}
