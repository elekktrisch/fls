package ch.alpenflight.migration.bundle.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.AbstractMapperContractTest;
import ch.alpenflight.migration.bundle.EntityType;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import net.datafaker.Faker;
import org.junit.jupiter.api.Test;

class ArticleMapperTest extends AbstractMapperContractTest<ArticleMapper> {

    private final ArticleMapper mapper = new ArticleMapper();

    @Override
    protected ArticleMapper mapper() {
        return mapper;
    }

    @Override
    protected Map<String, Object> legacyRow(Faker faker) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ArticleId", randomUuidString(faker));
        row.put("ClubId", randomUuidString(faker));
        row.put("ArticleNumber", "A-1001");
        row.put("ArticleName", faker.commerce().productName());
        row.put("ArticleInfo", "Promo");
        row.put("Description", faker.lorem().sentence());
        row.put("IsActive", true);
        row.put("CreatedOn", Timestamp.from(Instant.parse("2024-01-01T12:00:00Z")));
        row.put("CreatedByUserId", randomUuidString(faker));
        row.put("ModifiedOn", Timestamp.from(Instant.parse("2024-02-01T12:00:00Z")));
        row.put("ModifiedByUserId", randomUuidString(faker));
        row.put("DeletedOn", Timestamp.from(Instant.parse("2024-03-01T12:00:00Z")));
        row.put("DeletedByUserId", randomUuidString(faker));
        return row;
    }

    @Test
    void exposesArticleEntityType() {
        assertThat(mapper.entityType()).isEqualTo(EntityType.ARTICLE);
    }

    @Test
    void declaresOnlyClubAsForeignKey() {
        assertThat(mapper.foreignKeys()).containsExactly(EntityType.CLUB);
    }
}
