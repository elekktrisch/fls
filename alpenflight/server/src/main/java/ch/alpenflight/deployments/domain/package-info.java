/**
 * Deployments domain — {@link ch.alpenflight.deployments.domain.Deployment}
 * aggregate, {@link ch.alpenflight.deployments.domain.LifecycleState} +
 * {@link ch.alpenflight.deployments.domain.Plan} enums (per ADR 0020 +
 * ADR 0022 directive 2 — pinned in Java only, never as a CHECK or DB enum
 * type), the FSM-method suite on the aggregate root, the
 * {@link ch.alpenflight.deployments.domain.DeploymentLifecycleTransitioned}
 * domain event the S-027 audit listener subscribes to, and the
 * {@link ch.alpenflight.deployments.domain.DeploymentRepository} port.
 *
 * <p>Per ADR 0022 directive 2 the legal-transition table lives on the
 * aggregate; the schema enforces only enum-literal-as-text. The partial
 * UNIQUE {@code ux_deployment_owner_active} is identity-structural
 * (one user holds at most one non-terminal Deployment) — the ADR 0022
 * exception for identity-bearing partial UNIQUE indexes.
 */
@NullMarked
package ch.alpenflight.deployments.domain;

import org.jspecify.annotations.NullMarked;
