package ch.alpenflight.articles.domain;

import ch.alpenflight.platform.id.ArticleId;

/**
 * Thrown when an Article endpoint is asked to read / mutate a non-existent
 * or soft-deleted row, including the cross-tenant case (Hibernate's
 * {@code @TenantId} filter scrubs it from the result set, so the service
 * cannot distinguish "doesn't exist" from "belongs to another tenant" —
 * 404, never 403, is the IDOR contract per S-159).
 */
public class ArticleNotFoundException extends RuntimeException {

    public ArticleNotFoundException(ArticleId id) {
        super("Article not found: " + id);
    }
}
