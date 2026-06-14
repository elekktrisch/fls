/**
 * Domain layer for the accounting module — the
 * {@link ch.alpenflight.accounting.domain.AccountingRuleFilter} aggregate root,
 * its typed {@link ch.alpenflight.accounting.domain.FilterConfig} jsonb value
 * object, and the
 * {@link ch.alpenflight.accounting.domain.InvalidAccountingRuleFilterException}
 * domain exception (translated to an HTTP problem response by
 * {@code accounting.web} once it lands in T-06).
 *
 * <p>Per ADR 0023 the {@code domain} package depends only on the JDK, Jakarta
 * Persistence + Hibernate annotations, JSpecify, and the shared
 * {@code platform.persistence} kernel ({@code SoftDeletableAggregate}). No
 * Jackson, no Spring web, no application-layer types — {@code FilterConfig}
 * carries NO Jackson annotations even though it is persisted as jsonb;
 * Hibernate's auto-registered {@code FormatMapper} serialises it reflectively
 * (LayeringRulesTest enforces the Jackson-free boundary).
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.accounting.domain;
