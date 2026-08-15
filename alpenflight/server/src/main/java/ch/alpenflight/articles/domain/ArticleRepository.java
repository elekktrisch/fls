package ch.alpenflight.articles.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ArticleRepository {

    List<Article> findAllActive();

    List<Article> findAllActiveIncludingInactive();

    Optional<Article> findActiveById(UUID id);

    Optional<Article> findActiveByNumber(String number);

    Article save(Article article);

    void flush();
}
