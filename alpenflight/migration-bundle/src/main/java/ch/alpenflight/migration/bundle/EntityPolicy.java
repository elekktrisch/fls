package ch.alpenflight.migration.bundle;

import java.util.List;
import java.util.Set;

/**
 * Per-entity port policy. Carried inside {@link Manifest} on the wire and
 * consulted by the producer (S-139) before export and the consumer (S-141)
 * before ingest.
 *
 * @param tenantBypassFks   FK columns that legitimately cross tenants
 *                          (Person.PrimaryClubId, Aircraft.ManagingClubId,
 *                          Location.HomeClubId per ADR 0008). Empty for
 *                          single-tenant entities.
 * @param columnAllowList   columns allowed on the wire for this entity.
 *                          Defense-in-depth against PII drift; the mapper's
 *                          {@code columns()} must be a subset (asserted by
 *                          a per-mapper test in S-184/S-185/S-186).
 */
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
        /** Bundle carries rows; consumer inserts into the destination table. */
        FULL_PORT,
        /**
         * Bundle carries (legacy_guid, lookup_key) pairs; consumer populates
         * {@code legacy_id_map_<entity>} by joining against the pre-seeded
         * destination via the lookup key (per S-012). Used for SYSTEM_GLOBAL
         * refs whose destination rows are owned by V2 / V3 / V4 seed inserts.
         *
         * <p>Mappers under this policy do NOT enumerate their FK targets in
         * {@link Mapper#foreignKeys()}: the resolution is structural through
         * {@code legacy_int_id}, not a per-bundle dependency.
         */
        SYSTEM_GLOBAL_RESOLVE,
        /** Manifest declares the entity but the bundle MAY omit rows. */
        OPTIONAL,
    }

    public enum TombstonePolicy {
        /** Carry rows where {@code IsDeleted=1} as {@code deleted_on IS NOT NULL}. */
        PORT_ALL,
        /** Drop tombstoned rows. Reference tables; noise. */
        SKIP_DELETED,
    }
}
