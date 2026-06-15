package ch.alpenflight.accounting.domain;

/**
 * Thrown when a crew-scoped rule condition needs a crew person's member-number
 * or member-state but that person has no PersonClub for the delivery's club.
 *
 * <p>Reproduces the legacy guard-by-exception: {@code BaseAccountingRule}
 * resolves crew values via {@code person.PersonClubs.First(q =&gt; q.ClubId ==
 * delivery.ClubId)}, and {@code First()} throws when the sequence is empty. The
 * rewrite keeps that fail-loud behavior (a crew person billed under a club they
 * are not a member of is a data defect the engine must not silently absorb)
 * rather than degrading to "no match".
 */
public class MissingPersonClubException extends RuntimeException {

    public MissingPersonClubException(String message) {
        super(message);
    }
}
