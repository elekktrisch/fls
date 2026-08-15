package ch.alpenflight.articles.domain;

import ch.alpenflight.platform.id.ArticleId;

public class ArticleNotFoundException extends RuntimeException {

    public ArticleNotFoundException(ArticleId id) {
        super("Article not found: " + id);
    }
}
