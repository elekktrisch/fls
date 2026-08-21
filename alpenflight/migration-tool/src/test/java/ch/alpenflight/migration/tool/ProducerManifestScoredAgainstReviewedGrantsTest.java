package ch.alpenflight.migration.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.migration.bundle.EntityPolicy;
import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.ForeignKeyColumn;
import ch.alpenflight.migration.bundle.KnownMappers;
import ch.alpenflight.migration.bundle.Manifest;
import ch.alpenflight.migration.bundle.Mapper;
import ch.alpenflight.migration.bundle.ReferenceLookup;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProducerManifestScoredAgainstReviewedGrantsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final List<EntityType> REGISTERED = ExportCommand.registeredEntities();

    @Test
    void theProducerEmitsTheCrossTenantColumnsTheReviewedGrantNames() {
        Map<EntityType, EntityPolicy> policies =
                ManifestBuilder.entityPoliciesFor(REGISTERED, KnownMappers.all());

        assertThat(policies.get(EntityType.AIRCRAFT).tenantBypassFks())
                .containsExactlyInAnyOrder("aircraft_owner_person_id", "homebase_id");
        assertThat(policies.get(EntityType.FLIGHT).tenantBypassFks())
                .containsExactly("aircraft_id");
        assertThat(policies.get(EntityType.AIRCRAFT_RESERVATION).tenantBypassFks())
                .containsExactlyInAnyOrder(
                        "aircraft_id", "pilot_person_id", "second_crew_person_id");
        assertThat(policies.get(EntityType.USER).tenantBypassFks())
                .containsExactly("person_id");
    }

    @Test
    void everyRegisteredEntityOnTheReviewedGrantTableReachesTheCheckWithColumns() {
        Map<EntityType, EntityPolicy> policies =
                ManifestBuilder.entityPoliciesFor(REGISTERED, KnownMappers.all());

        List<EntityType> scored = REGISTERED.stream()
                .filter(Manifest.reviewedCrossTenantGrantsByEntity()::containsKey)
                .filter(entity -> !policies.get(entity).tenantBypassFks().isEmpty())
                .toList();

        assertThat(scored).containsExactlyInAnyOrder(
                EntityType.USER, EntityType.PERSON_CLUB, EntityType.AIRCRAFT,
                EntityType.AIRCRAFT_AIRCRAFT_STATE, EntityType.FLIGHT, EntityType.FLIGHT_CREW,
                EntityType.AIRCRAFT_RESERVATION, EntityType.PLANNING_DAY_ASSIGNMENT,
                EntityType.DELIVERY, EntityType.PERSON_FLIGHT_TIME_CREDIT);
    }

    @Test
    void theReviewedGrantAcceptsTheManifestTheRealProducerWouldWrite() {
        Map<EntityType, EntityPolicy> policies =
                ManifestBuilder.entityPoliciesFor(REGISTERED, KnownMappers.all());

        assertThatCode(() -> ManifestBuilder.scoreAgainstTheReviewedCrossTenantGrants(
                policies, unmappedReasonFor(policies)))
                .doesNotThrowAnyException();
    }

    @Test
    void theCrossTenantColumnsSurviveTheManifestJsonTheBundleActuallyCarries() throws Exception {
        Map<EntityType, EntityPolicy> policies =
                ManifestBuilder.entityPoliciesFor(REGISTERED, KnownMappers.all());

        Manifest readBackFromTheBundleBytes =
                reparseAsTheServerDoes(manifestBytesAsWrittenIntoTheBundle(policies));

        assertThat(readBackFromTheBundleBytes.entityPolicies()
                .get(EntityType.AIRCRAFT).tenantBypassFks())
                .containsExactlyInAnyOrder("aircraft_owner_person_id", "homebase_id");
    }

    @Test
    void aPlantedOffListColumnIsRejectedWhenTheManifestJsonIsReadBack() throws Exception {
        List<Mapper> tampered = registryWithCrossTenantColumnsReplaced(
                EntityType.AIRCRAFT,
                Set.of("aircraft_owner_person_id", "homebase_id", "backup_pilot_person_id"));
        byte[] manifestJson = manifestBytesAsWrittenIntoTheBundle(
                ManifestBuilder.entityPoliciesFor(REGISTERED, tampered));

        assertThatThrownBy(() -> reparseAsTheServerDoes(manifestJson))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("backup_pilot_person_id")
                .hasMessageContaining("AIRCRAFT");
    }

    private static Manifest reparseAsTheServerDoes(byte[] manifestJson) throws IOException {
        JsonNode tree = JSON.readTree(manifestJson);
        Map<EntityType, EntityPolicy> policies = JSON.convertValue(
                tree.get("entityPolicies"),
                JSON.getTypeFactory().constructMapType(
                        EnumMap.class, EntityType.class, EntityPolicy.class));
        Map<EntityType, String> unmapped = JSON.convertValue(
                tree.get("unmappedReason"),
                JSON.getTypeFactory().constructMapType(
                        EnumMap.class, EntityType.class, String.class));
        return new Manifest(tree.get("schemaVersion").asInt(), policies, unmapped);
    }

    private static byte[] manifestBytesAsWrittenIntoTheBundle(
            Map<EntityType, EntityPolicy> policies) throws IOException {
        return JSON.writeValueAsBytes(new ServerBundleManifestMirror(
                Manifest.CURRENT_SCHEMA_VERSION,
                "Producer Grant Scoring Deployment",
                List.of(new ServerBundleManifestMirror.ClubDeclaration(
                        UUID.fromString("00000000-0000-4000-8000-000000000001"),
                        "Producer Grant Scoring Club", "producer-grant-scoring-club", "PGSC",
                        false,
                        UUID.fromString("00000000-0000-4000-8000-0000000000c1"),
                        UUID.fromString("00000000-0000-4000-8000-0000000000c2"))),
                UUID.fromString("00000000-0000-4000-8000-000000000001"),
                policies,
                unmappedReasonFor(policies)));
    }

    @Test
    void aColumnTheGrantDoesNotNameOnAGrantedEntityIsRejected() {
        List<Mapper> tampered = registryWithCrossTenantColumnsReplaced(
                EntityType.AIRCRAFT,
                Set.of("aircraft_owner_person_id", "homebase_id", "backup_pilot_person_id"));

        Map<EntityType, EntityPolicy> policies =
                ManifestBuilder.entityPoliciesFor(REGISTERED, tampered);

        assertThatThrownBy(() -> ManifestBuilder.scoreAgainstTheReviewedCrossTenantGrants(
                policies, unmappedReasonFor(policies)))
                .isInstanceOf(ExportException.class)
                .hasMessageContaining("backup_pilot_person_id")
                .hasMessageContaining("AIRCRAFT");
    }

    @Test
    void aBypassColumnOnAnEntityWithNoGrantAtAllIsRejected() {
        List<Mapper> tampered = registryWithCrossTenantColumnsReplaced(
                EntityType.AIRCRAFT_OPERATING_COUNTER, Set.of("aircraft_id"));

        Map<EntityType, EntityPolicy> policies =
                ManifestBuilder.entityPoliciesFor(REGISTERED, tampered);

        assertThatThrownBy(() -> ManifestBuilder.scoreAgainstTheReviewedCrossTenantGrants(
                policies, unmappedReasonFor(policies)))
                .isInstanceOf(ExportException.class)
                .hasMessageContaining("AIRCRAFT_OPERATING_COUNTER")
                .hasMessageContaining("not on the cross-tenant allow-list");
    }

    @Test
    void aGrantedEntityThatDeclaresNothingIsRejectedInsteadOfSilentlyPassing() {
        List<Mapper> silenced = registryWithCrossTenantColumnsReplaced(
                EntityType.FLIGHT, Set.of());

        assertThatThrownBy(() -> ManifestBuilder.entityPoliciesFor(REGISTERED, silenced))
                .isInstanceOf(ExportException.class)
                .hasMessageContaining("FLIGHT")
                .hasMessageContaining("unverifiable")
                .hasMessageContaining("Residual limit");
    }

    private static List<Mapper> registryWithCrossTenantColumnsReplaced(
            EntityType entity, Set<String> columns) {
        List<Mapper> registry = new ArrayList<>();
        for (Mapper mapper : KnownMappers.all()) {
            registry.add(mapper.entityType() == entity
                    ? new CrossTenantColumnsOverride(mapper, columns)
                    : mapper);
        }
        return registry;
    }

    private static Map<EntityType, String> unmappedReasonFor(
            Map<EntityType, EntityPolicy> policies) {
        Map<EntityType, String> unmapped = new EnumMap<>(EntityType.class);
        for (EntityType type : EntityType.values()) {
            if (!policies.containsKey(type)) {
                unmapped.put(type, "NOT_REGISTERED_BY_THE_EXPORTER");
            }
        }
        return unmapped;
    }

    private record CrossTenantColumnsOverride(Mapper delegate, Set<String> columns)
            implements Mapper {

        @Override
        public EntityType entityType() {
            return delegate.entityType();
        }

        @Override
        public String[] wireColumns() {
            return delegate.wireColumns();
        }

        @Override
        public List<EntityType> foreignKeyTargets() {
            return delegate.foreignKeyTargets();
        }

        @Override
        public List<ForeignKeyColumn> foreignKeyColumns() {
            return delegate.foreignKeyColumns();
        }

        @Override
        public List<ReferenceLookup> referenceLookups() {
            return delegate.referenceLookups();
        }

        @Override
        public List<String> deferredSelfFkColumns() {
            return delegate.deferredSelfFkColumns();
        }

        @Override
        public Set<String> crossTenantForeignKeyColumns() {
            return columns;
        }

        @Override
        public void writeNdjson(ResultSet source, JsonGenerator target)
                throws SQLException, IOException {
            delegate.writeNdjson(source, target);
        }

        @Override
        public void readEntity(JsonNode source, PreparedStatement target) throws SQLException {
            delegate.readEntity(source, target);
        }
    }
}
