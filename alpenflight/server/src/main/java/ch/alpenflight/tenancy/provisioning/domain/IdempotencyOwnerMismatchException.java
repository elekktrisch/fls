package ch.alpenflight.tenancy.provisioning.domain;

/**
 * Idempotency-key replay arrived under a different owner than the one
 * bound on the original provisioning. Translates to a 404 — the
 * application MUST NOT confirm the key is bound to someone else, since
 * that would let an attacker probe for in-flight provisionings by
 * guessing or stealing migration-run identifiers.
 */
public class IdempotencyOwnerMismatchException extends RuntimeException {

    public IdempotencyOwnerMismatchException() {
        super("Idempotency key not found for this owner");
    }
}
