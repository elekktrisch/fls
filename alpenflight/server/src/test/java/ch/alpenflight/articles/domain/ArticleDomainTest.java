package ch.alpenflight.articles.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ArticleDomainTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-05-24T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void register_minimalArticle_setsFieldsAndActive() {
        Article a = Article.register("A-100", "Glider hour", "per flight", null, true);
        assertThat(a.getArticleNumber()).isEqualTo("A-100");
        assertThat(a.getArticleName()).isEqualTo("Glider hour");
        assertThat(a.getArticleInfo()).isEqualTo("per flight");
        assertThat(a.getDescription()).isNull();
        assertThat(a.isActive()).isTrue();
        assertThat(a.isDeleted()).isFalse();
    }

    @Test
    void register_trims_articleNumberAndName() {
        Article a = Article.register("  A-200  ", "  Tow service  ", null, null, true);
        assertThat(a.getArticleNumber()).isEqualTo("A-200");
        assertThat(a.getArticleName()).isEqualTo("Tow service");
    }

    @Test
    void register_blankNumber_rejects() {
        assertThatThrownBy(() -> Article.register("   ", "name", null, null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("articleNumber");
    }

    @Test
    void register_blankName_rejects() {
        assertThatThrownBy(() -> Article.register("A-300", "  ", null, null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("articleName");
    }

    @Test
    void register_overlongNumber_rejects() {
        String tooLong = "A".repeat(51);
        assertThatThrownBy(() -> Article.register(tooLong, "name", null, null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("articleNumber");
    }

    @Test
    void renumber_mutatesNumber() {
        // article_number is mutable post-create — delivery_item.article_number
        // is a frozen snapshot (Swiss OR Art. 957a) so renaming never
        // corrupts historical invoices.
        Article a = Article.register("A-400", "Original", null, null, true);
        a.renumber("A-401");
        assertThat(a.getArticleNumber()).isEqualTo("A-401");
    }

    @Test
    void deactivate_then_activate_isIdempotent() {
        Article a = Article.register("A-500", "Toggle", null, null, true);
        a.deactivate();
        assertThat(a.isActive()).isFalse();
        a.deactivate();
        assertThat(a.isActive()).isFalse();
        a.activate();
        assertThat(a.isActive()).isTrue();
        a.activate();
        assertThat(a.isActive()).isTrue();
    }

    @Test
    void softDelete_idempotent() {
        Article a = Article.register("A-600", "ToDelete", null, null, true);
        a.softDelete(null, FIXED_CLOCK);
        assertThat(a.isDeleted()).isTrue();
        a.softDelete(null, FIXED_CLOCK);
        assertThat(a.isDeleted()).isTrue();
    }

    @Test
    void mutateAfterSoftDelete_throws() {
        Article a = Article.register("A-700", "Frozen", null, null, true);
        a.softDelete(null, FIXED_CLOCK);
        assertThatThrownBy(a::deactivate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("soft-deleted");
    }

    @Test
    void emptyInfoOrDescription_storedAsNull() {
        Article a = Article.register("A-800", "Empty", "   ", "", true);
        assertThat(a.getArticleInfo()).isNull();
        assertThat(a.getDescription()).isNull();
    }
}
