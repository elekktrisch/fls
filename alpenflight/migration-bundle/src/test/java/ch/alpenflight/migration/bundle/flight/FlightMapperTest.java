package ch.alpenflight.migration.bundle.flight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import ch.alpenflight.migration.bundle.AbstractMapperContractTest;
import ch.alpenflight.migration.bundle.EntityType;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.datafaker.Faker;
import org.junit.jupiter.api.Test;

class FlightMapperTest extends AbstractMapperContractTest<FlightMapper> {

    private final FlightMapper mapper = new FlightMapper();

    @Override
    protected FlightMapper mapper() {
        return mapper;
    }

    @Override
    protected Map<String, Object> legacyRow(Faker faker) {
        return rowWithAirState(faker, FlightMapper.LEGACY_AIR_STATE_FLIGHT_PLAN_OPEN);
    }

    @Override
    protected Map<String, Set<Number>> permittedSparseEnumValues() {
        // Sparse-enum sacred cow (FlightAircraftTypeValue.cs:5-7):
        // 1=Glider, 2=Tow, 4=Motor — 3 deliberately skipped. S-058
        // enforces; mapper passes through.
        return Map.of(FlightMapper.FLIGHT_AIRCRAFT_TYPE_ID,
                Set.of((short) 1, (short) 2, (short) 4));
    }

    @Test
    void exposesFlightEntityType() {
        assertThat(mapper.entityType()).isEqualTo(EntityType.FLIGHT);
    }

    @Test
    void doesNotDeclareSelfFkSinceTwoPassUpdateRunsAtIngest() {
        assertThat(mapper.foreignKeys())
                .as("Flight.tow_flight_id self-FK is deferred to S-141 two-pass "
                        + "UPDATE (PersonCategory precedent). Declaring FLIGHT "
                        + "here would violate the ingest-order invariant.")
                .doesNotContain(EntityType.FLIGHT);
    }

    @Test
    void declaresEveryRequiredForeignKeyTarget() {
        assertThat(mapper.foreignKeys())
                .containsExactlyInAnyOrder(
                        EntityType.CLUB, EntityType.AIRCRAFT, EntityType.LOCATION,
                        EntityType.FLIGHT_TYPE, EntityType.START_TYPE);
    }

    @Test
    void v13TranslationEmitsModifiedOnWhenLegacyStateIsFlightPlanOpen() throws Exception {
        Instant modifiedOn = Instant.parse("2024-06-01T08:30:00Z");
        Map<String, Object> row = rowWithAirState(
                seededFaker(), FlightMapper.LEGACY_AIR_STATE_FLIGHT_PLAN_OPEN);
        row.put("ModifiedOn", Timestamp.from(modifiedOn));
        JsonNode emitted = invokeWriteNdjson(row);
        assertThat(emitted.get(FlightMapper.FLIGHT_PLAN_OPENED_ON).asText())
                .as("Legacy AirStateId == FlightPlanOpen → flight_plan_opened_on "
                        + "carries the legacy ModifiedOn timestamp")
                .isEqualTo(modifiedOn.toString());
    }

    @Test
    void v13TranslationEmitsNullWhenLegacyStateIsNotFlightPlanOpen() throws Exception {
        for (int otherState : new int[] {0, 8, 10, 15, 20, 25}) {
            Map<String, Object> row = rowWithAirState(seededFaker(), otherState);
            JsonNode emitted = invokeWriteNdjson(row);
            assertThat(emitted.get(FlightMapper.FLIGHT_PLAN_OPENED_ON).isNull())
                    .as("Legacy AirStateId == %d (not FlightPlanOpen) → "
                            + "flight_plan_opened_on must be null per V13 + ADR 0022 D2",
                            otherState)
                    .isTrue();
        }
    }

    @Test
    void towFlightIdPreservedAcrossSoftDeleteToggle() throws Exception {
        // Build a live (non-tombstoned) row, capture the original tow ref,
        // and assert it both survives the live emission AND survives when
        // the same row is re-emitted with DeletedOn set. V3 schema FK is
        // ON DELETE SET NULL; the mapper must NOT pre-empt the cascade by
        // nullifying tow_flight_id at port time — the legacy chain must
        // remain intact for the S-141 two-pass UPDATE even when the tow
        // flight also ports tombstoned.
        Map<String, Object> liveRow = rowWithAirState(
                seededFaker(), FlightMapper.LEGACY_AIR_STATE_FLIGHT_PLAN_OPEN);
        liveRow.put("DeletedOn", null);
        liveRow.put("DeletedByUserId", null);
        String expectedTowFlightId = (String) liveRow.get("TowFlightId");
        JsonNode liveEmitted = invokeWriteNdjson(liveRow);
        assertThat(liveEmitted.get(FlightMapper.TOW_FLIGHT_ID).asText())
                .as("Live (non-deleted) Flight row must carry tow_flight_id")
                .isEqualTo(expectedTowFlightId);

        Map<String, Object> tombstonedRow = new LinkedHashMap<>(liveRow);
        tombstonedRow.put("DeletedOn", Timestamp.from(Instant.parse("2024-06-04T00:00:00Z")));
        tombstonedRow.put("DeletedByUserId", randomUuidString(seededFaker()));
        JsonNode tombstonedEmitted = invokeWriteNdjson(tombstonedRow);
        assertThat(tombstonedEmitted.get(FlightMapper.TOW_FLIGHT_ID).asText())
                .as("Soft-deleted Flight row must STILL carry tow_flight_id — "
                        + "nullification at port time would defeat the V3 "
                        + "ON DELETE SET NULL cascade and lose the forensic "
                        + "chain to the (possibly also-tombstoned) tow row")
                .isEqualTo(expectedTowFlightId);
    }

    @Test
    void emptyGuidTowAndLocationFksPortAsNull() throws Exception {
        // Legacy ASP.NET writes the all-zero uniqueidentifier into NOT-NULL GUID
        // FK columns to mean "no relation" (oracle #18). The nullable rewrite FKs
        // must port that as null — a verbatim empty-guid would violate
        // fk_flight_tow_flight_id / fk_flight_*_location_id at INSERT.
        Map<String, Object> row = rowWithAirState(
                seededFaker(), FlightMapper.LEGACY_AIR_STATE_FLIGHT_PLAN_OPEN);
        String emptyGuid = "00000000-0000-0000-0000-000000000000";
        row.put("TowFlightId", emptyGuid);
        row.put("StartLocationId", emptyGuid);
        row.put("LdgLocationId", emptyGuid);
        row.put("FlightTypeId", emptyGuid);
        JsonNode emitted = invokeWriteNdjson(row);
        assertThat(emitted.get(FlightMapper.TOW_FLIGHT_ID).isNull())
                .as("empty-guid TowFlightId → null").isTrue();
        assertThat(emitted.get(FlightMapper.START_LOCATION_ID).isNull())
                .as("empty-guid StartLocationId → null").isTrue();
        assertThat(emitted.get(FlightMapper.LDG_LOCATION_ID).isNull())
                .as("empty-guid LdgLocationId → null").isTrue();
        assertThat(emitted.get(FlightMapper.FLIGHT_TYPE_ID).isNull())
                .as("empty-guid FlightTypeId → null").isTrue();
    }

    @Test
    void lockedAtSetFromModifiedOnOnlyForLockedOrBeyond() throws Exception {
        Instant modifiedOn = Instant.parse("2024-06-10T09:00:00Z");
        // Still-Valid (30): locked_at null — the bill gate must never fire on import.
        Map<String, Object> valid = rowWithAirState(
                seededFaker(), FlightMapper.LEGACY_AIR_STATE_FLIGHT_PLAN_OPEN);
        valid.put("ProcessStateId", 30);
        valid.put("ModifiedOn", Timestamp.from(modifiedOn));
        assertThat(invokeWriteNdjson(valid).get(FlightMapper.LOCKED_AT).isNull())
                .as("Valid(30) flight ports locked_at = null").isTrue();
        // Locked(40) and beyond: locked_at = ModifiedOn (the legacy lock-time proxy).
        for (int lockedState : new int[] {40, 50, 60, 99}) {
            Map<String, Object> locked = rowWithAirState(
                    seededFaker(), FlightMapper.LEGACY_AIR_STATE_FLIGHT_PLAN_OPEN);
            locked.put("ProcessStateId", lockedState);
            locked.put("ModifiedOn", Timestamp.from(modifiedOn));
            assertThat(invokeWriteNdjson(locked).get(FlightMapper.LOCKED_AT).asText())
                    .as("ProcessStateId %d (>= LOCKED) ports locked_at = ModifiedOn", lockedState)
                    .isEqualTo(modifiedOn.toString());
        }
    }

    @Test
    void sparseEnumValuePassesThroughVerbatimWithoutMapperSideValueSetGuard() throws Exception {
        for (int rawValue : new int[] {1, 2, 4, 3, 99}) {
            Map<String, Object> row = rowWithAirState(
                    seededFaker(), FlightMapper.LEGACY_AIR_STATE_FLIGHT_PLAN_OPEN);
            row.put("FlightAircraftType", rawValue);
            JsonNode emitted = invokeWriteNdjson(row);
            assertThat(emitted.get(FlightMapper.FLIGHT_AIRCRAFT_TYPE_ID).intValue())
                    .as("Sparse-enum value %d must pass through verbatim — rejection "
                            + "lives on the Flight aggregate (S-058) per ADR 0022 D2; "
                            + "introducing a mapper-side guard re-introduces "
                            + "schema-shaped business logic into the producer layer",
                            rawValue)
                    .isEqualTo(rawValue);
        }
    }

    @Test
    void sparseEnumPositionBindsAsShortNotInteger() throws Exception {
        Map<String, Object> row = rowWithAirState(
                seededFaker(), FlightMapper.LEGACY_AIR_STATE_FLIGHT_PLAN_OPEN);
        row.put("FlightAircraftType", 2);
        JsonNode emitted = invokeWriteNdjson(row);
        Map<Integer, Short> shortBinds = new TreeMap<>();
        PreparedStatement ps = mock(PreparedStatement.class);
        doAnswer(invocation -> {
            shortBinds.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(ps).setShort(anyInt(), anyShort());
        // Soak up every other bind so the call doesn't blow up on unstubbed methods.
        lenient().doNothing().when(ps).setObject(anyInt(), any());
        lenient().doNothing().when(ps).setObject(anyInt(), any(), anyInt());
        lenient().doNothing().when(ps).setString(anyInt(), any());
        lenient().doNothing().when(ps).setBigDecimal(anyInt(), any());
        lenient().doNothing().when(ps).setDate(anyInt(), any());
        lenient().doNothing().when(ps).setTimestamp(anyInt(), any());
        lenient().doNothing().when(ps).setBytes(anyInt(), any());
        lenient().doNothing().when(ps).setNull(anyInt(), anyInt());

        mapper.readEntity(emitted, ps);

        int sparseEnumPosition =
                positionOf(mapper, FlightMapper.FLIGHT_AIRCRAFT_TYPE_ID);
        assertThat(shortBinds)
                .as("flight_aircraft_type_id binds via setShort to preserve "
                        + "the SMALLINT type contract — Integer boxing would "
                        + "violate the allocation-discipline budget")
                .containsEntry(sparseEnumPosition, (short) 2);
    }

    private Map<String, Object> rowWithAirState(Faker faker, int legacyAirStateId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("FlightId", randomUuidString(faker));
        // Real legacy column is OwnerId (the operating-club source), NOT
        // OwnerClubId — the J-2 T-07 producer-SELECT reconciliation fix.
        row.put("OwnerId", randomUuidString(faker));
        row.put("AircraftId", randomUuidString(faker));
        row.put("FlightDate", java.sql.Date.valueOf("2024-06-01"));
        row.put("StartDateTime", Timestamp.from(Instant.parse("2024-06-01T08:00:00Z")));
        row.put("LdgDateTime", Timestamp.from(Instant.parse("2024-06-01T10:00:00Z")));
        row.put("BlockStartDateTime", Timestamp.from(Instant.parse("2024-06-01T07:50:00Z")));
        row.put("BlockEndDateTime", Timestamp.from(Instant.parse("2024-06-01T10:10:00Z")));
        row.put("StartLocationId", randomUuidString(faker));
        row.put("LdgLocationId", randomUuidString(faker));
        row.put("StartRunway", "14");
        row.put("LdgRunway", "32");
        row.put("OutboundRoute", "VIA POINT ALPHA");
        row.put("InboundRoute", "VIA POINT BRAVO");
        row.put("FlightTypeId", randomUuidString(faker));
        row.put("IsSoloFlight", false);
        row.put("StartType", 2);
        row.put("TowFlightId", randomUuidString(faker));
        row.put("NrOfLdgs", (short) 1);
        row.put("NrOfLdgsOnStartLocation", (short) 1);
        row.put("NoStartTimeInformation", false);
        row.put("NoLdgTimeInformation", false);
        row.put("AirStateId", legacyAirStateId);
        row.put("ProcessStateId", 30);
        row.put("FlightAircraftType", 1);
        row.put("EngineStartOperatingCounterInSeconds", 0L);
        row.put("EngineEndOperatingCounterInSeconds", 7200L);
        row.put("Comment", "Routine training flight");
        row.put("IncidentComment", "");
        row.put("ValidationErrors", "");
        row.put("CouponNumber", "C-12345");
        row.put("FlightCostBalanceType", 1);
        row.put("DeliveryCreatedOn", Timestamp.from(Instant.parse("2024-07-01T00:00:00Z")));
        row.put("ValidatedOn", Timestamp.from(Instant.parse("2024-06-02T00:00:00Z")));
        row.put("NrOfPassengers", (short) 0);
        row.put("StartPosition", (short) 1);
        row.put("FlightReportSentOn", Timestamp.from(Instant.parse("2024-06-03T00:00:00Z")));
        row.put("CreatedOn", Timestamp.from(Instant.parse("2024-05-30T00:00:00Z")));
        row.put("CreatedByUserId", randomUuidString(faker));
        row.put("ModifiedOn", Timestamp.from(Instant.parse("2024-06-01T12:00:00Z")));
        row.put("ModifiedByUserId", randomUuidString(faker));
        row.put("DeletedOn", Timestamp.from(Instant.parse("2024-06-04T00:00:00Z")));
        row.put("DeletedByUserId", randomUuidString(faker));
        return row;
    }

    private JsonNode invokeWriteNdjson(Map<String, Object> legacy) throws Exception {
        return invokeWriteNdjson(mapper, legacy);
    }
}
