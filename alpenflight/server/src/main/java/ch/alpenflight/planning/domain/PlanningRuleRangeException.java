package ch.alpenflight.planning.domain;

/**
 * Raised when the bulk weekday-expansion rule (T-05) covers a date span larger
 * than {@link PlanningDay#MAX_RULE_SPAN_DAYS}. Legacy
 * ({@code PlanningDayService.cs:304}) is unbounded; V4's dedup-aware save makes
 * an unbounded rule an expensive way to insert thousands of rows, so the domain
 * caps it (ADR 0022 directive 2 — the bound is a domain rule, not a DB CHECK).
 *
 * <p>Translated to HTTP 422 (key {@code planning.rule.range}) by the planning
 * web layer (T-05); the domain exception stays free of Spring web imports per
 * ADR 0023.
 */
public class PlanningRuleRangeException extends RuntimeException {

    public PlanningRuleRangeException(String message) {
        super(message);
    }
}
