package ch.alpenflight.joinrequests.application;

public class AlreadyClubMemberException extends RuntimeException {

    public AlreadyClubMemberException() {
        super("The caller already belongs to a club (one sub, one club)");
    }
}
