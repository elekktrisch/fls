package ch.alpenflight.migration.bundle.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.AbstractMapperContractTest;
import ch.alpenflight.migration.bundle.EntityType;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import net.datafaker.Faker;
import org.junit.jupiter.api.Test;

class DeliveryItemMapperTest extends AbstractMapperContractTest<DeliveryItemMapper> {

    private final DeliveryItemMapper mapper = new DeliveryItemMapper();

    @Override
    protected DeliveryItemMapper mapper() {
        return mapper;
    }

    @Override
    protected Map<String, Object> legacyRow(Faker faker) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("DeliveryItemId", randomUuidString(faker));
        row.put("OperatingClubId", randomUuidString(faker));
        row.put("DeliveryId", randomUuidString(faker));
        row.put("Position", 1);
        row.put("ResolvedArticleId", randomUuidString(faker));
        row.put("ArticleNumber", "A-1001");
        row.put("ItemText", faker.commerce().productName());
        row.put("AdditionalInformation", "");
        row.put("Quantity", new BigDecimal("1.5000"));
        row.put("ResolvedUnitPrice", new BigDecimal("85.0000"));
        row.put("DiscountInPercent", 0);
        row.put("UnitType", "MINUTES");
        row.put("CreatedOn", Timestamp.from(Instant.parse("2024-01-01T12:00:00Z")));
        row.put("CreatedByUserId", randomUuidString(faker));
        row.put("ModifiedOn", Timestamp.from(Instant.parse("2024-02-01T12:00:00Z")));
        row.put("ModifiedByUserId", randomUuidString(faker));
        row.put("DeletedOn", Timestamp.from(Instant.parse("2024-03-01T12:00:00Z")));
        row.put("DeletedByUserId", randomUuidString(faker));
        return row;
    }

    @Test
    void exposesDeliveryItemEntityType() {
        assertThat(mapper.entityType()).isEqualTo(EntityType.DELIVERY_ITEM);
    }

    @Test
    void declaresClubDeliveryAndArticleAsForeignKeys() {
        assertThat(mapper.foreignKeys())
                .containsExactly(EntityType.CLUB, EntityType.DELIVERY, EntityType.ARTICLE);
    }

    @Test
    void columnsDoNotIncludeRemovedTotalAmount() {
        assertThat(mapper.columns()).doesNotContain("total_amount");
    }

    @Test
    void unitPriceColumnDeclaredEvenThoughLegacyHasNoSuchColumn() {
        assertThat(mapper.columns()).contains(DeliveryItemMapper.UNIT_PRICE);
    }
}
