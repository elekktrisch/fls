package ch.alpenflight.migration.bundle;

import java.util.List;
import java.util.Set;

public record EntityPolicy(
        PortPolicy portPolicy,
        TombstonePolicy tombstonePolicy,
        Set<String> tenantBypassFks,
        List<String> columnAllowList) {

    public EntityPolicy {
        if (portPolicy == null || tombstonePolicy == null) {
            throw new IllegalArgumentException("portPolicy and tombstonePolicy are required");
        }
        tenantBypassFks = tenantBypassFks == null
                ? Set.of()
                : Set.copyOf(tenantBypassFks);
        columnAllowList = columnAllowList == null
                ? List.of()
                : List.copyOf(columnAllowList);
    }

    public enum PortPolicy {
        FULL_PORT,
        SYSTEM_GLOBAL_RESOLVE,
        OPTIONAL,
    }

    public enum TombstonePolicy {
        PORT_ALL,
        SKIP_DELETED,
    }
}
