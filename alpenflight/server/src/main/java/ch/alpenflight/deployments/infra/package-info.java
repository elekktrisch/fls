/**
 * Deployments infra — Spring Data JPA implementation of
 * {@link ch.alpenflight.deployments.domain.DeploymentRepository}. Free of
 * domain logic per ADR 0023; ports in {@code deployments.domain} are the
 * application layer's seam.
 */
@NullMarked
package ch.alpenflight.deployments.infra;

import org.jspecify.annotations.NullMarked;
