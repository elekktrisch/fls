package ch.alpenflight.articles.domain;

/**
 * Thrown when the chosen {@code articleNumber} already exists for the
 * caller's tenant (active row only — soft-deleted siblings are allowed to
 * recreate the same number). Uniqueness is per-tenant: the V3 partial UNIQUE
 * on {@code (operating_club_id, article_number) WHERE deleted_on IS NULL}
 * is the structural race catcher.
 */
public class DuplicateArticleNumberException extends RuntimeException {

    public DuplicateArticleNumberException(String articleNumber) {
        super("Article number already in use: " + articleNumber);
    }
}
