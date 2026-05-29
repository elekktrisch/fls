package ch.alpenflight.migration.bundle.flight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import ch.alpenflight.migration.bundle.AbstractMapperContractTest;
import ch.alpenflight.migration.bundle.EntityType;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.datafaker.Faker;
import org.junit.jupiter.api.Test;

class FlightMapperTest extends AbstractMapperContractTest<FlightMapper> {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonFactory JSON_FACTORY = new JsonFactory();

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
        row.put("OwnerClubId", randomUuidString(faker));
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
        ResultSet rs = mock(ResultSet.class);
        lenient().when(rs.getString(anyString())).thenAnswer(invocation -> {
            Object value = legacy.get(invocation.<String>getArgument(0));
            return value == null ? null : value.toString();
        });
        lenient().when(rs.getObject(anyString())).thenAnswer(
                invocation -> legacy.get(invocation.<String>getArgument(0)));
        lenient().when(rs.getObject(anyString(), any(Class.class))).thenAnswer(invocation -> {
            Object value = legacy.get(invocation.<String>getArgument(0));
            Class<?> target = invocation.getArgument(1);
            if (value == null) {
                return null;
            }
            if (target == Integer.class && value instanceof Number n) {
                return n.intValue();
            }
            if (target == Long.class && value instanceof Number n) {
                return n.longValue();
            }
            if (target == Short.class && value instanceof Number n) {
                return n.shortValue();
            }
            return value;
        });
        lenient().when(rs.getInt(anyString())).thenAnswer(invocation -> {
            Object value = legacy.get(invocation.<String>getArgument(0));
            return value instanceof Number n ? n.intValue() : 0;
        });
        lenient().when(rs.getLong(anyString())).thenAnswer(invocation -> {
            Object value = legacy.get(invocation.<String>getArgument(0));
            return value instanceof Number n ? n.longValue() : 0L;
        });
        lenient().when(rs.getBoolean(anyString())).thenAnswer(invocation -> {
            Object value = legacy.get(invocation.<String>getArgument(0));
            return value instanceof Boolean b && b;
        });
        lenient().when(rs.getDate(anyString())).thenAnswer(
                invocation -> legacy.get(invocation.<String>getArgument(0)));
        lenient().when(rs.getTimestamp(anyString())).thenAnswer(
                invocation -> legacy.get(invocation.<String>getArgument(0)));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (JsonGenerator generator = JSON_FACTORY.createGenerator(sink)) {
            mapper.writeNdjson(rs, generator);
        }
        return JSON.readTree(sink.toByteArray());
    }
}
