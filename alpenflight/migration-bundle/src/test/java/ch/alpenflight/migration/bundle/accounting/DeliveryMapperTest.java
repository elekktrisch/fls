package ch.alpenflight.migration.bundle.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.AbstractMapperContractTest;
import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.ParityMarkers;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import net.datafaker.Faker;
import org.junit.jupiter.api.Test;

class DeliveryMapperTest extends AbstractMapperContractTest<DeliveryMapper> {

    private final DeliveryMapper mapper = new DeliveryMapper();

    @Override
    protected DeliveryMapper mapper() {
        return mapper;
    }

    @Override
    protected Map<String, Object> legacyRow(Faker faker) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("DeliveryId", randomUuidString(faker));
        row.put("ClubId", randomUuidString(faker));
        // Producer-aliased columns — the JOIN to Flights derives
        // ResolvedProcessStateId from ProcessStateId + IsFurtherProcessed.
        row.put("ResolvedProcessStateId", 20);
        row.put("FlightId", randomUuidString(faker));
        row.put("RecipientPersonId", randomUuidString(faker));
        row.put("RecipientName", faker.name().fullName());
        row.put("RecipientFirstname", faker.name().firstName());
        row.put("RecipientLastname", faker.name().lastName());
        row.put("RecipientAddressLine1", faker.address().streetAddress());
        row.put("RecipientAddressLine2", "");
        row.put("RecipientZipCode", faker.address().zipCode());
        row.put("RecipientCity", faker.address().city());
        row.put("RecipientCountryName", faker.address().country());
        row.put("RecipientPersonClubMemberNumber", "1001");
        row.put("DeliveryInformation", "Pickup at front desk");
        row.put("AdditionalInformation", "");
        // Producer-aliased — Integer.parseInt success path. Both columns
        // populated here for round-trip contract coverage; the at-most-one
        // invariant lives on the Delivery aggregate at S-064.
        row.put("ResolvedDeliveryNumber", 42);
        row.put("ResolvedLegacyDeliveryNumberText", "INV-2024-001");
        row.put("DeliveredOn", Timestamp.from(Instant.parse("2024-06-15T18:30:00Z")));
        row.put("BatchId", 0L);
        row.put("CreatedOn", Timestamp.from(Instant.parse("2024-01-01T12:00:00Z")));
        row.put("CreatedByUserId", randomUuidString(faker));
        row.put("ModifiedOn", Timestamp.from(Instant.parse("2024-02-01T12:00:00Z")));
        row.put("ModifiedByUserId", randomUuidString(faker));
        row.put("DeletedOn", Timestamp.from(Instant.parse("2024-03-01T12:00:00Z")));
        row.put("DeletedByUserId", randomUuidString(faker));
        return row;
    }

    @Test
    void exposesDeliveryEntityType() {
        assertThat(mapper.entityType()).isEqualTo(EntityType.DELIVERY);
    }

    @Test
    void declaresClubFlightAndPersonAsForeignKeys() {
        assertThat(mapper.foreignKeys())
                .containsExactly(EntityType.CLUB, EntityType.FLIGHT, EntityType.PERSON);
    }

    @Test
    void recipientColumnsAreSentinelsNotParityIgnored() {
        // Frozen-snapshot invariant per Swiss OR Art. 957a — the parity oracle
        // must compare recipient_* values exactly, never skip them.
        var ignored = ParityMarkers.ignored(DeliveryMapper.class);
        assertThat(ignored)
                .as("recipient_* columns are Swiss OR Art. 957a frozen snapshots — "
                        + "@ParityIgnore would silently allow drift on legal-record fields")
                .doesNotContain(
                        DeliveryMapper.RECIPIENT_NAME,
                        DeliveryMapper.RECIPIENT_FIRSTNAME,
                        DeliveryMapper.RECIPIENT_LASTNAME,
                        DeliveryMapper.RECIPIENT_ADDRESS_LINE1,
                        DeliveryMapper.RECIPIENT_ADDRESS_LINE2,
                        DeliveryMapper.RECIPIENT_ZIP_CODE,
                        DeliveryMapper.RECIPIENT_CITY,
                        DeliveryMapper.RECIPIENT_COUNTRY_NAME,
                        DeliveryMapper.RECIPIENT_PERSON_CLUB_MEMBER_NUMBER);
    }

    @Test
    void deliveryNumberAndLegacyTextBothPresentOnEveryRow() throws Exception {
        // Mutually-exclusive invariant lives on Delivery (S-064) — at the
        // mapper level both columns are declared and populated per the
        // producer-resolved values.
        JsonNode emitted = invokeWriteNdjson(mapper, legacyRow(seededFaker()));
        assertThat(emitted.has(DeliveryMapper.DELIVERY_NUMBER)).isTrue();
        assertThat(emitted.has(DeliveryMapper.LEGACY_DELIVERY_NUMBER_TEXT)).isTrue();
    }
}
