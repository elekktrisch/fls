package ch.alpenflight.migration.bundle.flight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import ch.alpenflight.migration.bundle.AbstractMapperContractTest;
import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.ForeignKeyColumn;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import net.datafaker.Faker;
import org.junit.jupiter.api.Test;

class AircraftMapperTest extends AbstractMapperContractTest<AircraftMapper> {

    private final AircraftMapper mapper = new AircraftMapper();

    @Override
    protected AircraftMapper mapper() {
        return mapper;
    }

    @Override
    protected Map<String, Object> legacyRow(Faker faker) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("AircraftId", randomUuidString(faker));
        row.put("ManagingClubId", randomUuidString(faker));
        row.put("AircraftOwnerClubId", randomUuidString(faker));
        row.put("AircraftType", 1);
        row.put("ManufacturerName", faker.aviation().manufacturer());
        row.put("AircraftModel", faker.aviation().aircraft());
        row.put("Immatriculation", "HB-" + faker.regexify("[A-Z]{3}"));
        row.put("CompetitionSign", faker.regexify("[A-Z]{2}"));
        row.put("FLARMId", faker.regexify("[A-F0-9]{6}"));
        row.put("AircraftSerialNumber", faker.regexify("[0-9]{6}"));
        row.put("YearOfManufacture", Date.valueOf(LocalDate.parse("1995-01-01")));
        row.put("NoiseClass", "C");
        row.put("NoiseLevel", new BigDecimal("65.4"));
        row.put("MTOM", 1200);
        row.put("NrOfSeats", 2);
        row.put("AircraftOwnerPersonId", randomUuidString(faker));
        row.put("FlightOperatingCounterUnitTypeId", 1);
        row.put("EngineOperatingCounterUnitTypeId", 2);
        row.put("HomebaseId", randomUuidString(faker));
        row.put("SpotLink", "https://findmespot.com/" + faker.internet().slug());
        row.put("IsTowingOrWinchRequired", true);
        row.put("IsTowingstartAllowed", true);
        row.put("IsWinchstartAllowed", false);
        row.put("IsTowingAircraft", false);
        row.put("IsFastEntryRecord", false);
        row.put("Comment", faker.lorem().sentence());
        row.put("DaecIndex", 42);
        row.put("CreatedOn", Timestamp.from(Instant.parse("2024-01-01T12:00:00Z")));
        row.put("CreatedByUserId", randomUuidString(faker));
        row.put("ModifiedOn", Timestamp.from(Instant.parse("2024-02-01T12:00:00Z")));
        row.put("ModifiedByUserId", randomUuidString(faker));
        row.put("DeletedOn", Timestamp.from(Instant.parse("2024-03-01T12:00:00Z")));
        row.put("DeletedByUserId", randomUuidString(faker));
        return row;
    }

    @Test
    void exposesAircraftEntityType() {
        assertThat(mapper.entityType()).isEqualTo(EntityType.AIRCRAFT);
    }

    @Test
    void declaresClubPersonAndLocationAsStructuralForeignKeys() {
        assertThat(mapper.foreignKeyTargets())
                .as("Aircraft is cross-tenant; FKs to Club (owner/manager), "
                        + "Person (aircraft_owner_person_id), Location (homebase_id) "
                        + "ride through TENANT_BYPASS_ALLOW_LIST")
                .containsExactlyInAnyOrder(
                        EntityType.CLUB, EntityType.PERSON, EntityType.LOCATION);
    }

    @Test
    void declaresNonCanonicalForeignKeyColumnsWithHomebaseDisambiguator() {
        assertThat(mapper.foreignKeyColumns())
                .as("AIRCRAFT declares each off-convention FK column → target pair; "
                        + "homebase_id (fan-out LOCATION) disambiguates via managing_club_id")
                .containsExactly(
                        new ForeignKeyColumn(AircraftMapper.MANAGING_CLUB_ID, EntityType.CLUB),
                        new ForeignKeyColumn(AircraftMapper.OWNER_CLUB_ID, EntityType.CLUB),
                        new ForeignKeyColumn(
                                AircraftMapper.AIRCRAFT_OWNER_PERSON_ID, EntityType.PERSON),
                        new ForeignKeyColumn(
                                AircraftMapper.HOMEBASE_ID, EntityType.LOCATION,
                                AircraftMapper.MANAGING_CLUB_ID));
    }

    @Test
    void rejectsNonHttpsSpotLinkAtReadEntity() {
        ObjectNode source = aircraftJsonWithEveryColumnPresent();
        source.put(AircraftMapper.SPOT_LINK, "http://insecure.example/aircraft");
        assertThatThrownBy(() -> mapper.readEntity(source, mock(PreparedStatement.class)))
                .hasMessageContaining(AircraftMapper.NOT_HTTPS_SPOT_LINK_ERROR)
                .hasMessageContaining("Aircraft.spot_link must start with 'https://'");
    }

    @Test
    void acceptsHttpsSpotLink() {
        ObjectNode source = aircraftJsonWithEveryColumnPresent();
        source.put(AircraftMapper.SPOT_LINK, "https://findmespot.com/abc");
        assertThatCode(() -> mapper.readEntity(source, mock(PreparedStatement.class)))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsNullSpotLink() {
        ObjectNode source = aircraftJsonWithEveryColumnPresent();
        source.putNull(AircraftMapper.SPOT_LINK);
        assertThatCode(() -> mapper.readEntity(source, mock(PreparedStatement.class)))
                .doesNotThrowAnyException();
    }

    private ObjectNode aircraftJsonWithEveryColumnPresent() {
        Faker faker = seededFaker();
        ObjectNode root = new ObjectMapper().createObjectNode();
        String uuid = randomUuidString(faker);
        root.put(AircraftMapper.LEGACY_GUID, uuid);
        root.put(AircraftMapper.MANAGING_CLUB_ID, uuid);
        root.putNull(AircraftMapper.OWNER_CLUB_ID);
        root.put(AircraftMapper.AIRCRAFT_TYPE_ID, uuid);
        root.putNull(AircraftMapper.MANUFACTURER_NAME);
        root.putNull(AircraftMapper.AIRCRAFT_MODEL);
        root.put(AircraftMapper.IMMATRICULATION, "HB-ABC");
        root.putNull(AircraftMapper.COMPETITION_SIGN);
        root.putNull(AircraftMapper.FLARM_ID);
        root.putNull(AircraftMapper.AIRCRAFT_SERIAL_NUMBER);
        root.putNull(AircraftMapper.YEAR_OF_MANUFACTURE);
        root.putNull(AircraftMapper.NOISE_CLASS);
        root.putNull(AircraftMapper.NOISE_LEVEL);
        root.putNull(AircraftMapper.MTOM);
        root.putNull(AircraftMapper.NR_OF_SEATS);
        root.putNull(AircraftMapper.AIRCRAFT_OWNER_PERSON_ID);
        root.putNull(AircraftMapper.FLIGHT_OPERATING_COUNTER_UNIT_TYPE_ID);
        root.putNull(AircraftMapper.ENGINE_OPERATING_COUNTER_UNIT_TYPE_ID);
        root.putNull(AircraftMapper.HOMEBASE_ID);
        root.put(AircraftMapper.IS_TOWING_OR_WINCH_REQUIRED, false);
        root.put(AircraftMapper.IS_TOWING_START_ALLOWED, false);
        root.put(AircraftMapper.IS_WINCH_START_ALLOWED, false);
        root.put(AircraftMapper.IS_TOWING_AIRCRAFT, false);
        root.put(AircraftMapper.IS_FAST_ENTRY_RECORD, false);
        root.putNull(AircraftMapper.COMMENT);
        root.putNull(AircraftMapper.DAEC_INDEX);
        root.put(AircraftMapper.CREATED_ON, Instant.parse("2024-01-01T12:00:00Z").toString());
        root.putNull(AircraftMapper.CREATED_BY_USER_ID);
        root.putNull(AircraftMapper.MODIFIED_ON);
        root.putNull(AircraftMapper.MODIFIED_BY_USER_ID);
        root.putNull(AircraftMapper.DELETED_ON);
        root.putNull(AircraftMapper.DELETED_BY_USER_ID);
        return root;
    }
}
