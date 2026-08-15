package ch.alpenflight.server.testsupport;

import ch.alpenflight.platform.tenancy.ClubTenantIdentifierResolver;
import ch.alpenflight.platform.tenancy.TenantContextCarrier;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class TenantTestContext {

    public static final UUID NO_TENANT = ClubTenantIdentifierResolver.NO_TENANT;

    private TenantTestContext() {}

    public static void set(UUID tenantId) {
        TenantContextCarrier.set(tenantId);
    }

    public static Optional<UUID> current() {
        return TenantContextCarrier.current();
    }

    public static void clear() {
        TenantContextCarrier.clear();
    }

    public static void runAs(UUID tenantId, Runnable body) {
        runAs(tenantId, () -> {
            body.run();
            return null;
        });
    }

    public static <T> T runAs(UUID tenantId, Supplier<T> body) {
        Optional<UUID> prior = TenantContextCarrier.current();
        TenantContextCarrier.set(tenantId);
        try {
            return body.get();
        } finally {
            if (prior.isPresent()) {
                TenantContextCarrier.set(prior.get());
            } else {
                TenantContextCarrier.clear();
            }
        }
    }

    public static void runUnscoped(Runnable body) {
        runAs(NO_TENANT, body);
    }
}
