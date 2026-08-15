package ch.alpenflight.migrations.application;

import ch.alpenflight.migration.bundle.Mapper;
import ch.alpenflight.migration.bundle.ReferenceLookup;
import ch.alpenflight.migrations.domain.BundleIngestErrorCode;
import ch.alpenflight.migrations.domain.BundleIngestException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

final class ReferenceLookupResolver implements AutoCloseable {

    private static final long SYNTHETIC_HIGH_BITS = 0L;
    private static final Pattern SEED_TABLE_ALLOWLIST = Pattern.compile("^[A-Za-z0-9_]+$");

    private final Connection connection;
    private final Map<String, PreparedStatement> lookups = new HashMap<>();

    ReferenceLookupResolver(Connection connection) {
        this.connection = connection;
    }

    void rewriteReferenceLookups(Mapper mapper, ObjectNode row) throws SQLException {
        for (ReferenceLookup lookup : mapper.referenceLookups()) {
            JsonNode currentValue = row.get(lookup.column());
            if (currentValue == null || currentValue.isNull()) {
                continue;
            }
            int legacyIntId = decodeSyntheticLegacyIntId(mapper, lookup, currentValue.asText());
            UUID resolved = lookupSeedPkOrNull(lookup.seedTable(), legacyIntId);
            if (resolved == null) {
                throw new BundleIngestException(
                        BundleIngestErrorCode.BUNDLE_CROSS_TENANT_FK_LEAK,
                        "Reference-lookup " + lookup.column() + " on " + mapper.entityType()
                                + " carries legacy_int_id " + legacyIntId
                                + " but " + lookup.seedTable()
                                + " has no row with that legacy_int_id; the Flyway seed must "
                                + "enumerate every reference value the producer emitted");
            }
            row.put(lookup.column(), resolved.toString());
        }
    }

    private static int decodeSyntheticLegacyIntId(Mapper mapper, ReferenceLookup lookup,
                                                  String rawUuid) {
        UUID synthetic;
        try {
            synthetic = UUID.fromString(rawUuid);
        } catch (IllegalArgumentException badUuid) {
            throw new BundleIngestException(
                    BundleIngestErrorCode.NDJSON_PARSE_FAILED,
                    "Reference-lookup " + lookup.column() + " on " + mapper.entityType()
                            + " is not a valid UUID: " + rawUuid, badUuid);
        }
        if (synthetic.getMostSignificantBits() != SYNTHETIC_HIGH_BITS) {
            throw new BundleIngestException(
                    BundleIngestErrorCode.NDJSON_PARSE_FAILED,
                    "Reference-lookup " + lookup.column() + " on " + mapper.entityType()
                            + " is not a legacy-int-id synthetic UUID (high bits non-zero): "
                            + rawUuid);
        }
        return Math.toIntExact(synthetic.getLeastSignificantBits());
    }

    private @Nullable UUID lookupSeedPkOrNull(String seedTable, int legacyIntId)
            throws SQLException {
        PreparedStatement ps = lookups.get(seedTable);
        if (ps == null) {
            if (!SEED_TABLE_ALLOWLIST.matcher(seedTable).matches()) {
                throw new IllegalStateException(
                        "Reference-lookup seed table " + seedTable
                                + " violates [A-Za-z0-9_]+ allowlist — SQL interpolation "
                                + "requires a safe identifier");
            }
            ps = connection.prepareStatement(
                    "SELECT id FROM " + seedTable + " WHERE legacy_int_id = ?");
            lookups.put(seedTable, ps);
        }
        ps.setInt(1, legacyIntId);
        try (ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getObject(1, UUID.class) : null;
        }
    }

    @Override
    public void close() {
        for (PreparedStatement ps : lookups.values()) {
            try {
                ps.close();
            } catch (SQLException ignored) {
            }
        }
        lookups.clear();
    }
}
