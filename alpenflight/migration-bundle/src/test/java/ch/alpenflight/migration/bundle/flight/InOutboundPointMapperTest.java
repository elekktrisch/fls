package ch.alpenflight.migration.bundle.flight;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.AbstractMapperContractTest;
import ch.alpenflight.migration.bundle.Coercions;
import ch.alpenflight.migration.bundle.EntityType;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
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
        row.put("LocationId", randomUuidString(faker));
        row.put("ClubId", randomUuidString(faker));
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
    void carriesNoOwnClubColumnEvenThoughTheWireRowDoes() {
        assertThat(mapper.columns())
                .as("club_id is a RESOLVER-ONLY wire field, not a destination column — "
                        + "t_inoutbound_point has no club_id (tenancy inherited via "
                        + "location_id), so it must be absent from columns() while still "
                        + "being emitted on the wire for T-07's composite FK lookup")
                .doesNotContain("club_id")
                .contains("location_id");
    }

    @Test
    void emitsDerivedIdSharedParentGuidAndResolverOnlyClubForAFannedOutChild()
            throws Exception {
        Faker faker = seededFaker();
        Map<String, Object> row = legacyRow(faker);
        UUID iopId = UUID.fromString(row.get("InOutboundPointId").toString());
        UUID locationId = UUID.fromString(row.get("LocationId").toString());
        UUID clubId = UUID.fromString(row.get("ClubId").toString());

        JsonNode emitted = invokeWriteNdjson(mapper, row);

        assertThat(emitted.get("id").asText())
                .as("fan-out child id = deriveFanOutId(IopId, ClubId) — distinct per "
                        + "replica, mirroring the parent Location fan-out")
                .isEqualTo(Coercions.deriveFanOutId(iopId, clubId).toString());
        assertThat(emitted.get("legacy_guid").asText())
                .as("legacy_guid stays the shared legacy IOP id across every replica")
                .isEqualTo(iopId.toString());
        assertThat(emitted.get("location_id").asText())
                .as("location_id stays the shared legacy parent LocationId — T-07 "
                        + "resolves it composite via the wire club_id")
                .isEqualTo(locationId.toString());
        assertThat(emitted.get("club_id").asText())
                .as("club_id is the child's OWN legacy club id, carried on the wire as a "
                        + "resolver-only field (not a destination column)")
                .isEqualTo(clubId.toString());
    }

    @Test
    void twoClubsSharingOneParentLocationYieldTwoChildRowsWithDistinctIds()
            throws Exception {
        UUID sharedIop = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID sharedParent = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID clubA = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID clubB = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

        JsonNode childA = invokeWriteNdjson(mapper, fanOutRow(sharedIop, sharedParent, clubA));
        JsonNode childB = invokeWriteNdjson(mapper, fanOutRow(sharedIop, sharedParent, clubB));

        assertThat(childA.get("legacy_guid").asText())
                .isEqualTo(childB.get("legacy_guid").asText())
                .isEqualTo(sharedIop.toString());
        assertThat(childA.get("location_id").asText())
                .as("both children point at the SAME shared legacy parent LocationId")
                .isEqualTo(childB.get("location_id").asText())
                .isEqualTo(sharedParent.toString());
        assertThat(childA.get("id").asText())
                .as("one IOP fanned out to two clubs must mint two DISTINCT ids")
                .isNotEqualTo(childB.get("id").asText());
        assertThat(childA.get("id").asText())
                .isEqualTo(Coercions.deriveFanOutId(sharedIop, clubA).toString());
        assertThat(childB.get("id").asText())
                .isEqualTo(Coercions.deriveFanOutId(sharedIop, clubB).toString());
    }

    private Map<String, Object> fanOutRow(UUID iopId, UUID locationId, UUID clubId) {
        Map<String, Object> row = legacyRow(seededFaker());
        row.put("InOutboundPointId", iopId.toString());
        row.put("LocationId", locationId.toString());
        row.put("ClubId", clubId.toString());
        return row;
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
