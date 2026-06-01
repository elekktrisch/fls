package ch.alpenflight.migration.bundle.flight;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.AbstractMapperContractTest;
import ch.alpenflight.migration.bundle.EntityType;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import net.datafaker.Faker;
import org.junit.jupiter.api.Test;

class InOutboundPointMapperTest extends AbstractMapperContractTest<InOutboundPointMapper> {

    private final InOutboundPointMapper mapper = new InOutboundPointMapper();

    @Override
    protected InOutboundPointMapper mapper() {
        return mapper;
    }

    @Override
    protected Map<String, Object> legacyRow(Faker faker) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("InOutboundPointId", randomUuidString(faker));
        // The parent Location legacy GUID — the producer fan-out keys the
        // parent (legacy_guid, club_id); the child carries only the parent
        // legacy GUID here and inherits tenancy through it (no own ClubId).
        row.put("LocationId", randomUuidString(faker));
        row.put("InOutboundPointName", "07N");
        row.put("IsInboundPoint", true);
        row.put("IsOutboundPoint", false);
        row.put("CreatedOn", Timestamp.from(Instant.parse("2024-01-01T12:00:00Z")));
        row.put("CreatedByUserId", randomUuidString(faker));
        row.put("ModifiedOn", Timestamp.from(Instant.parse("2024-02-01T12:00:00Z")));
        row.put("ModifiedByUserId", randomUuidString(faker));
        row.put("DeletedOn", Timestamp.from(Instant.parse("2024-03-01T12:00:00Z")));
        row.put("DeletedByUserId", randomUuidString(faker));
        return row;
    }

    @Test
    void exposesInOutboundPointEntityType() {
        assertThat(mapper.entityType()).isEqualTo(EntityType.INOUTBOUND_POINT);
    }

    @Test
    void declaresParentLocationAsTheOnlyForeignKey() {
        assertThat(mapper.foreignKeys())
                .as("InOutboundPoint inherits tenancy through its parent Location "
                        + "(no own club_id), so its only structural FK is the parent "
                        + "Location — which must precede INOUTBOUND_POINT in the topo "
                        + "order so the fanned-out parent replicas exist first")
                .containsExactly(EntityType.LOCATION);
    }

    @Test
    void carriesNoOwnClubColumn() {
        assertThat(mapper.columns())
                .as("Tenancy is inherited via location_id, not carried — a club_id "
                        + "column would break the child-of-fanned-out-parent invariant")
                .doesNotContain("club_id")
                .contains("location_id");
    }

    @Test
    void keysTheChildUnderItsParentLocationGuid() throws Exception {
        JsonNode emitted = invokeWriteNdjson(mapper, legacyRow(seededFaker()));
        // legacy_guid (the child's own id, aliased to t_inoutbound_point.id by
        // the ingest orchestrator) and location_id (the parent fan-out key)
        // are both present, so the FK-rewrite can resolve the parent replica.
        assertThat(emitted.has("legacy_guid")).isTrue();
        assertThat(emitted.has("location_id")).isTrue();
        assertThat(emitted.get("location_id").asText()).isNotBlank();
    }

    @Test
    void collapsesLegacyInboundOutboundBitsIntoTheDirectionToken() throws Exception {
        JsonNode inbound = invokeWriteNdjson(mapper, legacyRow(seededFaker()));
        assertThat(inbound.get("direction").asText()).isEqualTo("INBOUND");

        Map<String, Object> bothSet = legacyRow(seededFaker());
        bothSet.put("IsInboundPoint", true);
        bothSet.put("IsOutboundPoint", true);
        assertThat(invokeWriteNdjson(mapper, bothSet).get("direction").asText())
                .isEqualTo("INOUTBOUND");

        Map<String, Object> outboundOnly = legacyRow(seededFaker());
        outboundOnly.put("IsInboundPoint", false);
        outboundOnly.put("IsOutboundPoint", true);
        assertThat(invokeWriteNdjson(mapper, outboundOnly).get("direction").asText())
                .isEqualTo("OUTBOUND");
    }
}
