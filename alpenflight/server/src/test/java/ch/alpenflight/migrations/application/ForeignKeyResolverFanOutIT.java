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

    /**
     * J-2 T-39 — the real-export catch. A FLIGHT's {@code start_type_id} carries
     * the synthetic {@code UUID(0, legacyAircraftStartType)}; the SYSTEM_GLOBAL
     * START_TYPE closure {@link StartTypeMapper#legacyEnumIdToSeedPk()} — the
     * exact map {@code BundleWriter.writeStartTypeEnumSeedPgcopy} ships — must
     * enumerate the FULL legacy enum (1..5). The real FLSTest had a SelfStart(3)
     * flight but the prior closure omitted value 3, so the resolver fail-closed
     * with {@code BUNDLE_CROSS_TENANT_FK_LEAK} at ingest. This drives the resolver
     * against real Postgres seeded from the enum-complete closure and asserts
     * EVERY enum value resolves, with SelfStart(3) the load-bearing case.
     */
    @Test
    void start_type_self_start_resolves_against_the_enum_complete_closure() throws Exception {
        Map<UUID, UUID> closure = StartTypeMapper.legacyEnumIdToSeedPk();

        try (Connection connection = txConnection()) {
            createSingleKeyIdMap(connection, EntityType.START_TYPE);
            for (Map.Entry<UUID, UUID> e : closure.entrySet()) {
                seedSingleKey(connection, EntityType.START_TYPE, e.getKey(), e.getValue());
            }

            try (ForeignKeyResolver resolver =
                    new ForeignKeyResolver(connection, startTypeManifest())) {
                // Every legacy AircraftStartType value (1..5) must resolve — not
                // just the ones the legacy StartTypes table happened to seed.
                for (int legacyId : List.of(1, 2, 3, 4, 5)) {
                    UUID synthetic = new UUID(0L, legacyId);
                    ObjectNode flight = JSON.createObjectNode();
                    flight.put("start_type_id", synthetic.toString());

                    resolver.rewriteForeignKeys(new FlightMapper(), flight);

                    assertThat(UUID.fromString(flight.get("start_type_id").asText()))
                            .as("legacy start type %d (UUID(0,%d)) resolves to its V2 "
                                    + "t_start_type seed PK", legacyId, legacyId)
                            .isEqualTo(closure.get(synthetic));
                }

                // SelfStart(3) — the exact value the real export 400'd on.
                ObjectNode selfStart = JSON.createObjectNode();
                selfStart.put("start_type_id", new UUID(0L, 3L).toString());
                resolver.rewriteForeignKeys(new FlightMapper(), selfStart);
                assertThat(UUID.fromString(selfStart.get("start_type_id").asText()))
                        .as("SelfStart(3) resolves (no BUNDLE_CROSS_TENANT_FK_LEAK)")
                        .isEqualTo(closure.get(new UUID(0L, 3L)));
            }
            connection.commit();
        }
    }

    @Test
    void start_type_missing_from_the_closure_fails_closed() throws Exception {
        // Regression direction: an INCOMPLETE START_TYPE map (the pre-fix bug —
        // SelfStart(3) absent) must still fail closed, NOT resolve to a wrong PK.
        // This pins that the BUNDLE_CROSS_TENANT_FK_LEAK guard is intact; the fix
        // makes the DATA complete, it does not weaken the guard.
        try (Connection connection = txConnection()) {
            createSingleKeyIdMap(connection, EntityType.START_TYPE);
            // Seed only 1/2/4/5 — deliberately omit SelfStart(3).
            Map<UUID, UUID> closure = StartTypeMapper.legacyEnumIdToSeedPk();
            for (int legacyId : List.of(1, 2, 4, 5)) {
                UUID synthetic = new UUID(0L, legacyId);
                seedSingleKey(connection, EntityType.START_TYPE, synthetic, closure.get(synthetic));
            }

            try (ForeignKeyResolver resolver =
                    new ForeignKeyResolver(connection, startTypeManifest())) {
                ObjectNode flight = JSON.createObjectNode();
                flight.put("start_type_id", new UUID(0L, 3L).toString());
                assertThatThrownBy(() ->
                        resolver.rewriteForeignKeys(new FlightMapper(), flight))
                        .isInstanceOf(BundleIngestException.class)
                        .extracting(e -> ((BundleIngestException) e).getErrorCode())
                        .isEqualTo(BundleIngestErrorCode.BUNDLE_CROSS_TENANT_FK_LEAK);
            }
            connection.commit();
        }
    }

    /** Manifest marking START_TYPE SYSTEM_GLOBAL_RESOLVE so the resolver
     * fail-closes on a missing map entry (the FK-leak guard). */
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
