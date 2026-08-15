package ch.alpenflight.articles.infra;

import ch.alpenflight.articles.domain.Article;
import ch.alpenflight.articles.domain.ArticleRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaArticleRepository
        extends JpaRepository<Article, UUID>, ArticleRepository {

    @Override
    @Query("select a from Article a where a.deletedOn is null and a.isActive = true "
            + "order by a.articleNumber asc")
    List<Article> findAllActive();

    @Override
    @Query("select a from Article a where a.deletedOn is null "
            + "order by a.articleNumber asc")
    List<Article> findAllActiveIncludingInactive();

    @Override
    @Query("select a from Article a where a.id = :id and a.deletedOn is null")
    Optional<Article> findActiveById(@Param("id") UUID id);

    @Override
    @Query("select a from Article a where a.articleNumber = :number and a.deletedOn is null")
    Optional<Article> findActiveByNumber(@Param("number") String number);
}
