/**
 * Provisioning domain — the
 * {@link ch.alpenflight.tenancy.provisioning.domain.KeycloakDeploymentDirectory}
 * port (group + per-Club role + user-attribute reconcile) plus the
 * {@link ch.alpenflight.tenancy.provisioning.domain.DeploymentExistsException}
 * raised when an owner already holds a non-terminal Deployment. Depends on
 * JDK only — per ADR 0023 the domain layer must not pull in Spring /
 * Jackson / Hibernate.
 */
package ch.alpenflight.tenancy.provisioning.domain;
