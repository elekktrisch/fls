package ch.fls.legacyextract.tenant;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * One row of the tenant-scope catalog. Consumed by S-022 (annotations),
 * S-023 (unscoped contexts), S-024 (leakage CI), S-025 (public-flow resolver).
 *
 * @param legacyTable        legacy SQL Server table name as it appears in S-010's {@code tables.json}
 * @param targetEntity       next-system JPA entity name (e.g. {@code Flight} for legacy {@code Flights})
 * @param legacyScope        classification of the legacy shape — consumed by S-016 migration
 * @param targetScope        classification S-022 / S-024 act on (may differ from legacyScope when S-013 reshapes)
 * @param tenantColumn       column name carrying the tenant identifier in the target schema; null for non-tenant-scoped
 * @param rationaleRef       anchor link into {@code tenant-catalog.md} for the human reasoning
 * @param via                FK chain for {@code INDIRECT_TENANT} entries (e.g. {@code "AircraftId -> Aircrafts.OwnerClubId"})
 * @param preconditions      cross-story preconditions (e.g. {@code "S-013 denormalize club_id from aircraft.owner_club_id"})
 * @param piiBlob            true if the entity holds blob columns containing cross-cluster PII (drives S-027 redaction)
 * @param emitsAudit         true if mutations against this entity should emit audit-log events
 * @param rideThroughTargets list of cross-tenant entities reachable via FK from this tenant-scoped entity
 *                           (consumed by S-024 for ride-through assertions)
 * @param tenancyEnforcement either {@code "hibernate_only"} (default) or {@code "hibernate_plus_rls"}
 *                           if Postgres RLS is layered on top (ADR 0008 follow-up)
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record TenantClassificationRecord(
        String legacyTable,
        String targetEntity,
        TenantScope legacyScope,
        TenantScope targetScope,
        String tenantColumn,
        String rationaleRef,
        String via,
        List<String> preconditions,
        boolean piiBlob,
        boolean emitsAudit,
        List<String> rideThroughTargets,
        String tenancyEnforcement) {}
