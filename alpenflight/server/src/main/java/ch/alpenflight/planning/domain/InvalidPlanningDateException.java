package ch.alpenflight.planning.domain;

/**
 * Raised when a {@code PlanningDay}'s {@code planning_date} falls outside the
 * sane range (per ADR 0022 directive 2 — the V4 schema deliberately omits the
 * {@code ck_pln_planning_date_reasonable} CHECK; the rule lives on
 * {@link PlanningDay#validatePlanningDate}). Translated to HTTP 422 by the
 * planning web layer (T-04); the domain exception stays free of Spring web
 * imports per ADR 0023.
 */
public class InvalidPlanningDateException extends RuntimeException {

    public InvalidPlanningDateException(String message) {
        super(message);
    }
}
