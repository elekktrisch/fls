package ch.alpenflight.articles.domain;

public class DuplicateArticleNumberException extends RuntimeException {

    public DuplicateArticleNumberException(String articleNumber) {
        super("Article number already in use: " + articleNumber);
    }
}
