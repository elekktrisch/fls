/**
 * Application layer for the accounting module — the orchestration service for
 * the {@link ch.alpenflight.accounting.domain.AccountingRuleFilter} aggregate
 * plus its wire-shape DTOs. No Spring web concerns leak here; the controller
 * lives in {@code accounting.web} (T-06).
 *
 * <p>{@link ch.alpenflight.accounting.application.AccountingRuleFiltersService}
 * is transactional, trusts Hibernate's {@code @TenantId} discriminator for
 * isolation (a cross-tenant id is invisible → 404 via
 * {@link ch.alpenflight.accounting.application.AccountingRuleFilterNotFoundException}),
 * and emits an audit event on every mutation. Per ADR 0022 directive 2 the
 * business invariants live on the aggregate; the service only orchestrates —
 * including the legacy {@code $scope.save} target-by-type assignment + the
 * threshold/duration normalisation, which are wire-shape translation, not
 * domain rules.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.accounting.application;
