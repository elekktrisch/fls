package ch.alpenflight.migrations.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.ForeignKeyColumn;
import ch.alpenflight.migration.bundle.LegacyIdMapTables;
import ch.alpenflight.migration.bundle.Mapper;
import ch.alpenflight.migration.bundle.flight.AircraftMapper;
import ch.alpenflight.migration.bundle.identity.AuditLogMapper;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
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

        assertThat(row.get("managing_club_id").asText())
                .as("two declared non-canonical columns pointing at ONE target both resolve")
                .isEqualTo(MANAGING_CLUB_NEW.toString());
        assertThat(row.get("owner_club_id").asText())
                .isEqualTo(OWNER_CLUB_NEW.toString());
        assertThat(row.get("owner_person_id").asText())
                .as("a declared non-canonical column to a distinct target resolves")
                .isEqualTo(OWNER_PERSON_NEW.toString());
        assertThat(row.get("country_id").asText())
                .as("a target absent from the declaration still resolves via the "
                        + "<target>_id convention fallback")
                .isEqualTo(COUNTRY_NEW.toString());
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
                .as("the fan-out homebase resolves through the composite "
                        + "(legacy_guid, club_id) map keyed on managing_club_id's PRE-REWRITE "
                        + "legacy value, even though the same pass also rewrites "
                        + "managing_club_id itself to the new club id")
                .isEqualTo(homebaseNewForManagingClub.toString());
    }

    @Test
    void real_audit_log_mapper_resolves_the_actor_user_id_its_own_writeNdjson_emits()
            throws Exception {
        UUID legacyActorUserId = UUID.randomUUID();
        UUID migratedActorUserId = UUID.randomUUID();

        Connection connection = stubConnection(Map.of(
                EntityType.USER, Map.of(legacyActorUserId, migratedActorUserId)));

        ObjectNode row = auditLogRowAsTheProducerWritesIt(legacyActorUserId);
        assertThat(row.has("actor_user_id"))
                .as("the wire field name comes from AuditLogMapper.writeNdjson, not from this "
                        + "test — a synthetic row could alias it and hide the defect")
                .isTrue();
        assertThat(row.has("user_id"))
                .as("the mapper emits no conventional user_id, so the resolver's "
                        + "<target>_id fallback has nothing to rewrite")
                .isFalse();

        try (ForeignKeyResolver resolver = new ForeignKeyResolver(connection, emptyManifest())) {
            resolver.rewriteForeignKeys(new AuditLogMapper(), row);
        }

        assertThat(row.get("actor_user_id").asText())
                .as("AuditLogMapper must declare actor_user_id in foreignKeyColumns(); without "
                        + "the declaration the resolver seeks user_id, finds nothing, and the "
                        + "migrated audit row keeps the raw legacy actor guid %s that "
                        + "/system/logs then renders in place of a user name", legacyActorUserId)
                .isEqualTo(migratedActorUserId.toString());
    }

    private static ObjectNode auditLogRowAsTheProducerWritesIt(UUID legacyActorUserId)
            throws Exception {
        Map<String, Object> legacyCursorRow = Map.of(
                "LegacyGuid", UUID.randomUUID().toString(),
                "EventDateUTC", Timestamp.from(Instant.parse("2024-06-15T08:30:00Z")),
                "ResolvedActorUserId", legacyActorUserId.toString(),
                "ResolvedAction", "UPDATE",
                "ResolvedTargetEntityType", "Flight",
                "ResolvedTargetEntityId", UUID.randomUUID().toString(),
                "UserName", "j.doe",
                "AuditLogId", 1_234_567L,
                "ResolvedLegacyTargetRecordId", "42");

        ResultSet legacyCursor = mock(ResultSet.class);
        org.mockito.Mockito.lenient().when(legacyCursor.getString(anyString()))
                .thenAnswer(call -> {
                    Object value = legacyCursorRow.get(call.<String>getArgument(0));
                    return value == null ? null : value.toString();
                });
        org.mockito.Mockito.lenient().when(legacyCursor.getTimestamp(anyString()))
                .thenAnswer(call -> legacyCursorRow.get(call.<String>getArgument(0)));
        org.mockito.Mockito.lenient().when(legacyCursor.getLong(anyString()))
                .thenAnswer(call -> {
                    Object value = legacyCursorRow.get(call.<String>getArgument(0));
                    return value instanceof Number number ? number.longValue() : 0L;
                });

        ByteArrayOutputStream ndjson = new ByteArrayOutputStream();
        try (JsonGenerator generator = JSON.getFactory().createGenerator(ndjson)) {
            new AuditLogMapper().writeNdjson(legacyCursor, generator);
        }
        return (ObjectNode) JSON.readTree(ndjson.toByteArray());
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
            public String[] wireColumns() {
                return new String[] {"managing_club_id", "owner_club_id", "owner_person_id",
                        "country_id"};
            }

            @Override
            public List<EntityType> foreignKeyTargets() {
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
            public String[] wireColumns() {
                return new String[] {"country_id"};
            }

            @Override
            public List<EntityType> foreignKeyTargets() {
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
            PreparedStatement statementStubBuiltBeforeTheEnclosingWhen =
                    stubStatement(entry.getValue());
            when(connection.prepareStatement(eq(sql)))
                    .thenReturn(statementStubBuiltBeforeTheEnclosingWhen);
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
            PreparedStatement statementStubBuiltBeforeTheEnclosingWhen =
                    stubCompositeStatement(entry.getValue());
            when(connection.prepareStatement(eq(sql)))
                    .thenReturn(statementStubBuiltBeforeTheEnclosingWhen);
        }
        return connection;
    }

    private record CompositeKey(UUID legacyGuid, UUID clubId) {}

    private static PreparedStatement stubCompositeStatement(Map<CompositeKey, UUID> idMap)
            throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);

        UUID[] boundLegacyGuidAndClubId = new UUID[2];
        boolean[] cursorConsumed = {false};

        org.mockito.Mockito.doAnswer(invocation -> {
            int index = invocation.getArgument(0);
            if (index == 1 || index == 2) {
                boundLegacyGuidAndClubId[index - 1] = invocation.getArgument(1);
            }
            return null;
        }).when(ps).setObject(anyInt(), org.mockito.ArgumentMatchers.any());

        when(ps.executeQuery()).thenAnswer(invocation -> {
            cursorConsumed[0] = false;
            return rs;
        });
        when(rs.next()).thenAnswer(invocation -> {
            CompositeKey key =
                    new CompositeKey(boundLegacyGuidAndClubId[0], boundLegacyGuidAndClubId[1]);
            boolean hasRow = idMap.containsKey(key) && !cursorConsumed[0];
            cursorConsumed[0] = true;
            return hasRow;
        });
        when(rs.getObject(1, UUID.class))
                .thenAnswer(invocation -> idMap.get(new CompositeKey(
                        boundLegacyGuidAndClubId[0], boundLegacyGuidAndClubId[1])));
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
