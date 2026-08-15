package ch.alpenflight.tenancy.provisioning.domain;

public class IdempotencyOwnerMismatchException extends RuntimeException {

    public IdempotencyOwnerMismatchException() {
        super("Idempotency key not found for this owner");
    }
}
