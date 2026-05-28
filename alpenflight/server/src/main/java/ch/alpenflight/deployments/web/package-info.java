/**
 * Deployments web — the {@code POST /api/v1/admin/deployments/{id}/lifecycle}
 * admin endpoint + a colocated {@link org.springframework.web.bind.annotation.RestControllerAdvice}
 * translating the {@code deployments.domain} exception vocabulary to HTTP.
 *
 * <p>Per ADR 0023 the controller depends on
 * {@code deployments.application} (DTOs + service) and
 * {@code deployments.domain} (caught exception types only). It MUST NOT
 * reach into {@code deployments.infra}.
 */
@NullMarked
package ch.alpenflight.deployments.web;

import org.jspecify.annotations.NullMarked;
