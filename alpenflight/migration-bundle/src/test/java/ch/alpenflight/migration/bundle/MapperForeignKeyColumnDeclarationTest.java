package ch.alpenflight.migration.bundle;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class MapperForeignKeyColumnDeclarationTest {

    private static final Map<String, Set<String>>
            KNOWN_UNDECLARED_AWAITING_ITS_OWN_MIGRATION_PROOF = Map.of(
                    "DELIVERY", new TreeSet<>(Set.of("club_id", "person_id")),
                    "DELIVERY_ITEM", new TreeSet<>(Set.of("club_id")),
                    "PERSON_FLIGHT_TIME_CREDIT_TRANSACTION",
                    new TreeSet<>(Set.of("person_flight_time_credit_id")));

    @Test
    void everyForeignKeyTheResolverSeeksIsAColumnTheMapperPutsOnTheWire() {
        assertThat(foreignKeysTheResolverSeeksButTheMapperNeverEmits(KnownMappers.all()))
                .as("ForeignKeyResolver rewrites a legacy guid only on a field the row "
                        + "carries. A target whose column is absent from wireColumns() is a "
                        + "silent no-op: the migrated row keeps the raw legacy identifier and "
                        + "the ingest either dangles the FK or writes an unresolvable id. "
                        + "Declare the real column in foreignKeyColumns(), or rename the wire "
                        + "field to the <target>_id convention. This assertion is an equality, "
                        + "not a subset: a mapper you repair must leave the pending map too")
                .isEqualTo(KNOWN_UNDECLARED_AWAITING_ITS_OWN_MIGRATION_PROOF);
    }

    @Test
    void aConventionFallbackTargetWhoseColumnIsAbsentFromTheWireScoresAViolation() {
        Mapper mapperNamingItsUserColumnActorUserId = new StubMapper(
                EntityType.AUDIT_LOG,
                new String[] {"legacy_guid", "actor_user_id"},
                List.of(EntityType.USER),
                List.of());

        assertThat(foreignKeysTheResolverSeeksButTheMapperNeverEmits(
                List.of(mapperNamingItsUserColumnActorUserId)))
                .containsExactly(Map.entry("AUDIT_LOG", new TreeSet<>(Set.of("user_id"))));
    }

    @Test
    void anExplicitlyDeclaredForeignKeyColumnAbsentFromTheWireScoresAViolation() {
        Mapper mapperDeclaringAColumnItNeverEmits = new StubMapper(
                EntityType.FLIGHT,
                new String[] {"legacy_guid", "aircraft_id"},
                List.of(EntityType.AIRCRAFT, EntityType.PERSON),
                List.of(
                        new ForeignKeyColumn("aircraft_id", EntityType.AIRCRAFT),
                        new ForeignKeyColumn("pilot_person_id", EntityType.PERSON)));

        assertThat(foreignKeysTheResolverSeeksButTheMapperNeverEmits(
                List.of(mapperDeclaringAColumnItNeverEmits)))
                .containsExactly(
                        Map.entry("FLIGHT", new TreeSet<>(Set.of("pilot_person_id"))));
    }

    @Test
    void aMapperWhoseColumnsAllReachTheWireScoresNoViolation() {
        Mapper mapperMixingDeclarationAndConvention = new StubMapper(
                EntityType.AIRCRAFT,
                new String[] {"legacy_guid", "managing_club_id", "country_id"},
                List.of(EntityType.CLUB, EntityType.COUNTRY),
                List.of(new ForeignKeyColumn("managing_club_id", EntityType.CLUB)));

        assertThat(foreignKeysTheResolverSeeksButTheMapperNeverEmits(
                List.of(mapperMixingDeclarationAndConvention)))
                .isEmpty();
    }

    private static Map<String, Set<String>> foreignKeysTheResolverSeeksButTheMapperNeverEmits(
            List<Mapper> mappers) {
        Map<String, Set<String>> byMapper = new TreeMap<>();
        for (Mapper mapper : mappers) {
            Set<String> wire = new TreeSet<>(Arrays.asList(mapper.wireColumns()));
            Set<String> phantom = new TreeSet<>();
            for (String sought : columnsTheResolverSeeks(mapper)) {
                if (!wire.contains(sought)) {
                    phantom.add(sought);
                }
            }
            if (!phantom.isEmpty()) {
                byMapper.put(mapper.entityType().name(), phantom);
            }
        }
        return byMapper;
    }

    private static Set<String> columnsTheResolverSeeks(Mapper mapper) {
        Set<String> sought = new LinkedHashSet<>();
        Set<EntityType> declaredTargets = EnumSet.noneOf(EntityType.class);
        for (ForeignKeyColumn declared : mapper.foreignKeyColumns()) {
            sought.add(declared.column());
            declaredTargets.add(declared.target());
        }
        for (EntityType target : mapper.foreignKeyTargets()) {
            if (!declaredTargets.contains(target)) {
                sought.add(target.name().toLowerCase(Locale.ROOT) + "_id");
            }
        }
        return sought;
    }

    private record StubMapper(
            EntityType entityType,
            String[] wire,
            List<EntityType> targets,
            List<ForeignKeyColumn> declaredColumns) implements Mapper {

        @Override
        public String[] wireColumns() {
            return wire.clone();
        }

        @Override
        public List<EntityType> foreignKeyTargets() {
            return targets;
        }

        @Override
        public List<ForeignKeyColumn> foreignKeyColumns() {
            return declaredColumns;
        }

        @Override
        public void writeNdjson(ResultSet source, JsonGenerator target) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void readEntity(JsonNode source, PreparedStatement target) {
            throw new UnsupportedOperationException();
        }
    }
}
