package ch.alpenflight.flights.domain;

/**
 * What invoked a flight-state transition. Used both for matrix scoping —
 * e.g. {@code VALID → LOCKED} is legal only under {@link #LOCK_JOB} — and
 * for the audit payload so forensic queries can filter "all transitions
 * driven by the validator" vs. "all transitions an operator drove".
 *
 * <p>{@link #OPERATOR} is the only trigger exposed via the HTTP surface
 * ({@code PATCH /api/v1/flights/{id}/process-state}). System triggers
 * are invoked from background jobs (validator / lock job / delivery
 * preparation / booking) — each lives in its own downstream story.
 */
public enum TransitionTrigger {

    /** A real user manipulating state via the REST API. */
    OPERATOR,

    /** The flight-validation pass — {@code FlightService.cs:900-1075}. */
    VALIDATOR,

    /** The nightly bulk-lock job — {@code FlightService.cs:1140-1185}. */
    LOCK_JOB,

    /** The delivery-preparation job — {@code DeliveryService.cs:130-200}. */
    DELIVERY_PREP,

    /** External finance booking confirmation — {@code DeliveryService.cs:340-360}. */
    BOOKING
}
