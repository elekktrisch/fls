/**
 * Migration-upload aggregate root, state-machine enum, repository port,
 * crypto port, funnel-telemetry port, domain exceptions.
 *
 * <p>Per ADR 0023 this package is the stable centre of the migrations
 * module — the aggregate carries the state-machine invariants in Java
 * (per ADR 0022 directive 2 — no DB CHECK on state), and the persistence
 * + crypto + telemetry boundaries are expressed as ports implemented in
 * {@code migrations.infra} and {@code migrations.application}.
 *
 * <p>Allowed dependencies: the JDK, JPA annotations (deliberate
 * Hibernate-on-aggregate concession per ADR 0023), JSpecify nullability
 * markers, {@code ch.alpenflight.platform.*} shared kernel,
 * {@code ch.alpenflight.audit.domain.AuditRedact}. Forbidden: Spring
 * web, Spring stereotypes, Jackson, Tink (the crypto port is implemented
 * downstream in {@code infra} — the domain only sees the port interface).
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.migrations.domain;
