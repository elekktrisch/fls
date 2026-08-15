package ch.alpenflight.migrations.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.migration.bundle.EntityPolicy;
import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.LegacyIdMapTables;
import ch.alpenflight.migration.bundle.flight.FlightMapper;
import ch.alpenflight.migration.bundle.flight.InOutboundPointMapper;
import ch.alpenflight.migration.bundle.flight.LocationMapper;
import ch.alpenflight.migration.bundle.flight.StartTypeMapper;
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

@Tag("slow")
class ForeignKeyResolverFanOutIT extends PostgresIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final int LEGACY_SELF_START_ENUM_ID = 3;
    private static final List<Integer> EVERY_LEGACY_AIRCRAFT_START_TYPE_ENUM_ID =
            List.of(1, 2, 3, 4, 5);
    private static final List<Integer> EVERY_LEGACY_START_TYPE_EXCEPT_SELF_START =
            List.of(1, 2, 4, 5);

    @Autowired DataSource dataSource;

    @Test
    void fanout_fk_target_resolves_per_club_replica() throws Exception {
        UUID sharedLocationGuid = UUID.randomUUID();
        UUID clubA = UUID.randomUUID();
        UUID clubB = UUID.randomUUID();
        UUID replicaA = UUID.randomUUID();
        UUID replicaB = UUID.randomUUID();

        try (Connection connection = manualCommitConnectionSoTempTablesDropOnCommit()) {
            createCompositeIdMap(connection, EntityType.LOCATION);
            seedComposite(connection, EntityType.LOCATION, sharedLocationGuid, clubA, replicaA);
            seedComposite(connection, EntityType.LOCATION, sharedLocationGuid, clubB, replicaB);

            try (ForeignKeyResolver resolver = new ForeignKeyResolver(connection, manifest())) {
                ObjectNode childA = iopRow(sharedLocationGuid, clubA);
                resolver.rewriteForeignKeys(new InOutboundPointMapper(), childA);
                assertThat(UUID.fromString(childA.get("location_id").asText()))
                        .as("club A's referencer lands on club A's replica of the shared "
                                + "legacy Location, not club B's")
                        .isEqualTo(replicaA);

                ObjectNode childB = iopRow(sharedLocationGuid, clubB);
                resolver.rewriteForeignKeys(new InOutboundPointMapper(), childB);
                assertThat(UUID.fromString(childB.get("location_id").asText()))
                        .as("a different club's referencer lands on a DIFFERENT replica")
                        .isEqualTo(replicaB);
            }
            connection.commit();
        }
    }

    @Test
    void composite_miss_fails_closed() throws Exception {
        UUID sharedLocationGuid = UUID.randomUUID();
        UUID provisionedClub = UUID.randomUUID();
        UUID unprovisionedClub = UUID.randomUUID();

        try (Connection connection = manualCommitConnectionSoTempTablesDropOnCommit()) {
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
        UUID legacyClub = UUID.randomUUID();
        UUID newClub = UUID.randomUUID();
        UUID legacyCountry = UUID.randomUUID();
        UUID newCountry = UUID.randomUUID();

        try (Connection connection = manualCommitConnectionSoTempTablesDropOnCommit()) {
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

    @Test
    void start_type_self_start_resolves_against_the_enum_complete_closure() throws Exception {
        Map<UUID, UUID> closure = StartTypeMapper.legacyEnumIdToSeedPk();

        try (Connection connection = manualCommitConnectionSoTempTablesDropOnCommit()) {
            createSingleKeyIdMap(connection, EntityType.START_TYPE);
            for (Map.Entry<UUID, UUID> e : closure.entrySet()) {
                seedSingleKey(connection, EntityType.START_TYPE, e.getKey(), e.getValue());
            }

            try (ForeignKeyResolver resolver =
                    new ForeignKeyResolver(connection, startTypeManifest())) {
                for (int legacyId : EVERY_LEGACY_AIRCRAFT_START_TYPE_ENUM_ID) {
                    UUID synthetic = new UUID(0L, legacyId);
                    ObjectNode flight = JSON.createObjectNode();
                    flight.put("start_type_id", synthetic.toString());

                    resolver.rewriteForeignKeys(new FlightMapper(), flight);

                    assertThat(UUID.fromString(flight.get("start_type_id").asText()))
                            .as("legacy start type %d (UUID(0,%d)) resolves to its V2 "
                                    + "t_start_type seed PK", legacyId, legacyId)
                            .isEqualTo(closure.get(synthetic));
                }

                UUID syntheticSelfStart = new UUID(0L, LEGACY_SELF_START_ENUM_ID);
                ObjectNode selfStart = JSON.createObjectNode();
                selfStart.put("start_type_id", syntheticSelfStart.toString());
                resolver.rewriteForeignKeys(new FlightMapper(), selfStart);
                assertThat(UUID.fromString(selfStart.get("start_type_id").asText()))
                        .as("SelfStart(3), the value the real export 400'd on, resolves "
                                + "(no BUNDLE_CROSS_TENANT_FK_LEAK)")
                        .isEqualTo(closure.get(syntheticSelfStart));
            }
            connection.commit();
        }
    }

    @Test
    void start_type_missing_from_the_closure_fails_closed() throws Exception {
        try (Connection connection = manualCommitConnectionSoTempTablesDropOnCommit()) {
            createSingleKeyIdMap(connection, EntityType.START_TYPE);
            Map<UUID, UUID> closure = StartTypeMapper.legacyEnumIdToSeedPk();
            for (int legacyId : EVERY_LEGACY_START_TYPE_EXCEPT_SELF_START) {
                UUID synthetic = new UUID(0L, legacyId);
                seedSingleKey(connection, EntityType.START_TYPE, synthetic, closure.get(synthetic));
            }

            try (ForeignKeyResolver resolver =
                    new ForeignKeyResolver(connection, startTypeManifest())) {
                ObjectNode flight = JSON.createObjectNode();
                flight.put("start_type_id", new UUID(0L, LEGACY_SELF_START_ENUM_ID).toString());
                assertThatThrownBy(() ->
                        resolver.rewriteForeignKeys(new FlightMapper(), flight))
                        .as("an incomplete START_TYPE map still fails closed rather than "
                                + "resolving SelfStart to a wrong PK")
                        .isInstanceOf(BundleIngestException.class)
                        .extracting(e -> ((BundleIngestException) e).getErrorCode())
                        .isEqualTo(BundleIngestErrorCode.BUNDLE_CROSS_TENANT_FK_LEAK);
            }
            connection.commit();
        }
    }

    private static BundleManifest startTypeManifest() {
        EntityPolicy systemGlobal = new EntityPolicy(
                EntityPolicy.PortPolicy.SYSTEM_GLOBAL_RESOLVE,
                EntityPolicy.TombstonePolicy.SKIP_DELETED,
                java.util.Set.of(),
                java.util.List.of());
        return new BundleManifest(
                1, "test-deployment", List.of(), null,
                Map.of(EntityType.START_TYPE, systemGlobal), Map.of());
    }

    private Connection manualCommitConnectionSoTempTablesDropOnCommit() throws Exception {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        return connection;
    }

    private static ObjectNode iopRow(UUID legacyLocationId, UUID childOwnLegacyClub) {
        ObjectNode row = JSON.createObjectNode();
        row.put("location_id", legacyLocationId.toString());
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
