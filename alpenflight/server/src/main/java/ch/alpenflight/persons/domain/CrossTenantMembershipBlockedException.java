package ch.alpenflight.persons.domain;

public class CrossTenantMembershipBlockedException extends RuntimeException {

    public CrossTenantMembershipBlockedException(String message) {
        super(message);
    }
}
