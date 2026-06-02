package ch.alpenflight.migrations.application;

import ch.alpenflight.migration.bundle.EntityPolicy;
import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.ForeignKeyColumn;
import ch.alpenflight.migration.bundle.LegacyIdMapTables;
import ch.alpenflight.migration.bundle.Mapper;
import ch.alpenflight.migrations.domain.BundleIngestErrorCode;
import ch.alpenflight.migrations.domain.BundleIngestException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Per-bundle FK resolver. Translates legacy GUIDs carried in
 * {@code <Entity>.ndjson} rows to the new-stack UUIDs the destination
 * table FKs reference, by joining each {@link Mapper#foreignKeys()}
 * target against {@code legacy_id_map_<entity>} (seeded earlier in the
 * ingest by the {@code legacy_id_map/<entity>.pgcopy} tar entries +
 * {@link EntityStreamIngestor#seedClubLegacyIdMap}).
 *
 * <p>SYSTEM_GLOBAL_RESOLVE FKs (per {@link BundleManifest#entityPolicies()})
 * are <em>required</em> to resolve — the bundle pgcopy entries are the
 * sole source of the legacy-to-V2-seed translation, and a missing entry
 * indicates the producer dropped a row the consumer relied on. Surface
 * {@link BundleIngestErrorCode#BUNDLE_CROSS_TENANT_FK_LEAK}.
 *
 * <p>FULL_PORT FKs (e.g. {@code User.club_id} → {@code CLUB}) translate
 * via {@code legacy_id_map_club} which the orchestrator pre-seeds in
 * {@code seedClubLegacyIdMap}. A missing entry there leaves the field
 * untouched and the downstream INSERT surfaces the FK violation
 * naturally — the orchestrator's per-mapper save and audit trail then
 * carries the failure cleanly.
 *
 * <p>Fan-out FKs (J-0b T-07 — target {@link EntityType#fansOut()}, e.g.
 * {@code InOutboundPoint.location_id} → {@code LOCATION}) cannot use the
 * single-key lookup: the fan-out target's {@code legacy_id_map_<target>}
 * holds N rows per shared {@code legacy_guid} (one per club). The lookup is
 * keyed composite {@code (legacy_guid, club_id)} on the referencer row's
 * OWN legacy {@code club_id} (a wire-only field the producer fans the
 * referencer out with), landing on that club's replica. A composite miss is
 * <em>fail-closed</em> (mirrors the SYSTEM_GLOBAL path) — never a verbatim FK.
 *
 * <p>Column-name convention (vertical-slice mappers, S-187): by default the
 * FK column is {@code <target.name().toLowerCase()>_id} — e.g.
 * {@code club_id}, {@code country_id}, {@code language_id}. A mapper that names
 * its FK columns off-convention, or references one target through several
 * columns (e.g. {@code Aircraft.managing_club_id} + {@code owner_club_id} →
 * CLUB, {@code Aircraft.homebase_id} → LOCATION), declares each
 * {@code (column, target)} pair via {@link Mapper#foreignKeyColumns()} (S-187a);
 * undeclared targets keep the convention.
 *
 * <p>Stateful — caches one prepared statement per target entity, scoped
 * to the ingest connection. Closed by {@link #close} when the per-entity
 * NDJSON drain completes.
 */
final class ForeignKeyResolver implements AutoCloseable {

    /**
     * Wire-only field a fan-out referencer carries to name its OWN legacy club
     * (T-05). Absent from the referencer's destination columns — consumed here
     * only to disambiguate which fan-out replica its FK points at.
     */
    private static final String REFERENCER_CLUB_FIELD = "club_id";

    private final Connection connection;
    private final BundleManifest manifest;
    private final Map<EntityType, PreparedStatement> lookups = new EnumMap<>(EntityType.class);
    private final Map<EntityType, PreparedStatement> compositeLookups =
            new EnumMap<>(EntityType.class);

    ForeignKeyResolver(Connection connection, BundleManifest manifest) {
        this.connection = connection;
        this.manifest = manifest;
    }

    /**
     * Walk the mapper's FK columns and rewrite each present legacy GUID to the
     * resolved new-stack UUID. Mutates {@code row} in place.
     *
     * <p>Each {@code (column, target)} pair is resolved independently: a target
     * declared via {@link Mapper#foreignKeyColumns()} uses its explicit column
     * name(s) — so the SAME target referenced through MULTIPLE columns (e.g.
     * AIRCRAFT's {@code managing_club_id} + {@code owner_club_id} → CLUB) resolves
     * each column — while any {@link Mapper#foreignKeys()} target NOT declared
     * falls back to the {@code <target>_id} convention. This generalises S-187's
     * one-column-per-target convention without changing it (S-187a).
     */
    void rewriteForeignKeys(Mapper mapper, ObjectNode row) throws SQLException {
        for (ForeignKeyBinding binding : foreignKeyBindings(mapper)) {
            EntityType target = binding.target();
            String field = binding.column();
            JsonNode currentValue = row.get(field);
            if (currentValue == null || currentValue.isNull()) {
                continue;
            }
            UUID legacyGuid;
            try {
                legacyGuid = UUID.fromString(currentValue.asText());
            } catch (IllegalArgumentException badUuid) {
                throw new BundleIngestException(
                        BundleIngestErrorCode.NDJSON_PARSE_FAILED,
                        "FK field " + field + " on " + mapper.entityType()
                                + " is not a valid UUID: " + currentValue.asText(),
                        badUuid);
            }
            if (target.fansOut()) {
                resolveFanOutForeignKey(
                        mapper, row, target, field, binding.disambiguatorColumn(), legacyGuid);
                continue;
            }
            UUID resolved = lookupOrNull(target, legacyGuid);
            if (resolved != null) {
                row.put(field, resolved.toString());
            } else if (isSystemGlobalResolve(target)) {
                throw new BundleIngestException(
                        BundleIngestErrorCode.BUNDLE_CROSS_TENANT_FK_LEAK,
                        "FK " + field + " on " + mapper.entityType()
                                + " carries legacy guid " + legacyGuid
                                + " but legacy_id_map_" + target
                                + " has no resolution; the SYSTEM_GLOBAL bundle entry must "
                                + "enumerate every value the producer emitted");
            }
            // Else: FULL_PORT target with no mapping yet — let the FK
            // constraint surface the failure on INSERT.
        }
    }

    /**
     * Composite FK resolution for a {@link EntityType#fansOut()} target (J-0b
     * T-07). The fan-out target's {@code legacy_id_map_<target>} carries N rows
     * per shared {@code legacy_guid} — one per club — so the single-key lookup
     * is ambiguous. Disambiguate on the referencer's OWN legacy club: the
     * wire-only {@code club_id} field the producer fans the referencer out with
     * (the IOP child carries its own club; a downstream Flight referencer would
     * likewise). Read it from the PARSED row, not from a destination column.
     *
     * <p>Fail-closed on a composite miss — mirror the SYSTEM_GLOBAL path: a
     * {@code (legacy_guid, club_id)} pair absent from the composite map aborts
     * the ingest with a clear error rather than landing a verbatim FK that
     * violates a constraint opaquely.
     */
    private void resolveFanOutForeignKey(
            Mapper mapper,
            ObjectNode row,
            EntityType target,
            String field,
            @Nullable String disambiguatorColumn,
            UUID legacyGuid)
            throws SQLException {
        String clubField =
                disambiguatorColumn != null ? disambiguatorColumn : REFERENCER_CLUB_FIELD;
        JsonNode clubValue = row.get(clubField);
        if (clubValue == null || clubValue.isNull()) {
            throw new BundleIngestException(
                    BundleIngestErrorCode.BUNDLE_CROSS_TENANT_FK_LEAK,
                    "FK " + field + " on " + mapper.entityType() + " targets fan-out "
                            + target + " but the referencer row carries no own "
                            + clubField + " to disambiguate which replica it means");
        }
        UUID referencerClubId;
        try {
            referencerClubId = UUID.fromString(clubValue.asText());
        } catch (IllegalArgumentException badUuid) {
            throw new BundleIngestException(
                    BundleIngestErrorCode.NDJSON_PARSE_FAILED,
                    clubField + " on " + mapper.entityType()
                            + " is not a valid UUID: " + clubValue.asText(),
                    badUuid);
        }
        UUID resolved = lookupCompositeOrNull(target, legacyGuid, referencerClubId);
        if (resolved == null) {
            throw new BundleIngestException(
                    BundleIngestErrorCode.BUNDLE_CROSS_TENANT_FK_LEAK,
                    "FK " + field + " on " + mapper.entityType()
                            + " carries legacy guid " + legacyGuid + " for club "
                            + referencerClubId + " but legacy_id_map_" + target
                            + " has no replica for that (legacy_guid, club_id) pair; the "
                            + "fan-out producer must emit one id-map row per referencing club");
        }
        row.put(field, resolved.toString());
    }

    /**
     * Rewrite a FULL_PORT row's own legacy id (the {@code legacy_guid}
     * carrier for the destination {@code id} per ADR 0019) to the new-stack
     * UUID via {@code legacy_id_map_<self>}.
     *
     * <p>Unlike {@link #rewriteForeignKeys}, a miss is <em>fail-closed</em>:
     * a row's own id has no downstream FK constraint to surface a dangling
     * value, so an unmapped id would conflict with, or insert past, a row no
     * upstream step provisioned.
     */
    void rewriteSelfId(EntityType selfType, String idField, ObjectNode row) throws SQLException {
        JsonNode idValue = row.get(idField);
        if (idValue == null || idValue.isNull()) {
            throw new BundleIngestException(
                    BundleIngestErrorCode.NDJSON_PARSE_FAILED,
                    selfType + " row is missing its " + idField + " identity");
        }
        UUID legacyId;
        try {
            legacyId = UUID.fromString(idValue.asText());
        } catch (IllegalArgumentException badUuid) {
            throw new BundleIngestException(
                    BundleIngestErrorCode.NDJSON_PARSE_FAILED,
                    selfType + " " + idField + " is not a valid UUID: " + idValue.asText(),
                    badUuid);
        }
        UUID resolved = lookupOrNull(selfType, legacyId);
        if (resolved == null) {
            throw new BundleIngestException(
                    BundleIngestErrorCode.BUNDLE_CROSS_TENANT_FK_LEAK,
                    selfType + " row carries legacy id " + legacyId
                            + " that this bundle's manifest did not provision; legacy_id_map_"
                            + selfType + " has no resolution");
        }
        row.put(idField, resolved.toString());
    }

    private @Nullable UUID lookupOrNull(EntityType target, UUID legacyGuid) throws SQLException {
        PreparedStatement ps = lookups.get(target);
        if (ps == null) {
            ps = connection.prepareStatement(
                    "SELECT new_uuid FROM " + LegacyIdMapTables.temporaryTableName(target)
                            + " WHERE legacy_guid = ?");
            lookups.put(target, ps);
        }
        ps.setObject(1, legacyGuid);
        try (ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getObject(1, UUID.class) : null;
        }
    }

    private @Nullable UUID lookupCompositeOrNull(
            EntityType target, UUID legacyGuid, UUID clubId) throws SQLException {
        PreparedStatement ps = compositeLookups.get(target);
        if (ps == null) {
            ps = connection.prepareStatement(
                    "SELECT new_uuid FROM " + LegacyIdMapTables.temporaryTableName(target)
                            + " WHERE legacy_guid = ? AND club_id = ?");
            compositeLookups.put(target, ps);
        }
        ps.setObject(1, legacyGuid);
        ps.setObject(2, clubId);
        try (ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getObject(1, UUID.class) : null;
        }
    }

    private boolean isSystemGlobalResolve(EntityType target) {
        EntityPolicy policy = manifest.entityPolicies().get(target);
        return policy != null
                && policy.portPolicy() == EntityPolicy.PortPolicy.SYSTEM_GLOBAL_RESOLVE;
    }

    /**
     * The effective ordered list of FK column bindings for a mapper: every
     * {@link Mapper#foreignKeyColumns()} declaration verbatim, plus a
     * convention-derived {@code <target>_id} binding for each
     * {@link Mapper#foreignKeys()} target NOT already covered by a declaration.
     * A target covered by one or more declared columns is resolved ONLY through
     * those columns (never additionally by convention).
     */
    private static List<ForeignKeyBinding> foreignKeyBindings(Mapper mapper) {
        List<ForeignKeyColumn> declared = mapper.foreignKeyColumns();
        List<ForeignKeyBinding> bindings = new ArrayList<>(declared.size());
        EnumSet<EntityType> declaredTargets = EnumSet.noneOf(EntityType.class);
        for (ForeignKeyColumn fk : declared) {
            bindings.add(new ForeignKeyBinding(fk.column(), fk.target(), fk.disambiguatorColumn()));
            declaredTargets.add(fk.target());
        }
        for (EntityType target : mapper.foreignKeys()) {
            if (!declaredTargets.contains(target)) {
                bindings.add(new ForeignKeyBinding(conventionalForeignKeyField(target), target, null));
            }
        }
        return bindings;
    }

    private static String conventionalForeignKeyField(EntityType target) {
        return target.name().toLowerCase(Locale.ROOT) + "_id";
    }

    /** One resolved FK column → target pair (with optional fan-out disambiguator). */
    private record ForeignKeyBinding(
            String column, EntityType target, @Nullable String disambiguatorColumn) {}

    @Override
    public void close() {
        closeAll(lookups);
        closeAll(compositeLookups);
    }

    private static void closeAll(Map<EntityType, PreparedStatement> statements) {
        for (PreparedStatement ps : statements.values()) {
            try {
                ps.close();
            } catch (SQLException ignored) {
                // Connection close-time will release whatever leaked.
            }
        }
        statements.clear();
    }
}
