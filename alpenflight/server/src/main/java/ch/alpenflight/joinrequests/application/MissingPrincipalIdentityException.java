package ch.alpenflight.joinrequests.application;

public class MissingPrincipalIdentityException extends RuntimeException {

    public MissingPrincipalIdentityException(String claim) {
        super("JWT is missing the required identity claim: " + claim);
    }
}
