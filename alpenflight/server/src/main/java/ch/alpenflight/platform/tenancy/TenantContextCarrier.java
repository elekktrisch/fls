package ch.alpenflight.platform.tenancy;

import java.util.Optional;
import java.util.UUID;

public final class TenantContextCarrier {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContextCarrier() {}

    public static Optional<UUID> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static void set(UUID tenantId) {
        CURRENT.set(tenantId);
    }

    public static void clear() {
        CURRENT.remove();
    }
}
