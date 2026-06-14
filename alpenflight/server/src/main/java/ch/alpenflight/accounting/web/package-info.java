/**
 * Web adapters for the accounting module (E-09) —
 * {@link ch.alpenflight.accounting.web.AccountingRuleFiltersController} speaks
 * {@code /api/v1/accounting-rule-filters}; the local
 * {@code @RestControllerAdvice} translates the application/domain exceptions
 * (NotFound → 404, Invalid → 400) to RFC 7807 problem responses.
 *
 * <p>Per ADR 0023 this package depends on {@code accounting.application} (the
 * service it adapts) and {@code accounting.domain} (for the exception types) +
 * Spring web. It must NOT depend on {@code accounting.infra}.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.accounting.web;
