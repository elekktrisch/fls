package ch.alpenflight.platform.tenancy;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

public final class Tenants {

    private Tenants() {}

    public static void runAs(UUID clubId, Runnable body) {
        runAs(clubId, () -> {
            body.run();
            return null;
        });
    }

    public static <T> T runAs(UUID clubId, Supplier<T> body) {
        if (clubId == null) {
            throw new IllegalArgumentException("clubId must not be null");
        }
        if (ClubTenantIdentifierResolver.NO_TENANT.equals(clubId)) {
            throw new IllegalArgumentException("clubId must not be the NO_TENANT sentinel");
        }
        Optional<UUID> prior = TenantContextCarrier.current();
        @Nullable Object priorRequestHint = RequestTenantHint.recordIfHttp(clubId);
        TenantContextCarrier.set(clubId);
        try {
            return body.get();
        } finally {
            if (prior.isPresent()) {
                TenantContextCarrier.set(prior.get());
            } else {
                TenantContextCarrier.clear();
            }
            RequestTenantHint.restoreIfHttp(priorRequestHint);
        }
    }
}
