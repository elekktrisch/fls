package ch.alpenflight.joinrequests.domain;

public enum JoinRequestStatus {

    PENDING,

    APPROVED,

    DENIED,

    WITHDRAWN;

    public boolean isTerminal() {
        return this != PENDING;
    }
}
