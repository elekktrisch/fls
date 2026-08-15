package ch.alpenflight.articles.application;

import ch.alpenflight.articles.application.ArticleDtos.ArticleDetail;
import ch.alpenflight.articles.application.ArticleDtos.ArticleListItem;
import ch.alpenflight.articles.domain.Article;
import ch.alpenflight.platform.id.ArticleId;
import java.util.Objects;

final class ArticleMapper {

    private ArticleMapper() {}

    static ArticleDetail toDetail(Article a) {
        ArticleId id = Objects.requireNonNull(a.getId(),
                "Article.id must be non-null after persist");
        return new ArticleDetail(
                id,
                a.getArticleNumber(),
                a.getArticleName(),
                a.getArticleInfo(),
                a.getDescription(),
                a.isActive());
    }

    static ArticleListItem toListItem(Article a) {
        ArticleId id = Objects.requireNonNull(a.getId(),
                "Article.id must be non-null after persist");
        return new ArticleListItem(
                id,
                a.getArticleNumber(),
                a.getArticleName(),
                a.getArticleInfo(),
                a.isActive());
    }
}
