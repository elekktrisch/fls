package ch.alpenflight.migration.bundle.accounting;

import ch.alpenflight.migration.bundle.Coercions;
import ch.alpenflight.migration.bundle.EntityType;
import ch.alpenflight.migration.bundle.Mapper;
import ch.alpenflight.migration.bundle.ParityIgnore;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * Tenant-scoped catalog aggregate: legacy {@code Articles.ArticleId} →
 * {@code t_article.id}. {@code operating_club_id} is the
 * {@code @TenantId} discriminator per V3.
 *
 * <p>{@code (operating_club_id, article_number)} UNIQUE collisions:
 * producer hard-fails the bundle ({@code ARTICLE_DUPLICATE_NUMBER}
 * warning + reject). DeliveryItem snapshots reference
 * {@code article_number} per Swiss OR Art. 957a — silent dedupe would
 * rewrite legal-record references. The mapper itself does not enforce;
 * the producer-side {@code SELECT} flags collisions before any row
 * crosses the bundle boundary.
 *
 * <p>Legacy ASP.NET artifacts dropped: {@code OwnerId},
 * {@code OwnershipType}, {@code RecordState}, {@code IsDeleted}.
 */
public final class ArticleMapper implements Mapper {

    static final String LEGACY_GUID = "legacy_guid";
    static final String OPERATING_CLUB_ID = "operating_club_id";
    static final String ARTICLE_NUMBER = "article_number";
    static final String ARTICLE_NAME = "article_name";
    static final String ARTICLE_INFO = "article_info";
    static final String DESCRIPTION = "description";
    static final String IS_ACTIVE = "is_active";

    @ParityIgnore
    static final String CREATED_ON = "created_on";
    static final String CREATED_BY_USER_ID = "created_by_user_id";
    @ParityIgnore
    static final String MODIFIED_ON = "modified_on";
    static final String MODIFIED_BY_USER_ID = "modified_by_user_id";
    static final String DELETED_ON = "deleted_on";
    static final String DELETED_BY_USER_ID = "deleted_by_user_id";

    private static final String[] COLUMNS = {
            LEGACY_GUID, OPERATING_CLUB_ID, ARTICLE_NUMBER, ARTICLE_NAME,
            ARTICLE_INFO, DESCRIPTION, IS_ACTIVE,
            CREATED_ON, CREATED_BY_USER_ID,
            MODIFIED_ON, MODIFIED_BY_USER_ID,
            DELETED_ON, DELETED_BY_USER_ID
    };

    @Override
    public EntityType entityType() {
        return EntityType.ARTICLE;
    }

    @Override
    public String[] columns() {
        return COLUMNS.clone();
    }

    @Override
    public List<EntityType> foreignKeys() {
        return List.of(EntityType.CLUB);
    }

    @Override
    public void writeNdjson(ResultSet source, JsonGenerator target)
            throws SQLException, IOException {
        target.writeStartObject();
        target.writeStringField(LEGACY_GUID, source.getString("ArticleId"));
        target.writeStringField(OPERATING_CLUB_ID, source.getString("ClubId"));
        target.writeStringField(ARTICLE_NUMBER, source.getString("ArticleNumber"));
        target.writeStringField(ARTICLE_NAME, source.getString("ArticleName"));
        Coercions.writeOptionalString(target, ARTICLE_INFO, source.getString("ArticleInfo"));
        Coercions.writeOptionalString(target, DESCRIPTION, source.getString("Description"));
        target.writeBooleanField(IS_ACTIVE, source.getBoolean("IsActive"));
        Coercions.writeRequiredTimestamp(target, CREATED_ON, source.getTimestamp("CreatedOn"));
        target.writeStringField(CREATED_BY_USER_ID, source.getString("CreatedByUserId"));
        Coercions.writeOptionalTimestamp(target, MODIFIED_ON, source.getTimestamp("ModifiedOn"));
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
        target.setString(position++, source.get(ARTICLE_NUMBER).asText());
        target.setString(position++, source.get(ARTICLE_NAME).asText());
        target.setString(position++, Coercions.readStringOrNull(source, ARTICLE_INFO));
        target.setString(position++, Coercions.readStringOrNull(source, DESCRIPTION));
        target.setObject(position++, source.get(IS_ACTIVE).asBoolean());
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, CREATED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, CREATED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, MODIFIED_ON));
        target.setObject(position++, Coercions.readUuidOrNull(source, MODIFIED_BY_USER_ID));
        target.setTimestamp(position++, Coercions.readTimestampOrNull(source, DELETED_ON));
        target.setObject(position, Coercions.readUuidOrNull(source, DELETED_BY_USER_ID));
    }
}
