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

class ForeignKeyResolverColumnDeclarationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

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

        assertThat(row.get("managing_club_id").asText()).isEqualTo(MANAGING_CLUB_NEW.toString());
        assertThat(row.get("owner_club_id").asText()).isEqualTo(OWNER_CLUB_NEW.toString());
        assertThat(row.get("owner_person_id").asText()).isEqualTo(OWNER_PERSON_NEW.toString());
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
        UUID managingClubLegacy = UUID.randomUUID();
        UUID managingClubNew = UUID.randomUUID();
        UUID ownerClubLegacy = UUID.randomUUID();
        UUID ownerClubNew = UUID.randomUUID();
        UUID ownerPersonLegacy = UUID.randomUUID();
        UUID ownerPersonNew = UUID.randomUUID();
        UUID homebaseLegacy = UUID.randomUUID();
        UUID homebaseNewForManagingClub = UUID.randomUUID();

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
        assertThat(row.get("homebase_id").asText())
                .isEqualTo(homebaseNewForManagingClub.toString());
    }

    private static BundleManifest emptyManifest() {
        return new BundleManifest(1, "test", List.of(), null, Map.of(), Map.of());
    }

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

    private static Connection stubConnection(Map<EntityType, Map<UUID, UUID>> idMaps)
            throws SQLException {
        Connection connection = mock(Connection.class);
        for (var entry : idMaps.entrySet()) {
            String sql = "SELECT new_uuid FROM "
                    + LegacyIdMapTables.temporaryTableName(entry.getKey())
                    + " WHERE legacy_guid = ?";
            PreparedStatement ps = stubStatement(entry.getValue());
            when(connection.prepareStatement(eq(sql))).thenReturn(ps);
        }
        return connection;
    }

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

    private record CompositeKey(UUID legacyGuid, UUID clubId) {}

    private static PreparedStatement stubCompositeStatement(Map<CompositeKey, UUID> idMap)
            throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);

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
