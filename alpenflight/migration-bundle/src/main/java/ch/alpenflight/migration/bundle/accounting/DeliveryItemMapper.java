package ch.alpenflight.migration.bundle.accounting;

import ch.alpenflight.migration.bundle.Coercions;
import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.Mapper;
import ch.alpenflight.migration.bundle.ParityIgnore;
import ch.alpenflight.migration.bundle.ParitySentinel;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

public final class DeliveryItemMapper implements Mapper {

    static final String LEGACY_GUID = "legacy_guid";
    static final String OPERATING_CLUB_ID = "operating_club_id";
    static final String DELIVERY_ID = "delivery_id";
    static final String POSITION = "position";
    static final String ARTICLE_ID = "article_id";
    static final String ARTICLE_NUMBER = "article_number";
    static final String ITEM_TEXT = "item_text";
    static final String ADDITIONAL_INFORMATION = "additional_information";

    @ParitySentinel
    static final String QUANTITY = "quantity";

    @ParitySentinel
    static final String UNIT_PRICE = "unit_price";

    static final String DISCOUNT_IN_PERCENT = "discount_in_percent";

    @ParitySentinel
    static final String UNIT_TYPE_CODE = "unit_type_code";

    @ParityIgnore
    static final String CREATED_ON = "created_on";
    static final String CREATED_BY_USER_ID = "created_by_user_id";
    @ParityIgnore
    static final String MODIFIED_ON = "modified_on";
    static final String MODIFIED_BY_USER_ID = "modified_by_user_id";
    static final String DELETED_ON = "deleted_on";
    static final String DELETED_BY_USER_ID = "deleted_by_user_id";

    private static final String[] COLUMNS = {
            LEGACY_GUID, OPERATING_CLUB_ID, DELIVERY_ID, POSITION,
            ARTICLE_ID, ARTICLE_NUMBER, ITEM_TEXT, ADDITIONAL_INFORMATION,
            QUANTITY, UNIT_PRICE, DISCOUNT_IN_PERCENT, UNIT_TYPE_CODE,
            CREATED_ON, CREATED_BY_USER_ID,
            MODIFIED_ON, MODIFIED_BY_USER_ID,
            DELETED_ON, DELETED_BY_USER_ID
    };

    @Override
    public EntityType entityType() {
        return EntityType.DELIVERY_ITEM;
    }

    @Override
    public String[] wireColumns() {
        return COLUMNS.clone();
    }

    @Override
    public List<EntityType> foreignKeyTargets() {
        return List.of(EntityType.CLUB, EntityType.DELIVERY, EntityType.ARTICLE);
    }

    @Override
    public void writeNdjson(ResultSet source, JsonGenerator target)
            throws SQLException, IOException {
        target.writeStartObject();
        target.writeStringField(LEGACY_GUID, source.getString("DeliveryItemId"));
        target.writeStringField(OPERATING_CLUB_ID, source.getString("OperatingClubId"));
        target.writeStringField(DELIVERY_ID, source.getString("DeliveryId"));
        target.writeNumberField(POSITION, source.getInt("Position"));
        Coercions.writeOptionalString(target, ARTICLE_ID, source.getString("ResolvedArticleId"));
        target.writeStringField(ARTICLE_NUMBER, source.getString("ArticleNumber"));
        Coercions.writeOptionalString(target, ITEM_TEXT, source.getString("ItemText"));
        Coercions.writeOptionalString(target, ADDITIONAL_INFORMATION,
                source.getString("AdditionalInformation"));
        Coercions.writeOptionalBigDecimal(target, QUANTITY, source.getBigDecimal("Quantity"));
        Coercions.writeOptionalBigDecimal(target, UNIT_PRICE,
                source.getBigDecimal("ResolvedUnitPrice"));
        target.writeNumberField(DISCOUNT_IN_PERCENT, source.getInt("DiscountInPercent"));
        target.writeStringField(UNIT_TYPE_CODE, source.getString("UnitType"));
        Coercions.writeRequiredTimestamp(target, CREATED_ON, source.getTimestamp("CreatedOn"));
        target.writeStringField(CREATED_BY_USER_ID, source.getString("CreatedByUserId"));
        Coercions.writeRequiredTimestampCoalescing(
                target, MODIFIED_ON, source.getTimestamp("ModifiedOn"),
                source.getTimestamp("CreatedOn"));
        Coercions.writeOptionalString(target, MODIFIED_BY_USER_ID,
                source.getString("ModifiedByUserId"));
        Coercions.writeOptionalTimestamp(target, DELETED_ON, source.getTimestamp("DeletedOn"));
        Coercions.writeOptionalString(target, DELETED_BY_USER_ID,
                source.getString("DeletedByUserId"));
        target.writeEndObject();
    }

    @Override
    public void readEntity(JsonNode source, PreparedStatement target) throws SQLException {
        int position = 1;
        target.setObject(position++, UUID.fromString(source.get(LEGACY_GUID).asText()));
        target.setObject(position++, UUID.fromString(source.get(OPERATING_CLUB_ID).asText()));
        target.setObject(position++, UUID.fromString(source.get(DELIVERY_ID).asText()));
        target.setInt(position++, source.get(POSITION).intValue());
        target.setObject(position++, Coercions.readUuidOrNull(source, ARTICLE_ID));
        target.setString(position++, source.get(ARTICLE_NUMBER).asText());
        target.setString(position++, Coercions.readStringOrNull(source, ITEM_TEXT));
        target.setString(position++, Coercions.readStringOrNull(source, ADDITIONAL_INFORMATION));
        target.setBigDecimal(position++, Coercions.readBigDecimalOrNull(source, QUANTITY));
        target.setBigDecimal(position++, Coercions.readBigDecimalOrNull(source, UNIT_PRICE));
        target.setInt(position++, source.get(DISCOUNT_IN_PERCENT).intValue());
        target.setString(position++, source.get(UNIT_TYPE_CODE).asText());
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, CREATED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, CREATED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, MODIFIED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, MODIFIED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, DELETED_ON));
        target.setObject(position, Coercions.readUuidOrNull(source, DELETED_BY_USER_ID));
    }
}
