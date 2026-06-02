package ch.alpenflight.migrations.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.ForeignKeyColumn;
import ch.alpenflight.migration.bundle.LegacyIdMapTables;
import ch.alpenflight.migration.bundle.Mapper;
import ch.alpenflight.migration.bundle.flight.AircraftMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * S-187a / J-1 T-05a — resolver contract generalization. Drives
 * {@link ForeignKeyResolver#rewriteForeignKeys} over a synthetic mapper that
 * declares non-canonical FK columns via {@link Mapper#foreignKeyColumns()},
 * proving:
 *
 * <ul>
 *   <li>two columns mapped to ONE target both resolve (CLUB ← managing_club_id
 *       + owner_club_id) — the AIRCRAFT shape T-05b lands;</li>
 *   <li>a declared non-canonical column resolves (PERSON ← owner_person_id);</li>
 *   <li>a target NOT in the declaration still resolves via the
 *       {@code <target>_id} convention fallback (COUNTRY ← country_id), i.e. the
 *       shipped Location resolution path is untouched.</li>
 * </ul>
 *
 * <p>Pure unit test: the {@code legacy_id_map_<entity>} lookups are mocked, so
 * no Postgres is needed — the resolver's SQL contract is asserted by the
 * stubbed {@code SELECT new_uuid … WHERE legacy_guid = ?} round-trips.
 */
class ForeignKeyResolverColumnDeclarationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // Legacy GUIDs on the wire + the new-stack UUIDs the id-map resolves them to.
    private static final UUID MANAGING_CLUB_LEGACY = UUID.randomUUID();
    private static final UUID MANAGING_CLUB_NEW = UUID.randomUUID();
    private static final UUID OWNER_CLUB_LEGACY = UUID.randomUUID();
    private static final UUID OWNER_CLUB_NEW = UUID.randomUUID();
    private static final UUID OWNER_PERSON_LEGACY = UUID.randomUUID();
    private static final UUID OWNER_PERSON_NEW = UUID.randomUUID();
    private static final UUID COUNTRY_LEGACY = UUID.randomUUID();
    private static final UUID COUNTRY_NEW = UUID.randomUUID();

    @Test
    void resolves_two_declared_columns_to_one_target_plus_convention_fallback()
            throws SQLException {
        Map<UUID, UUID> clubMap = Map.of(
                MANAGING_CLUB_LEGACY, MANAGING_CLUB_NEW,
                OWNER_CLUB_LEGACY, OWNER_CLUB_NEW);
        Map<UUID, UUID> personMap = Map.of(OWNER_PERSON_LEGACY, OWNER_PERSON_NEW);
        Map<UUID, UUID> countryMap = Map.of(COUNTRY_LEGACY, COUNTRY_NEW);

        Connection connection = stubConnection(Map.of(
                EntityType.CLUB, clubMap,
                EntityType.PERSON, personMap,
                EntityType.COUNTRY, countryMap));

        Mapper mapper = aircraftLikeMapper();
        ObjectNode row = JSON.createObjectNode();
        row.put("managing_club_id", MANAGING_CLUB_LEGACY.toString());
        row.put("owner_club_id", OWNER_CLUB_LEGACY.toString());
        row.put("owner_person_id", OWNER_PERSON_LEGACY.toString());
        row.put("country_id", COUNTRY_LEGACY.toString());

        try (ForeignKeyResolver resolver = new ForeignKeyResolver(connection, emptyManifest())) {
            resolver.rewriteForeignKeys(mapper, row);
        }

        // Two non-canonical columns → one target both resolve.
        assertThat(row.get("managing_club_id").asText()).isEqualTo(MANAGING_CLUB_NEW.toString());
        assertThat(row.get("owner_club_id").asText()).isEqualTo(OWNER_CLUB_NEW.toString());
        // A declared non-canonical column to a distinct target resolves.
        assertThat(row.get("owner_person_id").asText()).isEqualTo(OWNER_PERSON_NEW.toString());
        // An undeclared target falls back to the <target>_id convention.
        assertThat(row.get("country_id").asText()).isEqualTo(COUNTRY_NEW.toString());
    }

    @Test
    void convention_only_mapper_is_unaffected() throws SQLException {
        Map<UUID, UUID> countryMap = Map.of(COUNTRY_LEGACY, COUNTRY_NEW);
        Connection connection = stubConnection(Map.of(EntityType.COUNTRY, countryMap));

        Mapper mapper = conventionOnlyMapper();
        ObjectNode row = JSON.createObjectNode();
        row.put("country_id", COUNTRY_LEGACY.toString());

        try (ForeignKeyResolver resolver = new ForeignKeyResolver(connection, emptyManifest())) {
            resolver.rewriteForeignKeys(mapper, row);
        }

        assertThat(row.get("country_id").asText()).isEqualTo(COUNTRY_NEW.toString());
    }

    @Test
    void real_aircraft_mapper_resolves_all_four_columns_including_fan_out_homebase()
            throws SQLException {
        // Legacy GUIDs the AIRCRAFT row carries on the wire + their resolutions.
        UUID managingClubLegacy = UUID.randomUUID();
        UUID managingClubNew = UUID.randomUUID();
        UUID ownerClubLegacy = UUID.randomUUID();
        UUID ownerClubNew = UUID.randomUUID();
        UUID ownerPersonLegacy = UUID.randomUUID();
        UUID ownerPersonNew = UUID.randomUUID();
        UUID homebaseLegacy = UUID.randomUUID();
        UUID homebaseNewForManagingClub = UUID.randomUUID();

        // CLUB + PERSON resolve via the single-key id-map; LOCATION fans out, so
        // homebase resolves via the composite (legacy_guid, club_id) lookup keyed on
        // the aircraft's OWN managing_club_id (the declared disambiguator) — NOT the
        // resolver's default "club_id" referencer field, which the row never carries.
        // The composite legacy_id_map_LOCATION is keyed by the LEGACY club guid (the
        // fan-out producer writes (legacy_guid, legacy club_id, new_uuid)), so the
        // disambiguator must use managing_club_id's PRE-REWRITE legacy value even
        // though it is ALSO a CLUB FK rewritten earlier in the same pass.
        Connection connection = stubConnection(
                Map.of(
                        EntityType.CLUB,
                        Map.of(
                                managingClubLegacy, managingClubNew,
                                ownerClubLegacy, ownerClubNew),
                        EntityType.PERSON, Map.of(ownerPersonLegacy, ownerPersonNew)),
                Map.of(
                        EntityType.LOCATION,
                        Map.of(
                                new CompositeKey(homebaseLegacy, managingClubLegacy),
                                homebaseNewForManagingClub)));

        // Wire column names (package-private constants on AircraftMapper; the
        // mapper's own unit test asserts the constants equal these literals).
        ObjectNode row = JSON.createObjectNode();
        row.put("managing_club_id", managingClubLegacy.toString());
        row.put("owner_club_id", ownerClubLegacy.toString());
        row.put("aircraft_owner_person_id", ownerPersonLegacy.toString());
        row.put("homebase_id", homebaseLegacy.toString());

        try (ForeignKeyResolver resolver = new ForeignKeyResolver(connection, emptyManifest())) {
            resolver.rewriteForeignKeys(new AircraftMapper(), row);
        }

        assertThat(row.get("managing_club_id").asText()).isEqualTo(managingClubNew.toString());
        assertThat(row.get("owner_club_id").asText()).isEqualTo(ownerClubNew.toString());
        assertThat(row.get("aircraft_owner_person_id").asText())
                .isEqualTo(ownerPersonNew.toString());
        // Fan-out homebase resolved against the managing_club_id disambiguator's
        // PRE-REWRITE legacy value (the composite map is legacy-keyed), proving
        // managing_club_id is read as both a CLUB FK (rewritten to the new id) and,
        // via the snapshot, the legacy homebase replica selector.
        assertThat(row.get("homebase_id").asText())
                .isEqualTo(homebaseNewForManagingClub.toString());
    }

    private static BundleManifest emptyManifest() {
        return new BundleManifest(1, "test", List.of(), null, Map.of(), Map.of());
    }

    /** AIRCRAFT-like: two CLUB columns + a PERSON column declared; COUNTRY by convention. */
    private static Mapper aircraftLikeMapper() {
        return new Mapper() {
            @Override
            public EntityType entityType() {
                return EntityType.AIRCRAFT;
            }

            @Override
            public String[] columns() {
                return new String[] {"managing_club_id", "owner_club_id", "owner_person_id",
                        "country_id"};
            }

            @Override
            public List<EntityType> foreignKeys() {
                return List.of(EntityType.CLUB, EntityType.PERSON, EntityType.COUNTRY);
            }

            @Override
            public List<ForeignKeyColumn> foreignKeyColumns() {
                return List.of(
                        new ForeignKeyColumn("managing_club_id", EntityType.CLUB),
                        new ForeignKeyColumn("owner_club_id", EntityType.CLUB),
                        new ForeignKeyColumn("owner_person_id", EntityType.PERSON));
            }

            @Override
            public void writeNdjson(ResultSet source, com.fasterxml.jackson.core.JsonGenerator t) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void readEntity(JsonNode source, PreparedStatement target) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static Mapper conventionOnlyMapper() {
        return new Mapper() {
            @Override
            public EntityType entityType() {
                return EntityType.LOCATION;
            }

            @Override
            public String[] columns() {
                return new String[] {"country_id"};
            }

            @Override
            public List<EntityType> foreignKeys() {
                return List.of(EntityType.COUNTRY);
            }

            @Override
            public void writeNdjson(ResultSet source, com.fasterxml.jackson.core.JsonGenerator t) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void readEntity(JsonNode source, PreparedStatement target) {
                throw new UnsupportedOperationException();
            }
        };
    }

    /**
     * A {@link Connection} that answers {@code SELECT new_uuid FROM
     * legacy_id_map_<entity> WHERE legacy_guid = ?} from the supplied per-target
     * legacy→new id maps. One {@link PreparedStatement} per target SQL, matching
     * the resolver's per-target statement caching.
     */
    private static Connection stubConnection(Map<EntityType, Map<UUID, UUID>> idMaps)
            throws SQLException {
        Connection connection = mock(Connection.class);
        for (var entry : idMaps.entrySet()) {
            String sql = "SELECT new_uuid FROM "
                    + LegacyIdMapTables.temporaryTableName(entry.getKey())
                    + " WHERE legacy_guid = ?";
            // Build the statement stub fully BEFORE the prepareStatement stubbing
            // begins — nesting it inside thenReturn(...) trips Mockito's
            // "stubbing inside another stub" guard.
            PreparedStatement ps = stubStatement(entry.getValue());
            when(connection.prepareStatement(eq(sql))).thenReturn(ps);
        }
        return connection;
    }

    /**
     * As {@link #stubConnection(Map)} but additionally answers the composite
     * fan-out SQL ({@code … WHERE legacy_guid = ? AND club_id = ?}) for each
     * supplied target — the path a {@link EntityType#fansOut()} FK (LOCATION)
     * takes.
     */
    private static Connection stubConnection(
            Map<EntityType, Map<UUID, UUID>> singleKeyMaps,
            Map<EntityType, Map<CompositeKey, UUID>> compositeMaps)
            throws SQLException {
        Connection connection = stubConnection(singleKeyMaps);
        for (var entry : compositeMaps.entrySet()) {
            String sql = "SELECT new_uuid FROM "
                    + LegacyIdMapTables.temporaryTableName(entry.getKey())
                    + " WHERE legacy_guid = ? AND club_id = ?";
            PreparedStatement ps = stubCompositeStatement(entry.getValue());
            when(connection.prepareStatement(eq(sql))).thenReturn(ps);
        }
        return connection;
    }

    /** (legacy_guid, club_id) composite key for the fan-out lookup stub. */
    private record CompositeKey(UUID legacyGuid, UUID clubId) {}

    private static PreparedStatement stubCompositeStatement(Map<CompositeKey, UUID> idMap)
            throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);

        // Mutable per-execution state: bound (legacy_guid @1, club_id @2) + cursor.
        UUID[] bound = new UUID[2];
        boolean[] cursorConsumed = {false};

        org.mockito.Mockito.doAnswer(invocation -> {
            int index = invocation.getArgument(0);
            if (index == 1 || index == 2) {
                bound[index - 1] = invocation.getArgument(1);
            }
            return null;
        }).when(ps).setObject(anyInt(), org.mockito.ArgumentMatchers.any());

        when(ps.executeQuery()).thenAnswer(invocation -> {
            cursorConsumed[0] = false;
            return rs;
        });
        when(rs.next()).thenAnswer(invocation -> {
            CompositeKey key = new CompositeKey(bound[0], bound[1]);
            boolean hasRow = idMap.containsKey(key) && !cursorConsumed[0];
            cursorConsumed[0] = true;
            return hasRow;
        });
        when(rs.getObject(1, UUID.class))
                .thenAnswer(invocation -> idMap.get(new CompositeKey(bound[0], bound[1])));
        return ps;
    }

    private static PreparedStatement stubStatement(Map<UUID, UUID> idMap) throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);

        // Mutable per-execution state: the bound legacy_guid and a one-row cursor.
        UUID[] boundLegacy = new UUID[1];
        boolean[] cursorConsumed = {false};

        org.mockito.Mockito.doAnswer(invocation -> {
            int index = invocation.getArgument(0);
            if (index == 1) {
                boundLegacy[0] = invocation.getArgument(1);
            }
            return null;
        }).when(ps).setObject(anyInt(), org.mockito.ArgumentMatchers.any());

        when(ps.executeQuery()).thenAnswer(invocation -> {
            cursorConsumed[0] = false;
            return rs;
        });
        when(rs.next()).thenAnswer(invocation -> {
            boolean hasRow = idMap.containsKey(boundLegacy[0]) && !cursorConsumed[0];
            cursorConsumed[0] = true;
            return hasRow;
        });
        when(rs.getObject(1, UUID.class)).thenAnswer(invocation -> idMap.get(boundLegacy[0]));
        return ps;
    }
}
