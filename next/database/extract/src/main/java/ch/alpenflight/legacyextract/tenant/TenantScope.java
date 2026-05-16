package ch.alpenflight.legacyextract.tenant;

/**
 * Tenant-scope classification for an entity. Closed vocabulary — every legacy
 * table reaches exactly one of these values for both {@code legacy_scope} and
 * {@code target_scope} (the values may differ when S-013 reshapes the entity,
 * e.g. {@code Flights}: legacy {@code INDIRECT_TENANT} → target {@code TENANT_SCOPED}
 * via denormalization of {@code aircraft.owner_club_id} into {@code flight.club_id}).
 *
 * <p>Classification rules (first match wins, see {@link TenantClassifier}):
 * <ol>
 *   <li>YAML override {@code kind: reference} → {@link #REFERENCE_DATA}</li>
 *   <li>YAML override {@code kind: principal} → {@link #PRINCIPAL_SUBJECT}</li>
 *   <li>YAML override {@code kind: cross-tenant} → {@link #CROSS_TENANT}</li>
 *   <li>YAML override {@code kind: system} → {@link #SYSTEM_GLOBAL}</li>
 *   <li>Table has a {@code ClubId} column → {@link #TENANT_SCOPED}</li>
 *   <li>No {@code ClubId} but FK reaches a {@code TENANT_SCOPED} table within
 *       1 hop → {@link #INDIRECT_TENANT}</li>
 * </ol>
 */
public enum TenantScope {

    /**
     * Carries a {@code ClubId} column (or its target equivalent {@code club_id})
     * and is filtered by Hibernate {@code @TenantId} on every JPA query.
     * Examples: {@code Aircraft}, {@code Delivery}, {@code AuditLogs}.
     */
    TENANT_SCOPED,

    /**
     * No {@code ClubId} — referenced by tenant-scoped entities via foreign keys
     * but not itself tenant-filtered. Loading by primary key works across tenants
     * by design (sacred cow: cross-club crew, multi-club Persons).
     * Examples: {@code Person}, {@code PersonClub}.
     */
    CROSS_TENANT,

    /**
     * No tenant concept at all. System-level configuration, migration metadata,
     * single-row settings.
     */
    SYSTEM_GLOBAL,

    /**
     * Static lookup data shared across all tenants (countries, language
     * translations, fixed enum-like tables). Never gets {@code @TenantId}.
     */
    REFERENCE_DATA,

    /**
     * No native {@code ClubId} column today but reaches tenant scope through a
     * single FK hop. In the new schema (S-013), these are reshaped to
     * {@link #TENANT_SCOPED} via denormalization. Canonical case:
     * {@code Flights} → {@code aircraft.owner_club_id}.
     */
    INDIRECT_TENANT,

    /**
     * The principal subject of tenancy, not a tenant-scoped row. Has a
     * {@code ClubId} column (the user's primary club) but tenancy resolves
     * FROM this entity, not OVER it. Canonical case: {@code Users}.
     * Cannot carry {@code @TenantId} (the resolver would chicken-and-egg
     * the user load).
     */
    PRINCIPAL_SUBJECT,
}
