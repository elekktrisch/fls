package ch.alpenflight.platform.tenancy;

/**
 * Thrown when a write would persist a tenant-scoped row whose
 * {@code @TenantId} value resolved to the {@link ClubTenantIdentifierResolver#NO_TENANT}
 * nil UUID. Rejecting at write time keeps the sentinel out of real rows;
 * reads return empty by construction because no real row carries nil.
 */
public class MissingTenantContextException extends RuntimeException {

    public MissingTenantContextException(String message) {
        super(message);
    }
}
