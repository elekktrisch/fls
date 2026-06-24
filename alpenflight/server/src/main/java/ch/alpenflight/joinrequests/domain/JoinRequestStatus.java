package ch.alpenflight.joinrequests.domain;

/**
 * S-178 join-request lifecycle. Stored as {@code @Enumerated(STRING)} in
 * {@code t_join_request.status}. Per ADR 0022 directive 2 the legal set + the
 * transition rule live in Java — no Postgres CHECK constraint pins them.
 *
 * <p>A request opens {@link #PENDING} and moves once into exactly one terminal
 * state. The {@code ux_join_request_alive} partial UNIQUE keys on {@code PENDING}
 * only, so a request leaving this state frees the {@code (sub, club)} pair to
 * re-submit.
 */
public enum JoinRequestStatus {

    /** Open: filed by the pilot, awaiting an admin decision. */
    PENDING,

    /** Terminal: an admin approved the request — the pilot becomes a member. */
    APPROVED,

    /** Terminal: an admin denied the request (optionally with a reason). */
    DENIED,

    /** Terminal: the pilot withdrew their own pending request. */
    WITHDRAWN;

    public boolean isTerminal() {
        return this != PENDING;
    }
}
