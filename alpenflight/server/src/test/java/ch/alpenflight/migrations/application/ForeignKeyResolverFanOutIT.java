package ch.alpenflight.migrations.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.LegacyIdMapTables;
import ch.alpenflight.migration.bundle.flight.InOutboundPointMapper;
import ch.alpenflight.migration.bundle.flight.LocationMapper;
import ch.alpenflight.migrations.domain.BundleIngestErrorCode;
import ch.alpenflight.migrations.domain.BundleIngestException;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * J-0b T-07 — club-aware FK resolution. The {@link InOutboundPointMapper} FK
 * target {@code LOCATION} is a fan-out entity, so {@code legacy_id_map_location}
 * carries N rows per shared {@code legacy_guid} (one per club). The single-key
 * lookup is therefore ambiguous; the resolver must key the composite
 * {@code (legacy_guid, club_id)} on the referencer row's OWN legacy
 * {@code club_id} wire field and land on the matching replica — failing closed
 * on a composite miss. A non-fan-out FK target keeps the single-key path.
 *
 * <p>Runs the resolver against real Postgres (the layer it operates at): the
 * temp id-map tables are created with the exact DDL the
 * {@code EntityStreamIngestor} emits (composite PK for fan-out, single-key for
 * non-fan-out) and seeded directly, so this slice exercises the live SQL the
 * resolver issues without the full HTTP bundle path.
 */
@Tag("slow")
class ForeignKeyResolverFanOutIT extends PostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired DataSource dataSource;

    @Test
    void fanout_fk_target_resolves_per_club_replica() throws Exception {
        UUID sharedLocationGuid = UUID.randomUUID();
        UUID clubA = UUID.randomUUID();
        UUID clubB = UUID.randomUUID();
        UUID replicaA = UUID.randomUUID();
        UUID replicaB = UUID.randomUUID();

        try (Connection connection = txConnection()) {
            createCompositeIdMap(connection, EntityType.LOCATION);
            seedComposite(connection, EntityType.LOCATION, sharedLocationGuid, clubA, replicaA);
            seedComposite(connection, EntityType.LOCATION, sharedLocationGuid, clubB, replicaB);

            try (ForeignKeyResolver resolver = new ForeignKeyResolver(connection, manifest())) {
                // Referencer in club A pointing at the shared legacy Location must
                // resolve to club A's replica, not club B's.
                ObjectNode childA = iopRow(sharedLocationGuid, clubA);
                resolver.rewriteForeignKeys(new InOutboundPointMapper(), childA);
                assertThat(UUID.fromString(childA.get("location_id").asText())).isEqualTo(replicaA);

                // A DIFFERENT club's row resolves to a DIFFERENT replica.
                ObjectNode childB = iopRow(sharedLocationGuid, clubB);
                resolver.rewriteForeignKeys(new InOutboundPointMapper(), childB);
                assertThat(UUID.fromString(childB.get("location_id").asText())).isEqualTo(replicaB);
            }
            connection.commit();
        }
    }

    @Test
    void composite_miss_fails_closed() throws Exception {
        UUID sharedLocationGuid = UUID.randomUUID();
        UUID provisionedClub = UUID.randomUUID();
        UUID unprovisionedClub = UUID.randomUUID();

        try (Connection connection = txConnection()) {
            createCompositeIdMap(connection, EntityType.LOCATION);
            seedComposite(connection, EntityType.LOCATION, sharedLocationGuid, provisionedClub,
                    UUID.randomUUID());

            try (ForeignKeyResolver resolver = new ForeignKeyResolver(connection, manifest())) {
                ObjectNode child = iopRow(sharedLocationGuid, unprovisionedClub);
                assertThatThrownBy(() ->
                        resolver.rewriteForeignKeys(new InOutboundPointMapper(), child))
                        .isInstanceOf(BundleIngestException.class)
                        .extracting(e -> ((BundleIngestException) e).getErrorCode())
                        .isEqualTo(BundleIngestErrorCode.BUNDLE_CROSS_TENANT_FK_LEAK);
            }
            connection.commit();
        }
    }

    @Test
    void non_fanout_fk_target_resolves_via_single_key() throws Exception {
        // LocationMapper.foreignKeys() = [CLUB, COUNTRY], both NON-fan-out, so the
        // single-key path (WHERE legacy_guid = ?) stays in force.
        UUID legacyClub = UUID.randomUUID();
        UUID newClub = UUID.randomUUID();
        UUID legacyCountry = UUID.randomUUID();
        UUID newCountry = UUID.randomUUID();

        try (Connection connection = txConnection()) {
            createSingleKeyIdMap(connection, EntityType.CLUB);
            createSingleKeyIdMap(connection, EntityType.COUNTRY);
            seedSingleKey(connection, EntityType.CLUB, legacyClub, newClub);
            seedSingleKey(connection, EntityType.COUNTRY, legacyCountry, newCountry);

            ObjectNode row = JSON.createObjectNode();
            row.put("club_id", legacyClub.toString());
            row.put("country_id", legacyCountry.toString());

            try (ForeignKeyResolver resolver = new ForeignKeyResolver(connection, manifest())) {
                resolver.rewriteForeignKeys(new LocationMapper(), row);
            }

            assertThat(UUID.fromString(row.get("club_id").asText())).isEqualTo(newClub);
            assertThat(UUID.fromString(row.get("country_id").asText())).isEqualTo(newCountry);
            connection.commit();
        }
    }

    /** Connection in a manual transaction so {@code ON COMMIT DROP} temp tables
     * are released on {@link Connection#commit()} before the connection returns
     * to the pool — keeping subsequent tests' temp-table creation collision-free. */
    private Connection txConnection() throws Exception {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        return connection;
    }

    private static ObjectNode iopRow(UUID legacyLocationId, UUID childOwnLegacyClub) {
        ObjectNode row = JSON.createObjectNode();
        row.put("location_id", legacyLocationId.toString());
        // The child's OWN legacy club — the wire-only resolver field (T-05).
        row.put("club_id", childOwnLegacyClub.toString());
        return row;
    }

    private static BundleManifest manifest() {
        return new BundleManifest(
                1, "test-deployment", List.of(), null, Map.of(), Map.of());
    }

    private static void createCompositeIdMap(Connection connection, EntityType entity)
            throws Exception {
        execute(connection, "CREATE TEMP TABLE " + LegacyIdMapTables.temporaryTableName(entity)
                + " (legacy_guid uuid, club_id uuid, new_uuid uuid NOT NULL, "
                + "PRIMARY KEY (legacy_guid, club_id)) ON COMMIT DROP");
    }

    private static void createSingleKeyIdMap(Connection connection, EntityType entity)
            throws Exception {
        execute(connection, "CREATE TEMP TABLE " + LegacyIdMapTables.temporaryTableName(entity)
                + " (legacy_guid uuid PRIMARY KEY, new_uuid uuid NOT NULL) ON COMMIT DROP");
    }

    private static void seedComposite(
            Connection connection, EntityType entity, UUID legacyGuid, UUID clubId, UUID newUuid)
            throws Exception {
        try (var ps = connection.prepareStatement("INSERT INTO "
                + LegacyIdMapTables.temporaryTableName(entity)
                + " (legacy_guid, club_id, new_uuid) VALUES (?, ?, ?)")) {
            ps.setObject(1, legacyGuid);
            ps.setObject(2, clubId);
            ps.setObject(3, newUuid);
            ps.executeUpdate();
        }
    }

    private static void seedSingleKey(
            Connection connection, EntityType entity, UUID legacyGuid, UUID newUuid)
            throws Exception {
        try (var ps = connection.prepareStatement("INSERT INTO "
                + LegacyIdMapTables.temporaryTableName(entity)
                + " (legacy_guid, new_uuid) VALUES (?, ?)")) {
            ps.setObject(1, legacyGuid);
            ps.setObject(2, newUuid);
            ps.executeUpdate();
        }
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
