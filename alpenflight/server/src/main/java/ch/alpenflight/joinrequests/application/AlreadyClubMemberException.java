package ch.alpenflight.joinrequests.application;

/**
 * Thrown when a submit comes from a principal who already has a {@code t_user}
 * row — the one-sub-one-club rule (S-178). Translated to HTTP 409. The check
 * is eager (at submit) rather than deferred to approval so the pilot gets a
 * clear "you're already a member" message instead of a request that later
 * fails to approve.
 */
public class AlreadyClubMemberException extends RuntimeException {

    public AlreadyClubMemberException() {
        super("The caller already belongs to a club (one sub, one club)");
    }
}
